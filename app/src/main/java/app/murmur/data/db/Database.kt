package app.murmur.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/** Conversation id of the public #nearby channel. DM conversations use the peer's hex id. */
const val NEARBY_CONVERSATION = "nearby"

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val nickname: String?,
    val emoji: String?,
    val colorIndex: Int,
    /** Hex Ed25519 public key (for safety numbers while the peer is offline). */
    val signingKey: String?,
    val lastSeen: Long,
    val verified: Boolean = false,
    val blocked: Boolean = false,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    /** Hex peer id for DMs, null for #nearby. */
    val peerId: String?,
    val preview: String,
    val lastActivity: Long,
    val unread: Int,
)

/**
 * Primary key: packetId (hex) for #nearby messages, messageId (hex) for DMs — so duplicates are
 * ignored on insert.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "sortKey"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val senderNickname: String,
    val body: String,
    /** Sender's timestamp. */
    val sentAt: Long,
    /** Local ordering key (arrival time for incoming, creation time for outgoing). */
    val sortKey: Long,
    val outgoing: Boolean,
    /** [app.murmur.core.mesh.DeliveryStatus] ordinal for outgoing DMs; -1 otherwise. */
    val status: Int,
    val hops: Int,
    /** For incoming DMs: a READ receipt was sent (or read receipts were off when it was seen). */
    val seen: Boolean,
)

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peerId = :id")
    suspend fun get(id: String): PeerEntity?

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("UPDATE peers SET blocked = :blocked WHERE peerId = :id")
    suspend fun setBlocked(id: String, blocked: Boolean)

    @Query("UPDATE peers SET verified = :verified WHERE peerId = :id")
    suspend fun setVerified(id: String, verified: Boolean)

    @Query("SELECT peerId FROM peers WHERE blocked = 1")
    fun observeBlocked(): Flow<List<String>>
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastActivity DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unread = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COALESCE(SUM(unread), 0) FROM conversations")
    fun observeTotalUnread(): Flow<Int>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sortKey DESC LIMIT :limit")
    fun observeConversation(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    /** Receipts only ever move a message forward (Failed < Delivered < Read). */
    @Query("UPDATE messages SET status = :status WHERE id = :id AND conversationId = :conversationId AND outgoing = 1 AND status < :status")
    suspend fun upgradeStatus(id: String, conversationId: String, status: Int): Int

    /** Sender-side transitions (pending/sending/sent/failed) never override Delivered/Read. */
    @Query("UPDATE messages SET status = :status WHERE id = :id AND outgoing = 1 AND status < :deliveredOrdinal")
    suspend fun setSendStatus(id: String, status: Int, deliveredOrdinal: Int): Int

    @Query("SELECT * FROM messages WHERE outgoing = 1 AND conversationId != '$NEARBY_CONVERSATION' AND status IN (:statuses)")
    suspend fun outgoingWithStatus(statuses: List<Int>): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND outgoing = 0 AND seen = 0")
    suspend fun unseenIncoming(conversationId: String): List<MessageEntity>

    @Query("UPDATE messages SET seen = 1 WHERE id IN (:ids)")
    suspend fun markSeen(ids: List<String>)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = '$NEARBY_CONVERSATION' AND sortKey < :before")
    suspend fun purgeNearbyBefore(before: Long): Int

    @Query("DELETE FROM messages WHERE senderId IN (:senderIds)")
    suspend fun deleteFromSenders(senderIds: List<String>): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sortKey DESC LIMIT 1")
    suspend fun latest(conversationId: String): MessageEntity?
}

@Database(
    entities = [PeerEntity::class, ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MurmurDatabase : RoomDatabase() {
    abstract fun peers(): PeerDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao

    companion object {
        fun create(context: Context): MurmurDatabase =
            Room.databaseBuilder(context, MurmurDatabase::class.java, "murmur.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/** Runs [block] in a Room transaction. */
suspend fun <T> MurmurDatabase.tx(block: suspend () -> T): T = withTransaction { block() }
