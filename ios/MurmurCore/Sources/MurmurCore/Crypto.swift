import CommonCrypto
import CryptoKit
import Foundation

// Same algorithms and byte layouts as the Kotlin `app.murmur.core.crypto` (BouncyCastle there,
// CryptoKit + CommonCrypto here).

public enum Sha256 {
    public static let size = 32

    public static func hash(_ parts: [UInt8]...) -> [UInt8] {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Array(h.finalize())
    }
}

/// Ed25519 key pair. `privateKey` is the 32-byte seed.
public final class SigningKeyPair {
    private let key: Curve25519.Signing.PrivateKey
    public let publicKey: [UInt8]

    public init(privateKey seed: [UInt8]) throws {
        key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        publicKey = Array(key.publicKey.rawRepresentation)
    }

    public var privateKey: [UInt8] { Array(key.rawRepresentation) }

    /// Signs the concatenation of `parts`. (CryptoKit's Ed25519 signatures are randomized; they verify
    /// exactly like deterministic ones.)
    public func sign(_ parts: [UInt8]...) -> [UInt8] {
        let message = parts.flatMap { $0 }
        // Signing only fails for a corrupted key object.
        return Array((try? key.signature(for: message)) ?? Data())
    }
}

public enum Ed25519 {
    public static let publicKeySize = 32
    public static let signatureSize = 64

    public static func generate(_ random: RandomSource) -> SigningKeyPair {
        // A 32-byte seed is always a valid key.
        try! SigningKeyPair(privateKey: random.nextBytes(32))
    }

    /// Verifies `signature` over the concatenation of `parts`. Never throws.
    public static func verify(publicKey: [UInt8], signature: [UInt8], _ parts: [UInt8]...) -> Bool {
        guard publicKey.count == publicKeySize, signature.count == signatureSize,
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey) else { return false }
        return key.isValidSignature(signature, for: parts.flatMap { $0 })
    }
}

/// X25519 key pair.
public final class AgreementKeyPair {
    private let key: Curve25519.KeyAgreement.PrivateKey
    public let publicKey: [UInt8]

    public init(privateKey: [UInt8]) throws {
        key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        publicKey = Array(key.publicKey.rawRepresentation)
    }

    public var privateKey: [UInt8] { Array(key.rawRepresentation) }

    /// Raw X25519 shared secret, or nil if `peerPublicKey` is malformed or low-order (all-zero result,
    /// which BouncyCastle rejects too).
    public func agree(_ peerPublicKey: [UInt8]) -> [UInt8]? {
        guard peerPublicKey.count == X25519.publicKeySize,
              let peer = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKey),
              let secret = try? key.sharedSecretFromKeyAgreement(with: peer) else { return nil }
        let raw = secret.withUnsafeBytes { Array($0) }
        return raw.allSatisfy { $0 == 0 } ? nil : raw
    }
}

public enum X25519 {
    public static let publicKeySize = 32

    public static func generate(_ random: RandomSource) -> AgreementKeyPair {
        try! AgreementKeyPair(privateKey: random.nextBytes(32))
    }
}

/// A node's long-term identity: an Ed25519 signing key and an X25519 agreement key.
public final class Identity {
    public let signing: SigningKeyPair
    public let agreement: AgreementKeyPair
    public let peerId: PeerId

    public init(signing: SigningKeyPair, agreement: AgreementKeyPair) {
        self.signing = signing
        self.agreement = agreement
        peerId = PeerId.fromPublicKey(signing.publicKey)
    }

    public static func generate(_ random: RandomSource) -> Identity {
        Identity(signing: Ed25519.generate(random), agreement: X25519.generate(random))
    }

    public static func fromPrivateKeys(signing: [UInt8], agreement: [UInt8]) throws -> Identity {
        Identity(signing: try SigningKeyPair(privateKey: signing), agreement: try AgreementKeyPair(privateKey: agreement))
    }
}

/// ChaCha20-Poly1305 with a 16-byte tag; output = ciphertext || tag (BouncyCastle's layout).
enum ChaCha {
    static func seal(key: [UInt8], nonce: [UInt8], aad: [UInt8], plaintext: [UInt8]) -> [UInt8]? {
        guard let n = try? ChaChaPoly.Nonce(data: nonce),
              let box = try? ChaChaPoly.seal(plaintext, using: SymmetricKey(data: key), nonce: n, authenticating: aad) else { return nil }
        return Array(box.ciphertext) + Array(box.tag)
    }

    /// Nil on authentication failure. Never throws.
    static func open(key: [UInt8], nonce: [UInt8], aad: [UInt8], sealed: [UInt8]) -> [UInt8]? {
        guard sealed.count >= DmCrypto.tagSize, let n = try? ChaChaPoly.Nonce(data: nonce) else { return nil }
        let split = sealed.count - DmCrypto.tagSize
        guard let box = try? ChaChaPoly.SealedBox(nonce: n, ciphertext: sealed[..<split], tag: sealed[split...]),
              let plain = try? ChaChaPoly.open(box, using: SymmetricKey(data: key), authenticating: aad) else { return nil }
        return Array(plain)
    }
}

/// End-to-end encryption for PRIVATE packets.
///
/// ephemeral X25519 → shared secret with the recipient's X25519 key →
/// HKDF-SHA256(salt = ephemeralPub || recipientPub, info = "murmur-dm-v1") → 32-byte key →
/// ChaCha20-Poly1305 with a random 12-byte nonce and the caller's AAD.
public enum DmCrypto {
    public static let nonceSize = 12
    public static let tagSize = 16
    private static let info = Array("murmur-dm-v1".utf8)

    public struct Sealed {
        public let ephemeralKey: [UInt8]
        public let nonce: [UInt8]
        public let ciphertext: [UInt8]
    }

    /// Nil if `recipientAgreementKey` is unusable. Draws 32 bytes (ephemeral key) then 12 (nonce).
    public static func seal(_ plaintext: [UInt8], recipientAgreementKey: [UInt8], aad: [UInt8], random: RandomSource) -> Sealed? {
        let ephemeral = X25519.generate(random)
        guard let shared = ephemeral.agree(recipientAgreementKey) else { return nil }
        let key = deriveKey(shared, ephemeral.publicKey, recipientAgreementKey)
        let nonce = random.nextBytes(nonceSize)
        guard let ciphertext = ChaCha.seal(key: key, nonce: nonce, aad: aad, plaintext: plaintext) else { return nil }
        return Sealed(ephemeralKey: ephemeral.publicKey, nonce: nonce, ciphertext: ciphertext)
    }

    /// The plaintext, or nil if the key, nonce, AAD or ciphertext don't match. Never throws.
    public static func open(_ sealed: Sealed, recipient: AgreementKeyPair, aad: [UInt8]) -> [UInt8]? {
        guard sealed.nonce.count == nonceSize, sealed.ciphertext.count >= tagSize,
              let shared = recipient.agree(sealed.ephemeralKey) else { return nil }
        let key = deriveKey(shared, sealed.ephemeralKey, recipient.publicKey)
        return ChaCha.open(key: key, nonce: sealed.nonce, aad: aad, sealed: sealed.ciphertext)
    }

    static func deriveKey(_ shared: [UInt8], _ ephemeralPub: [UInt8], _ recipientPub: [UInt8]) -> [UInt8] {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: shared),
            salt: ephemeralPub + recipientPub,
            info: info,
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Array($0) }
    }
}

/// Password-protected channels. Everyone who knows the name and password derives the same key:
/// PBKDF2-HMAC-SHA256(password, salt = "murmur-channel-v1:" + name, 120,000 iterations). Each message:
/// ChaCha20-Poly1305 with a random 12-byte nonce, AAD = packetId || senderId || timestamp || name.
public enum ChannelCrypto {
    public static let defaultIterations = 120_000
    public static let keySize = 32

    public static func deriveKey(channel: String, password: String, iterations: Int = defaultIterations) -> [UInt8] {
        Pbkdf2.derive(password: Array(password.utf8), salt: Array("murmur-channel-v1:\(channel)".utf8), iterations: iterations, size: keySize)
    }

    public static func aad(packetId: [UInt8], senderId: [UInt8], timestamp: Int64, channel: String) -> [UInt8] {
        var w = ByteWriter(capacity: 40)
        w.append(packetId)
        w.append(senderId)
        w.i64(timestamp)
        w.append(Array(channel.utf8))
        return w.bytes
    }

    /// nonce || ciphertext+tag.
    public static func seal(key: [UInt8], plaintext: [UInt8], aad: [UInt8], random: RandomSource) -> [UInt8] {
        let nonce = random.nextBytes(DmCrypto.nonceSize)
        guard let c = ChaCha.seal(key: key, nonce: nonce, aad: aad, plaintext: plaintext) else {
            preconditionFailure("encryption failed")
        }
        return nonce + c
    }

    /// Nil if the key, AAD or bytes don't match. Never throws.
    public static func open(key: [UInt8], sealed: [UInt8], aad: [UInt8]) -> [UInt8]? {
        guard key.count == keySize, sealed.count >= DmCrypto.nonceSize + DmCrypto.tagSize else { return nil }
        return ChaCha.open(key: key, nonce: Array(sealed[..<DmCrypto.nonceSize]), aad: aad, sealed: Array(sealed[DmCrypto.nonceSize...]))
    }
}

/// Call audio. nonce = direction (1 = from the caller, 2 = from the callee) || 7 zero bytes || seq (u32);
/// AAD = callId || senderId || recipientId || seq.
public enum CallCrypto {
    public static let keySize = 32

    public static func newKey(_ random: RandomSource) -> [UInt8] { random.nextBytes(keySize) }

    public static func nonce(fromCaller: Bool, seq: UInt32) -> [UInt8] {
        var n = [UInt8](repeating: 0, count: DmCrypto.nonceSize)
        n[0] = fromCaller ? 1 : 2
        for i in 0..<4 { n[DmCrypto.nonceSize - 4 + i] = UInt8(truncatingIfNeeded: seq >> (24 - 8 * UInt32(i))) }
        return n
    }

    public static func aad(callId: [UInt8], senderId: PeerId, recipientId: PeerId, seq: UInt32) -> [UInt8] {
        var w = ByteWriter(capacity: 36)
        w.append(callId)
        w.append(senderId.toBytes())
        w.append(recipientId.toBytes())
        w.u32(seq)
        return w.bytes
    }

    public static func seal(key: [UInt8], fromCaller: Bool, seq: UInt32, aad: [UInt8], plaintext: [UInt8]) -> [UInt8] {
        precondition(key.count == keySize)
        guard let c = ChaCha.seal(key: key, nonce: nonce(fromCaller: fromCaller, seq: seq), aad: aad, plaintext: plaintext) else {
            preconditionFailure("encryption failed")
        }
        return c
    }

    public static func open(key: [UInt8], fromCaller: Bool, seq: UInt32, aad: [UInt8], sealed: [UInt8]) -> [UInt8]? {
        guard key.count == keySize, sealed.count >= DmCrypto.tagSize else { return nil }
        return ChaCha.open(key: key, nonce: nonce(fromCaller: fromCaller, seq: seq), aad: aad, sealed: sealed)
    }
}

/// App-lock PINs are stored only as a salted PBKDF2-HMAC-SHA256 hash.
public enum PinHasher {
    public static let iterations = 60_000
    public static let saltSize = 16

    public static func newSalt(_ random: RandomSource) -> [UInt8] { random.nextBytes(saltSize) }

    public static func hash(pin: String, salt: [UInt8], iterations: Int = iterations) -> [UInt8] {
        Pbkdf2.derive(password: Array(pin.utf8), salt: salt, iterations: iterations, size: 32)
    }

    public static func verify(pin: String, salt: [UInt8], expected: [UInt8], iterations: Int = iterations) -> Bool {
        let actual = hash(pin: pin, salt: salt, iterations: iterations)
        guard actual.count == expected.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<actual.count { diff |= actual[i] ^ expected[i] }
        return diff == 0
    }
}

enum Pbkdf2 {
    static func derive(password: [UInt8], salt: [UInt8], iterations: Int, size: Int) -> [UInt8] {
        precondition(iterations > 0 && size > 0)
        var out = [UInt8](repeating: 0, count: size)
        let status = password.withUnsafeBufferPointer { pw in
            CCKeyDerivationPBKDF(
                CCPBKDFAlgorithm(kCCPBKDF2),
                pw.baseAddress.map { UnsafeRawPointer($0).assumingMemoryBound(to: Int8.self) },
                password.count,
                salt,
                salt.count,
                CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                UInt32(iterations),
                &out,
                size
            )
        }
        precondition(status == Int32(kCCSuccess), "PBKDF2 failed: \(status)")
        return out
    }
}

/// Safety number: SHA-256 over both Ed25519 public keys in sorted (unsigned lexicographic) order,
/// rendered as 24 digits in 6 groups of 4. Identical on both phones.
public enum SafetyNumber {
    public static func digits(_ keyA: [UInt8], _ keyB: [UInt8]) -> String {
        let (first, second) = Hex.compare(keyA, keyB) <= 0 ? (keyA, keyB) : (keyB, keyA)
        let hash = Sha256.hash(first, second)
        var s = ""
        for group in 0..<6 {
            var v: UInt64 = 0
            for i in 0..<5 { v = v << 8 | UInt64(hash[group * 5 + i]) }
            let part = String(v % 10_000)
            s += String(repeating: "0", count: 4 - part.count) + part
        }
        return s
    }

    /// "1234 5678 9012 3456 7890 1234".
    public static func formatted(_ keyA: [UInt8], _ keyB: [UInt8]) -> String {
        let d = Array(digits(keyA, keyB))
        return stride(from: 0, to: d.count, by: 4).map { String(d[$0..<min($0 + 4, d.count)]) }.joined(separator: " ")
    }
}
