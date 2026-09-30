package app.murmur.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.murmur.core.SecureRandomSource
import app.murmur.core.crypto.Identity
import app.murmur.diagnostics.DiagnosticsLog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the identity's two private keys, wrapped with an AES-256-GCM key that lives in the Android
 * Keystore (never exported). If unwrapping fails (e.g. the Keystore key was lost after a restore),
 * a fresh identity is created instead of crashing.
 *
 * File layout: version (1) || iv (12) || AES-GCM(signingPrivate(32) || agreementPrivate(32)).
 */
class IdentityStore(context: Context, private val log: DiagnosticsLog) {
    private val file = File(context.noBackupFilesDir, "identity.bin")
    @Volatile
    private var cached: Identity? = null

    @Synchronized
    fun loadOrCreate(): Identity {
        cached?.let { return it }
        val loaded = if (file.exists()) {
            try {
                unwrap(file.readBytes())
            } catch (e: Exception) {
                log.log("IDENTITY", "could not unwrap stored identity (${e.javaClass.simpleName}); creating a new one")
                null
            }
        } else {
            null
        }
        val identity = loaded ?: Identity.generate(SecureRandomSource()).also { fresh ->
            try {
                file.writeBytes(wrap(fresh))
                log.log("IDENTITY", "created identity ${fresh.peerId}")
            } catch (e: Exception) {
                log.log("IDENTITY", "could not store identity (${e.javaClass.simpleName}); it will not survive a restart")
            }
        }
        cached = identity
        return identity
    }

    /** Panic wipe: forget the keys and the Keystore wrapping key. */
    @Synchronized
    fun wipe() {
        cached = null
        file.delete()
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            log.log("IDENTITY", "keystore delete failed: ${e.javaClass.simpleName}")
        }
    }

    private fun wrap(identity: Identity): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val plain = identity.signing.privateKey + identity.agreement.privateKey
        val ciphertext = cipher.doFinal(plain)
        plain.fill(0)
        return byteArrayOf(VERSION) + cipher.iv + ciphertext
    }

    private fun unwrap(bytes: ByteArray): Identity {
        require(bytes.size > 1 + IV_SIZE && bytes[0] == VERSION) { "bad identity file" }
        val iv = bytes.copyOfRange(1, 1 + IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = existingKey() ?: error("wrapping key missing")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(bytes, 1 + IV_SIZE, bytes.size - 1 - IV_SIZE)
        require(plain.size == 64) { "bad key material" }
        val identity = Identity.fromPrivateKeys(plain.copyOfRange(0, 32), plain.copyOfRange(32, 64))
        plain.fill(0)
        return identity
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun wrappingKey(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "murmur_identity_wrap_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_SIZE = 12
    }
}
