# Murmur

### Chat and call people nearby. No internet. No accounts. No servers.

[![Build](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)
![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)
![Platform: Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![No internet permission](https://img.shields.io/badge/network-none-critical)

Murmur is a free, open-source Android app where phones talk to each other over **Bluetooth Low
Energy** and relay messages for each other in a mesh, so a message can reach someone who is out of
your own range as long as other Murmur phones sit in between. Direct messages and voice calls are
end-to-end encrypted, and the app **has no internet permission** — it is physically incapable of
sending your data over the network.

**Get it:** grab the signed `Murmur.apk` from the [Releases](../../releases) page and sideload it, or
[build it yourself](#build-it-yourself). &nbsp;·&nbsp; [Apache-2.0](LICENSE) &nbsp;·&nbsp;
[Privacy policy](PRIVACY.md) &nbsp;·&nbsp; [Wire protocol](PROTOCOL.md) &nbsp;·&nbsp;
[Contributing](CONTRIBUTING.md)

## Contents

- [Features](#features)
- [Install the APK on your phone](#install-the-apk-on-your-phone)
- [Build it yourself](#build-it-yourself)
- [Test plan with 2–3 phones](#test-plan-with-23-phones)
- [How it's built](#how-its-built)
- [Privacy and security](#privacy-and-security)
- [Limitations](#limitations)
- [Publishing a release](#publishing-a-release)
- [Licence](#licence)

## Features

- **Radar**: an animated night-sky radar shows people nearby (by signal strength) and people reachable
  through the mesh (with a hop count).
- **#nearby**: a public room for everyone in range. Messages delete themselves after 24 hours.
- **Channels**: `#topic` rooms anyone can join by name, optionally protected by a **password**
  (encrypted). **Invite** people with one tap; channels active nearby show up in the Join dialog.
- **Direct messages**: end-to-end encrypted. Relays pass them on but can't read them. Ticks show
  Sent → Delivered → Read; messages to someone offline wait on your phone (up to 24 h).
- **Voice calls**: end-to-end encrypted, over Bluetooth only, with mute, speaker and a call timer.
  Incoming calls wake the phone and ring over the lock screen, like a normal phone call.
- **Replies, reactions, @mentions, formatting**, delete for everyone, disappearing messages, waves,
  search and drafts.
- **SOS alert** in #nearby: everyone in range gets a loud, red alert.
- **Safety numbers** to check nobody is impersonating a friend; **favorites**, private **nicknames**
  and a **People** screen.
- **Privacy**: block anyone, PIN **app lock**, hide message text in notifications, and a **panic
  wipe** that erases everything including your identity keys.
- **Diagnostics** screen and a **Demo mode** (five pretend people) to try the app on one phone.
- Light and dark themes, touch animations, TalkBack labels everywhere, and support for the system
  "Remove animations" setting.

What changed in each version is on the [Releases](../../releases) page.

## Install the APK on your phone

1. On your phone, open the **[Releases](../../releases)** page of this repository.
2. Under the newest release, tap **`Murmur-vX.Y.Z.apk`** to download it.
3. Open it. If Android asks, allow your browser or Files app to **install unknown apps**, then tap
   **Install**.
4. Open Murmur and follow the three onboarding steps.

Notes:

- `Murmur-vX.Y.Z-debug.apk` is the debug build; it works the same but animates noticeably slower.
- Every version is signed with the same key (committed in `keystore/`), so a newer version installs
  over an older one and keeps your chats. Older versions stay available on the Releases page.
- Some phones (Xiaomi, Huawei, Samsung, OnePlus…) aggressively stop background apps. If Murmur stops
  relaying when the screen is off, set its battery usage to **Unrestricted**.
- Every push also builds the app on GitHub Actions: signed-in users can download test builds from
  **Actions** → a green **Build** run → **Artifacts** → **Murmur-apk**.

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
   - Lock B and turn its screen off, then call it: B's screen wakes up showing Answer / Decline over the
     lock screen. Answer without unlocking; the minimize arrow asks to unlock, then opens Murmur with the call
     as a pill.
   - Optional: repeat with a third phone relaying (A and C out of range): the route reads "Through the
     mesh · 2 hops"; the Diagnostics log on B shows the relaying.
9. **Private channels (two phones with 1.3)**
   - On A: Chats → **Join channel** → `#secret`, Password on, `pass1234` → Join. Post a message.
   - On B: Chats → **Join channel**: "🔒 #secret" appears under **Active nearby** (after A's message).
     Joining without the password shows a red hint; B's #secret then explains that A uses a password.
   - On A: #secret → ⋮ → **Invite people…** → Invite B. B gets "A invited you to #secret" in their
     chat with A → **Join #secret** → B sees A's next messages.

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

## Publishing a release

Releases are built by `.github/workflows/release.yml`. Each one gets `Murmur-vX.Y.Z.apk` and
`Murmur-vX.Y.Z-debug.apk` attached, with `.github/release-notes/vX.Y.Z.md` as its notes. Every
version and the commit it's built from is listed in `.github/release-notes/versions.txt`.

**Publish from the browser (no terminal needed):** Actions → **Release** → **Run workflow** → enter a
version (e.g. `v1.3.1`) or **`all`** → Run. Missing tags are created on the listed commits. Releases
that already exist get their APKs and notes refreshed.

**To add a new version:**

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Write `.github/release-notes/vX.Y.Z.md` and `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Merge to `main`, then either push a tag (`git tag vX.Y.Z && git push origin vX.Y.Z`) or add the
   version and its commit to `versions.txt` and run the workflow.

The same source is suitable for **F-Droid**: it has no proprietary dependencies, no Google Play
services and no `INTERNET` permission. Listing text lives in `fastlane/metadata/`. F-Droid builds and
signs the app from source on its own infrastructure, so no signing key is shared.

> **Signing note:** release builds are currently signed with the debug keystore committed in
> `keystore/` so that anyone can reproduce the exact APK. That is fine for sideloading and for
> F-Droid (which re-signs), but **not** for Google Play. For Play, generate a private upload key (or
> use Play App Signing), keep it out of the repository, and point the `release` `signingConfig` in
> `app/build.gradle.kts` at it via CI secrets.

## Licence

Murmur is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for third-party
attributions. Contributions are welcome under the same licence — see [CONTRIBUTING.md](CONTRIBUTING.md).
