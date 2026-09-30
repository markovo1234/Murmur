# Murmur wire protocol — version 1

Murmur phones exchange **packets** over Bluetooth LE. Every packet is signed by its sender and
flooded through the mesh by relays. All integers are **big-endian**. Strings are **UTF-8**; decoders
reject malformed UTF-8 so every accepted string re-encodes to the identical bytes.

The reference implementation is `core/src/main/kotlin/app/murmur/core/protocol/`.

## Identity

| Item | Definition |
|---|---|
| Signing key | Ed25519 (32-byte public key) |
| Agreement key | X25519 (32-byte public key) |
| `peerId` | first 8 bytes of `SHA-256(Ed25519 public key)` |
| Broadcast id | `FF FF FF FF FF FF FF FF` |

`peerId`s are ordered as unsigned 64-bit integers (the same as comparing the bytes), which the BLE
link dedupe relies on.

## Packet

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | `version` = `0x01` |
| 1 | 1 | `type` (see below) |
| 2 | 1 | `ttl` (1…7; every packet starts at 7) |
| 3 | 16 | `packetId` (random) |
| 19 | 8 | `senderId` |
| 27 | 8 | `recipientId` (broadcast for ANNOUNCE, PUBLIC, LEAVE; a peer for PRIVATE) |
| 35 | 8 | `timestamp` (signed int64, ms since Unix epoch) |
| 43 | 32 | sender's Ed25519 public key |
| 75 | 2 | payload length `N` (≤ 2048) |
| 77 | N | payload |
| 77+N | 64 | Ed25519 signature |

Total size = 141 + N bytes (≤ 2189).

**Signature** — Ed25519 over bytes `[0, 2) ‖ [3, 77+N)`: everything except the `ttl` byte and the
signature itself. Relays decrement `ttl` without re-signing.

**Acceptance** — a receiver drops a packet unless:

1. it decodes strictly (known version and type, exact length, valid payload, no trailing bytes),
2. `senderId == first8(SHA-256(included key))`,
3. the signature verifies,
4. `now - 12 h ≤ timestamp ≤ now + 1 h`.

## Payloads

### `0x01` ANNOUNCE

| Size | Field |
|---:|---|
| 1 + n | nickname: u8 length + UTF-8 (1–20 code points, ≤ 80 bytes, no control chars, no surrounding spaces) |
| 1 + n | avatar emoji: u8 length + UTF-8 (1–32 bytes) |
| 1 | avatar color index (0–9) |
| 32 | X25519 public key |

Sent on every new link (ttl 7, on that link only), flooded every 30 s (60 s in background), and right
after a profile edit. The sender of the first ANNOUNCE received on a link **with ttl 7** is that link's
direct peer.

### `0x02` PUBLIC (#nearby)

| Size | Field |
|---:|---|
| 1 + n | sender nickname (as ANNOUNCE) |
| 2 + n | text: u16 length + UTF-8 (1–1000 bytes) |

### `0x03` PRIVATE (end-to-end encrypted)

| Size | Field |
|---:|---|
| 32 | ephemeral X25519 public key `E` |
| 12 | nonce (random) |
| rest | ChaCha20-Poly1305 ciphertext ‖ 16-byte tag (≥ 67 bytes) |

Encryption, per packet:

1. fresh ephemeral X25519 key pair `(e, E)`;
2. `shared = X25519(e, R)` where `R` is the recipient's X25519 public key;
3. `key = HKDF-SHA256(ikm = shared, salt = E ‖ R, info = "murmur-dm-v1", L = 32)`;
4. `ChaCha20-Poly1305(key, nonce, AAD = packetId ‖ senderId ‖ recipientId ‖ timestamp)`.

Inner plaintext:

| Size | Field |
|---:|---|
| 1 | kind: `1` TEXT, `2` DELIVERED, `3` READ, `4` TYPING |
| 16 | `messageId` (TEXT: stable across resends; receipts: the acknowledged message) |
| 32 | sender's X25519 public key (so the recipient can always reply) |
| 2 + n | body: u16 length + UTF-8 (TEXT: 1–1000 bytes; others: empty) |

Relays forward PRIVATE packets they cannot read.

### `0x04` LEAVE

Empty payload. Flooded on graceful shutdown; receivers mark the sender offline.

## Mesh rules

* Remember the last 10,000 `packetId`s (including one's own). Duplicates are dropped.
* A new valid packet is **delivered** if it is broadcast or addressed to me.
* It is **relayed** if it is not addressed to me, relaying is enabled, and `ttl > 1`: forwarded with
  `ttl - 1` on every ready link except the one it arrived on.
* `hops = 8 - received ttl` (1 = heard directly).
* Peer status: **nearby** (direct link), **via mesh** (heard through relays in the last 90 s),
  **offline** (silent for 90 s, or sent LEAVE).

## DM delivery

`Pending → Sending → Sent → Delivered → Read`, or `Failed`.

* The recipient shows each `messageId` once but answers **every** copy with DELIVERED, and sends READ
  once the message is on screen (if read receipts are on).
* No DELIVERED within 30 s while the recipient is reachable → resend as a new packet (new `packetId`,
  same `messageId`), up to 3 times, then Failed.
* Recipient offline → Pending on the sender only; sent when the recipient is heard again; Failed after 24 h.
* TYPING: at most one per 3 s while typing; shown for 5 s; never stored.

## Safety number

`SHA-256(K1 ‖ K2)` where `K1, K2` are both Ed25519 public keys sorted as unsigned byte strings. For
each group `g = 0…5`, bytes `[5g, 5g+5)` are read as a 40-bit unsigned integer and rendered as
`value mod 10000`, zero-padded to 4 digits: 24 digits in 6 groups, identical on both phones.

## BLE framing

One GATT service `8a51d968-575d-4be3-871d-5400a225aa2d` with one characteristic
`4a05bee7-c4c9-45a1-b06d-72d674c025b1` (WRITE + NOTIFY, CCCD `0x2902`). The client writes, the server
notifies. Each packet is split into fragments of at most `min(MTU - 3, 512)` bytes, header included:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | marker `0x4D` |
| 1 | 4 | `streamId` (per link, increments per packet) |
| 5 | 2 | fragment index (0-based) |
| 7 | 2 | fragment count |
| 9 | … | data |

All fragments of one packet are sent before the next packet. Incomplete streams are dropped after
30 s without progress.

Advertising data contains only the service UUID. The scan response carries service data under the
same UUID: the advertiser's 8-byte `peerId`.
