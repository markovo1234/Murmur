# Murmur wire protocol — version 1

Murmur phones exchange **packets** over Bluetooth LE. Every packet is signed by its sender and
flooded through the mesh by relays. All integers are **big-endian**. Strings are **UTF-8**; decoders
reject malformed UTF-8 so every accepted string re-encodes to the identical bytes.

The reference implementation is `core/src/main/kotlin/app/murmur/core/protocol/`.

**App 1.1 additions** (still packet `version` 1, fully interoperable with 1.0 phones): the ROOM
packet type (`0x05`) for #nearby extras and channels, four new DM kinds, relaying of unknown packet
types, and tolerance for trailing bytes in payloads so later versions can append fields. Sections
marked *(1.1)* describe them; see [Compatibility](#compatibility) for how 1.0 phones behave.

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

1. it decodes strictly (known version, exact length, valid payload),
2. `senderId == first8(SHA-256(included key))`,
3. the signature verifies,
4. `now - 12 h ≤ timestamp ≤ now + 1 h`.

*(1.1)* A packet with an **unknown `type`** is still checked (2–4) and, if valid, relayed like any
other, but never delivered. ANNOUNCE, PUBLIC, LEAVE, ROOM content and DM plaintexts **ignore trailing
bytes** after their known fields (1.0 rejected them), so a later version can append fields without
breaking 1.1 phones. PRIVATE payloads are length-exact.

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
| 1 | kind: `1` TEXT, `2` DELIVERED, `3` READ, `4` TYPING; *(1.1)* `5` REACTION, `6` RETRACT, `7` WAVE, `8` TIMER |
| 16 | `messageId` (TEXT: stable across resends; receipts: the acknowledged message) |
| 32 | sender's X25519 public key (so the recipient can always reply) |
| 2 + n | body: u16 length + UTF-8 (TEXT: 1–1000 bytes; others: see below) |

Relays forward PRIVATE packets they cannot read.

*(1.1)* Control kinds. They are sent once (no DELIVERED, no resend) and never answered with receipts:

| kind | `messageId` | body |
|---|---|---|
| REACTION | the reacted-to message | one emoji (≤ 32 bytes), or empty to remove my reaction |
| RETRACT | one of **my own** messages | empty — "delete for everyone" |
| WAVE | random | empty — a 👋 nudge |
| TIMER | random | disappearing-message time for this chat in seconds, decimal ("0" = off, max 2,419,200) |

**Replies** are ordinary TEXT whose first line is `> Author: snippet` (snippet ≤ 80 characters)
followed by a newline and the reply, so 1.0 phones show them as a readable quote.
**Mentions** are `@nickname` in the text, matched case-insensitively.

### `0x04` LEAVE

Empty payload. Flooded on graceful shutdown; receivers mark the sender offline.

### `0x05` ROOM *(1.1)*

Broadcast. #nearby extras and named channels.

| Size | Field |
|---:|---|
| 1 | flags: bit 0 = encrypted |
| 1 + n | channel: u8 length + ASCII; empty = #nearby, else 1–24 of `a–z 0–9 - _` (not `nearby`) |
| rest | room content, or (encrypted) 12-byte nonce ‖ ChaCha20-Poly1305 ciphertext ‖ tag of it |

Room content:

| Size | Field |
|---:|---|
| 1 | kind: `1` TEXT, `2` REACTION, `3` RETRACT, `4` SOS |
| 1 + n | sender nickname (as ANNOUNCE) |
| 16 | target `packetId` (REACTION / RETRACT; zeros otherwise) |
| 2 + n | body: u16 length + UTF-8 (TEXT 1–1000 bytes; REACTION: emoji or empty to remove; RETRACT: empty; SOS: optional text) |

* A **password channel** encrypts with
  `key = PBKDF2-HMAC-SHA256(password, salt = "murmur-channel-v1:" ‖ channel, 120,000 iterations, 32 bytes)`
  and `AAD = packetId ‖ senderId ‖ timestamp ‖ channel`. An open channel and a password channel may
  share a name; they never mix.
* Receivers deliver ROOM packets only for #nearby and channels they joined (and can decrypt); every
  valid ROOM packet is relayed regardless.
* RETRACT is honoured only when the target was sent by the same `senderId`. SOS is only valid in
  #nearby. Senders may use `ttl` 3 instead of 7 ("short reach").
* #nearby plain text still uses PUBLIC (`0x02`) so 1.0 phones see it; only REACTION, RETRACT and SOS
  in #nearby use ROOM.

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

## Compatibility

| Sent by 1.1 | What a 1.0 phone does |
|---|---|
| #nearby text, DMs, receipts, typing | works as before |
| a reply | shows it as text with the `> Author: …` quote line |
| DM REACTION / RETRACT / WAVE / TIMER | ignores it (not shown; no receipt) |
| ROOM (reactions, SOS, channels) | drops it and doesn't relay it — the mesh still carries it through 1.1 phones |
| trailing bytes / unknown types from a future version | 1.0 drops them; 1.1 accepts or relays them |

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
