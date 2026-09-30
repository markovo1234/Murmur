package app.murmur.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
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

/** Conversation id of the public #nearby room. DMs use the peer's hex id; channels [channelConversation]. */
const val NEARBY_CONVERSATION = "nearby"
const val CHANNEL_PREFIX = "ch:"

fun channelConversation(name: String): String = CHANNEL_PREFIX + name
fun channelOf(conversationId: String): String? = conversationId.takeIf { it.startsWith(CHANNEL_PREFIX) }?.removePrefix(CHANNEL_PREFIX)

/** [MessageEntity.kind] values. */
object MessageKind {
    const val TEXT = 0

    /** "Luna waved 👋", "Messages now disappear after 1 hour", … */
    const val SYSTEM = 1

    /** An emergency alert in #nearby. */
    const val SOS = 2

    /** A channel invitation in a DM; body = [app.murmur.core.protocol.ChannelInvites] format. */
    const val INVITE = 3
}

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
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    /** A name only this phone uses for the peer. */
    val alias: String? = null,
    @ColumnInfo(defaultValue = "0") val firstSeen: Long = 0,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    /** Hex peer id for DMs, null for #nearby and channels. */
    val peerId: String?,
    val preview: String,
    val lastActivity: Long,
    val unread: Int,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val muted: Boolean = false,
    /** DMs: messages disappear this many seconds after they arrive (0 = off). */
    @ColumnInfo(defaultValue = "0") val disappearSeconds: Long = 0,
    @ColumnInfo(defaultValue = "") val draft: String = "",
)

/**
 * Primary key: packetId (hex) for #nearby and channel messages, messageId (hex) for DMs — so duplicates
 * are ignored on insert, and reactions/retractions can point at them.
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
    @ColumnInfo(defaultValue = "0") val kind: Int = MessageKind.TEXT,
    /** Deleted for everyone by its sender. */
    @ColumnInfo(defaultValue = "0") val retracted: Boolean = false,
    /** Disappearing messages: deleted at this time (0 = never). */
    @ColumnInfo(defaultValue = "0") val expiresAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val deliveredAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val readAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val mentionsMe: Boolean = false,
)

/** One reaction per person per message. */
@Entity(
    tableName = "reactions",
    primaryKeys = ["messageId", "reactorId"],
    indices = [Index(value = ["conversationId"])],
)
data class ReactionEntity(
    val messageId: String,
    val conversationId: String,
    val reactorId: String,
    val emoji: String,
    val time: Long,
)

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val name: String,
    /** Hex 32-byte key for password-protected channels; null for open channels. */
    val keyHex: String?,
    val joinedAt: Long,
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

    @Query("UPDATE peers SET favorite = :favorite WHERE peerId = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE peers SET alias = :alias WHERE peerId = :id")
    suspend fun setAlias(id: String, alias: String?)

    @Query("SELECT peerId FROM peers WHERE blocked = 1")
    fun observeBlocked(): Flow<List<String>>
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, lastActivity DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unread = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE conversations SET unread = 0")
    suspend fun markAllRead()

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean)

    @Query("UPDATE conversations SET disappearSeconds = :seconds WHERE id = :id")
    suspend fun setDisappear(id: String, seconds: Long)

    @Query("UPDATE conversations SET draft = :draft WHERE id = :id")
    suspend fun setDraft(id: String, draft: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    /** Muted chats don't count towards the Chats badge. */
    @Query("SELECT COALESCE(SUM(unread), 0) FROM conversations WHERE muted = 0")
    fun observeTotalUnread(): Flow<Int>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sortKey DESC LIMIT :limit")
    fun observeConversation(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND retracted = 0 AND kind IN (0, 2) " +
            "AND body LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY sortKey DESC LIMIT 200",
    )
    fun search(conversationId: String, query: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    /** Receipts only ever move a message forward (Failed < Delivered < Read). */
    @Query("UPDATE messages SET status = :status WHERE id = :id AND conversationId = :conversationId AND outgoing = 1 AND status < :status")
    suspend fun upgradeStatus(id: String, conversationId: String, status: Int): Int

    @Query("UPDATE messages SET deliveredAt = :time WHERE id = :id AND conversationId = :conversationId AND deliveredAt = 0")
    suspend fun setDeliveredAt(id: String, conversationId: String, time: Long)

    @Query("UPDATE messages SET readAt = :time WHERE id = :id AND conversationId = :conversationId AND readAt = 0")
    suspend fun setReadAt(id: String, conversationId: String, time: Long)

    /** Sender-side transitions (pending/sending/sent/failed) never override Delivered/Read. */
    @Query("UPDATE messages SET status = :status WHERE id = :id AND outgoing = 1 AND status < :deliveredOrdinal")
    suspend fun setSendStatus(id: String, status: Int, deliveredOrdinal: Int): Int

    /** Delete for everyone: only the original sender's request counts. */
    @Query("UPDATE messages SET retracted = 1, body = '' WHERE id = :id AND conversationId = :conversationId AND senderId = :senderId")
    suspend fun retract(id: String, conversationId: String, senderId: String): Int

    @Query("SELECT * FROM messages WHERE outgoing = 1 AND conversationId != '$NEARBY_CONVERSATION' AND conversationId NOT LIKE '$CHANNEL_PREFIX%' AND status IN (:statuses) AND retracted = 0")
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

    @Query("DELETE FROM messages WHERE expiresAt > 0 AND expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM messages WHERE senderId IN (:senderIds)")
    suspend fun deleteFromSenders(senderIds: List<String>): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sortKey DESC LIMIT 1")
    suspend fun latest(conversationId: String): MessageEntity?
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE conversationId = :conversationId")
    fun observe(conversationId: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE messageId = :messageId AND reactorId = :reactorId")
    suspend fun get(messageId: String, reactorId: String): ReactionEntity?

    @Upsert
    suspend fun upsert(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND reactorId = :reactorId")
    suspend fun delete(messageId: String, reactorId: String)

    @Query("DELETE FROM reactions WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query("DELETE FROM reactions WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    /** Reactions whose message no longer exists (expired, purged, deleted). */
    @Query("DELETE FROM reactions WHERE messageId NOT IN (SELECT id FROM messages)")
    suspend fun deleteOrphans(): Int
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY joinedAt")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE name = :name")
    suspend fun get(name: String): ChannelEntity?

    @Upsert
    suspend fun upsert(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE name = :name")
    suspend fun delete(name: String)
}

@Database(
    entities = [
        PeerEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ReactionEntity::class,
        ChannelEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class MurmurDatabase : RoomDatabase() {
    abstract fun peers(): PeerDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun reactions(): ReactionDao
    abstract fun channels(): ChannelDao

    companion object {
        fun create(context: Context): MurmurDatabase =
            Room.databaseBuilder(context, MurmurDatabase::class.java, "murmur.db")
                // Only used if a future version ships without a migration; 1 → 2 is migrated in place.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/** Runs [block] in a Room transaction. */
suspend fun <T> MurmurDatabase.tx(block: suspend () -> T): T = withTransaction { block() }
