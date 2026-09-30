package app.murmur.core.crypto

import app.murmur.core.RandomSource
import app.murmur.core.protocol.Bytes
import app.murmur.core.protocol.PeerId
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object Sha256 {
    const val SIZE: Int = 32

    fun hash(vararg parts: ByteArray): ByteArray {
        val d = SHA256Digest()
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(SIZE).also { d.doFinal(it, 0) }
    }
}

/** Ed25519 key pair. [privateKey] is the 32-byte seed. */
class SigningKeyPair(privateKey: ByteArray) {
    private val params = Ed25519PrivateKeyParameters(privateKey.copyOf(), 0)
    val privateKey: ByteArray get() = params.encoded
    val publicKey: ByteArray = params.generatePublicKey().encoded

    fun sign(vararg parts: Pair<ByteArray, IntRange>): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, params)
        for ((bytes, range) in parts) {
            if (!range.isEmpty()) signer.update(bytes, range.first, range.last - range.first + 1)
        }
        return signer.generateSignature()
    }

    fun sign(message: ByteArray): ByteArray = sign(message to message.indices)
}

object Ed25519 {
    const val PUBLIC_KEY_SIZE: Int = 32
    const val SIGNATURE_SIZE: Int = 64

    fun generate(random: RandomSource): SigningKeyPair = SigningKeyPair(random.nextBytes(32))

    /** Verifies [signature] over the concatenation of the given byte ranges. Never throws. */
    fun verify(publicKey: ByteArray, signature: ByteArray, vararg parts: Pair<ByteArray, IntRange>): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            for ((bytes, range) in parts) {
                if (!range.isEmpty()) verifier.update(bytes, range.first, range.last - range.first + 1)
            }
            verifier.verifySignature(signature)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean =
        verify(publicKey, signature, message to message.indices)
}

/** X25519 key pair. */
class AgreementKeyPair(privateKey: ByteArray) {
    private val params = X25519PrivateKeyParameters(privateKey.copyOf(), 0)
    val privateKey: ByteArray get() = params.encoded
    val publicKey: ByteArray = params.generatePublicKey().encoded

    /** Raw X25519 shared secret, or null if [peerPublicKey] is malformed or low-order. */
    fun agree(peerPublicKey: ByteArray): ByteArray? {
        if (peerPublicKey.size != X25519.PUBLIC_KEY_SIZE) return null
        return try {
            val agreement = X25519Agreement()
            agreement.init(params)
            ByteArray(agreement.agreementSize).also {
                agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), it, 0)
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}

object X25519 {
    const val PUBLIC_KEY_SIZE: Int = 32

    fun generate(random: RandomSource): AgreementKeyPair = AgreementKeyPair(random.nextBytes(32))
}

/** A node's long-term identity: an Ed25519 signing key and an X25519 agreement key. */
class Identity(val signing: SigningKeyPair, val agreement: AgreementKeyPair) {
    val peerId: PeerId = PeerId.fromPublicKey(signing.publicKey)

    companion object {
        fun generate(random: RandomSource): Identity = Identity(Ed25519.generate(random), X25519.generate(random))

        fun fromPrivateKeys(signingPrivate: ByteArray, agreementPrivate: ByteArray): Identity =
            Identity(SigningKeyPair(signingPrivate), AgreementKeyPair(agreementPrivate))
    }
}

/**
 * End-to-end encryption for PRIVATE packets.
 *
 * ephemeral X25519 → shared secret with recipient X25519 key →
 * HKDF-SHA256(salt = ephemeralPub || recipientPub, info = "murmur-dm-v1") → 32-byte key →
 * ChaCha20-Poly1305 with a random 12-byte nonce and the caller's AAD.
 */
object DmCrypto {
    const val NONCE_SIZE: Int = 12
    const val TAG_SIZE: Int = 16
    private val INFO = "murmur-dm-v1".encodeToByteArray()

    class Sealed(val ephemeralKey: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)

    /** Returns null if [recipientAgreementKey] is unusable. */
    fun seal(plaintext: ByteArray, recipientAgreementKey: ByteArray, aad: ByteArray, random: RandomSource): Sealed? {
        val ephemeral = X25519.generate(random)
        val shared = ephemeral.agree(recipientAgreementKey) ?: return null
        val key = deriveKey(shared, ephemeral.publicKey, recipientAgreementKey)
        val nonce = random.nextBytes(NONCE_SIZE)
        val ciphertext = aead(true, key, nonce, aad, plaintext) ?: return null
        return Sealed(ephemeral.publicKey, nonce, ciphertext)
    }

    /** Returns the plaintext, or null if the key, nonce, AAD or ciphertext don't match. Never throws. */
    fun open(sealed: Sealed, recipient: AgreementKeyPair, aad: ByteArray): ByteArray? {
        if (sealed.nonce.size != NONCE_SIZE || sealed.ciphertext.size < TAG_SIZE) return null
        val shared = recipient.agree(sealed.ephemeralKey) ?: return null
        val key = deriveKey(shared, sealed.ephemeralKey, recipient.publicKey)
        return aead(false, key, sealed.nonce, aad, sealed.ciphertext)
    }

    private fun deriveKey(shared: ByteArray, ephemeralPub: ByteArray, recipientPub: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, ephemeralPub + recipientPub, INFO))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray? =
        try {
            val cipher = ChaCha20Poly1305()
            cipher.init(encrypt, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, aad))
            val out = ByteArray(cipher.getOutputSize(input.size))
            var n = cipher.processBytes(input, 0, input.size, out, 0)
            n += cipher.doFinal(out, n)
            if (n == out.size) out else out.copyOf(n)
        } catch (_: InvalidCipherTextException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}

/**
 * Safety number: SHA-256 over both Ed25519 public keys in sorted (unsigned lexicographic) order,
 * rendered as 24 digits in 6 groups of 4. Identical on both phones.
 */
object SafetyNumber {
    fun digits(keyA: ByteArray, keyB: ByteArray): String {
        val (first, second) = if (Bytes.compare(keyA, keyB) <= 0) keyA to keyB else keyB to keyA
        val hash = Sha256.hash(first, second)
        val sb = StringBuilder(24)
        for (group in 0 until 6) {
            var v = 0L
            for (i in 0 until 5) v = (v shl 8) or (hash[group * 5 + i].toLong() and 0xFF)
            sb.append((v % 10_000).toString().padStart(4, '0'))
        }
        return sb.toString()
    }

    /** "1234 5678 9012 3456 7890 1234". */
    fun formatted(keyA: ByteArray, keyB: ByteArray): String = digits(keyA, keyB).chunked(4).joinToString(" ")
}
