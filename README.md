# Murmur

Chat and call people nearby. No internet. No accounts. No servers.

Murmur is an Android app where phones talk to each other over **Bluetooth Low Energy** and relay
messages for each other in a mesh, so a message can reach someone who is out of your own range as long
as other Murmur phones sit in between. The app **has no internet permission**.

## Features

- **Radar**: an animated night-sky radar shows people nearby (by signal strength) and people reachable
  through the mesh (with a hop count).
- **#nearby**: a public room for everyone in range. Messages delete themselves after 24 hours.
- **Direct messages**: end-to-end encrypted. Relays pass them on but can't read them. Ticks show
  Pending/Sending → Sent → Delivered → Read; failed messages can be retried. Messages to someone who
  is offline wait on your phone (up to 24 h) and go out when they come back.
- **Typing indicator**, read receipts (can be turned off), notifications for DMs.
- **Safety numbers**: compare 24 digits with a friend to make sure nobody is impersonating them, then
  mark them as verified.
- **Block** anyone (their messages are still relayed for others, but you never see them).
- **Panic wipe**: hold for 2 seconds to erase all messages, settings and your identity keys.
- **Diagnostics** screen with the radio state, links, counters and a copyable log (for when something
  doesn't work and you can't read logcat).
- **Demo mode**: five pretend people who move around the radar, chat in #nearby and answer DMs, so you
  can try the whole app on one phone.
- Light and dark themes (dark-first), optional dynamic color, TalkBack labels everywhere, and respect
  for the system "Remove animations" setting.

### New in 1.2: voice calls

- Tap the 📞 button in a direct chat to **call** someone. Calls are end-to-end encrypted and go over
  Bluetooth only: no internet, no phone number. The other phone rings (with its ringtone, following
  silent/vibrate mode) and can answer from the app or the notification.
- Mute, speaker, a call timer and a quality indicator; the call screen can shrink to a pill so you
  can keep chatting. Calls keep going with the screen off. Missed calls and call durations appear in
  the chat.
- Voice is compressed with AMR-NB at 7.95 kbit/s (phone-call quality). Calls work best **directly
  between two phones in Bluetooth range**; through one or two relaying phones they work but can
  break up. Expect about half a second of delay.
- Both phones need Murmur 1.2. Murmur asks for the microphone the first time you call or answer.

### New in 1.1

- **Channels**: join or create `#topic` rooms from the Chats tab. Anyone who types the same name
  joins the same channel. Add a **password** and the messages are encrypted so only people who know
  it can read them (relays still carry them).
- **Replies**: swipe a message right (or long-press → Reply) to quote it; tap a quote to jump to the
  original.
- **Reactions**: long-press for 👍 ❤️ 😂 😮 😢 🙏, or double-tap for ❤️. Works in DMs, #nearby and channels.
- **Delete for everyone** on your own messages, and **Delete for me** on any message.
- **@mentions** with suggestions while typing; mentions of you are highlighted and always notify.
- **Formatting**: `*bold*`, `_italic_`, `~strike~` and `` `code` ``.
- **Disappearing messages** per DM (5 min, 1 h, 1 day, 1 week), set for both people.
- **Wave 👋** to nudge someone.
- **SOS alert** in #nearby (hold to send): everyone in range gets a loud, red alert.
- **Search** inside any chat; **drafts** are kept per chat.
- **Pin** and **mute** chats, **Mark all read**, message **info** (sent, delivered, read, route).
- **Favorites** (★) with an alert when a favorite comes into range, and private **nicknames** for
  people. A **People** screen lists everyone you've met.
- **App lock** with a PIN (also hides the app from screenshots and the recent-apps preview), and an
  option to **hide message text in notifications**.
- **Battery saver** (slower scanning in the background) and **message reach** (7 or 3 hops).
- A **relay counter** in Settings shows how many messages your phone has carried for others.

1.1 phones and 1.0 phones talk to each other: text, DMs and replies work both ways. Reactions,
deletions, waves, timers, SOS and channels need 1.1 on both ends; 1.0 phones just don't show them.
Updating keeps your chats (the database migrates in place).

## Install the APK on your phone (from GitHub Actions)

Every push builds the app on GitHub Actions and attaches the APKs to the run.

1. On your phone, open this repository on **github.com in the browser** and make sure you are signed in
   (artifacts can only be downloaded when signed in; the GitHub mobile app doesn't show them).
2. Tap **Actions** → pick the latest **Build** run with a green check for your branch.
3. Scroll down to **Artifacts** and tap **Murmur-apk**. A zip file downloads.
4. Open the zip in your Files app and tap **Murmur.apk**. If Android asks, allow your browser or Files
   app to **install unknown apps**, then tap **Install**.
5. Open Murmur and follow the three onboarding steps.

Notes:

- Install **Murmur.apk** (release). `Murmur-debug.apk` is the debug build; it works the same but
  animates noticeably slower.
- Both are signed with the same key (committed in `keystore/`), so a newer build installs over an older
  one and keeps your chats. You can also switch between the debug and release builds.
- Some phones (Xiaomi, Huawei, Samsung, OnePlus…) aggressively stop background apps. If Murmur stops
  relaying when the screen is off, set its battery usage to **Unrestricted**.

## Build it yourself

Requirements: JDK 17 or newer and the Android SDK (platform 37 and build-tools; the Android Gradle
plugin downloads missing pieces if the SDK licenses are accepted).

```sh
./gradlew :core:test          # protocol, crypto, fragmentation and mesh simulation tests
./gradlew :app:lintDebug      # Android lint (0 errors)
./gradlew :app:dist           # → dist/Murmur.apk (release) and dist/Murmur-debug.apk
```

If the SDK isn't found, create `local.properties` with `sdk.dir=/path/to/Android/sdk` or set
`ANDROID_HOME`.

## Test plan with 2–3 phones

Install Murmur on every phone, finish onboarding with different nicknames, grant all permissions and
keep Bluetooth on. Open **Settings → Diagnostics** on any phone to see links, counters and the log; tap
**Copy** to share the log.

1. **Direct chat (A and B side by side)**
   - Within about 10–20 s each phone's Radar shows the other on an inner ring and the status pill says
     "1 nearby".
   - Send a message in **#nearby** from A: it appears on B.
   - Open the other person from the Radar → **Message**. Type on A: B sees the typing dots. Send: A's
     tick goes clock → ✓ → ✓✓; when B opens the chat, A's ticks turn mint (Read).
   - Open the peer sheet on both phones: the safety numbers must be identical. Mark as verified.
2. **Three-phone relay (A and C out of each other's range)**
   - Put B in the middle and A and C far apart (different floors, or ~40–60 m apart outdoors; walls
     help). In Diagnostics, A must have a link to B but **not** to C.
   - A's Radar shows C on the dashed outer ring with an amber "2 hops" badge ("via mesh · 2 hops").
   - A sends a DM to C: it arrives on C, A's ticks reach ✓✓, and B's **Relayed** counter goes up
     (B cannot read it).
   - Post in #nearby on C: A receives it with a hop badge.
3. **DM to an offline phone, delivered later**
   - On C, tap **Stop** in the "Murmur is active" notification (or turn Bluetooth off).
   - After ~90 s, A shows C as offline. Send C a DM: it stays on the clock (Pending).
   - Start C again (open the app / turn Bluetooth on). Within ~30 s the DM arrives and A's ticks turn
     ✓✓. (Pending messages fail after 24 h; "tap to retry" resends them.)
4. **Bluetooth off/on recovery**
   - Turn Bluetooth off on B: the pill says "Bluetooth off" and a banner offers **Turn on**. The other
     phones mark B offline after ~90 s.
   - Turn it back on: B reconnects by itself within ~30 s. The Diagnostics log shows "radio down" and
     "radio up".
5. **Background notification**
   - Keep "Keep running in background" on (default). Lock B's screen.
   - A sends B a DM: B gets a notification; tapping it opens that chat. The persistent notification
     reads "Murmur is active · N nearby" and has a **Stop** action.
   - Turn "Keep running in background" off: leaving the app stops the mesh.
6. **Panic wipe**
   - On B: Settings → **Hold to wipe everything** for 2 s (releasing early springs back).
   - B returns to onboarding with no chats. After onboarding again, B appears to the others as a new
     person, and the safety numbers are different.

7. **1.1 features (two phones with 1.1)**
   - Chats → **Join channel** → `#test` on both phones: a message on A appears on B. Join `#secret`
     with the same password on both: it works; a third phone that joined `#secret` without the
     password (or with a different one) sees nothing.
   - Swipe one of B's messages right on A and reply: B sees the quote. Double-tap it: B sees ❤️.
     Long-press your own message → **Delete for everyone**: it turns into "Message deleted" on both.
   - In the DM menu: **Wave 👋** (B sees "A waved at you 👋", as a notification if the chat isn't
     open) and **Disappearing messages →
     5 minutes** (B sees the change; new messages vanish on both phones 5 min after arriving).
   - #nearby menu → **Send SOS alert** → hold: B shows a red banner and a loud notification.
   - Settings → **App lock** → set a PIN, leave the app and come back: the PIN pad appears.
8. **Voice calls (two phones with 1.2, side by side)**
   - Open A's chat with B → 📞. Allow the microphone. A shows "Calling…" then "Ringing…"; B rings.
   - Answer on B (in the app, or with **Answer** in the notification when B is on another app).
     Both show a running timer and "Direct Bluetooth link". Talk: each hears the other within ~0.5 s.
   - Try Mute and Speaker; minimize the call and send a message; turn A's screen off and keep talking.
   - Hang up on either phone: both show "Call ended" and the chat gets "📞 Outgoing/Incoming call · m:ss".
   - Call again and don't answer: after ~35 s A shows "No answer" and B gets a missed-call notification.
   - Optional: repeat with a third phone relaying (A and C out of range): the route reads "Through the
     mesh · 2 hops"; the Diagnostics log on B shows the relaying.

Single phone? Turn on **Demo mode** (Settings → Diagnostics) to see the radar, chats, ticks, typing,
replies, reactions, waves and a demo channel without other phones.

## How it's built

- `:core`: pure Kotlin/JVM, no Android APIs. Wire protocol and codec (documented in
  [PROTOCOL.md](PROTOCOL.md)), crypto on BouncyCastle's lightweight API, fragmentation, and `MeshNode`
  (routing, dedupe, TTL, relaying, announces, delivery tracking, retries, pending queue). Everything
  time-based runs on an injected clock and coroutine scope, so tests use virtual time.
- `:app`: `BleTransport` (GATT server + client, one link per connection, all state on one thread),
  `MeshService` (foreground service), Room + DataStore, identity keys wrapped by the Android Keystore,
  and a Jetpack Compose + Material 3 UI with manual dependency injection (`AppContainer`).

## Privacy and security

- Your identity is an Ed25519 signing key and an X25519 key, created on first launch. The private keys
  are encrypted with an AES-256-GCM key kept in the Android Keystore. Backups are disabled.
- Every packet is signed; forged or tampered packets are dropped and not relayed.
- DMs are encrypted per message (ephemeral X25519 → HKDF-SHA256 → ChaCha20-Poly1305).
- **What others can see**: #nearby messages are public. For DMs, relays can see who is talking to whom,
  when, and roughly how long the messages are, but not their content. Nicknames and avatars are
  broadcast in the clear. Channel names are visible to relays; open-channel messages are public;
  password-channel messages are encrypted with a key derived from the name and password (PBKDF2,
  120,000 rounds), so a weak password can be guessed offline by someone who recorded the traffic.
- The app-lock PIN is stored only as a salted PBKDF2 hash; 5 wrong tries lock input for 30 s. It
  protects the screen, not the database files.
- There is no forward secrecy against theft of the recipient's key: someone who records traffic and
  later steals a phone's keys could decrypt DMs sent to it. Panic wipe replaces your keys.
- This code has not been audited.

## Limitations

- Bluetooth LE range is roughly 10–30 m indoors; throughput is low (text and voice calls only; no
  video, photos or files).
- Voice calls need ~27 kbit/s each way on every link they cross. On a busy mesh or across several
  hops they break up. Calls can't wait for someone to come back in range.
- Each phone makes at most 6 outgoing connections; very dense crowds aren't tuned.
- Delivery depends on phones being in the mesh at the time: DMs wait on the sender for up to 24 h, but
  #nearby messages aren't stored and forwarded later. Read receipts aren't queued.
- Packets older than 12 h or more than 1 h in the future are dropped, so a badly wrong phone clock
  breaks messaging.
- Android may stop background services on some phones despite the foreground service (see battery
  note above).
- Not compatible with Bitchat or iOS.
- Demo mode is a UI preview only; it never touches Bluetooth.
