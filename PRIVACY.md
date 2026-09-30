# Murmur privacy policy

_Last updated: 2026-09-30_

Murmur is an offline, peer-to-peer chat and voice-call app. It has **no servers, no accounts,
and no internet permission**. There is no operator who can see your data, because there is no
operator.

## What Murmur collects

**Nothing leaves your phone except over Bluetooth, to the people you are talking to.** Murmur has
no analytics, no crash reporting, no advertising, and no tracking of any kind. The Android manifest
does not request the `INTERNET` permission, so the app is technically incapable of sending your data
to us or to anyone over the network.

## What stays on your device

- Your profile (nickname, avatar emoji, colour) and your identity keys.
- Your messages, channels and call history, stored in a local database.
- App settings (theme, PIN hash for the optional app lock, etc.).

You can erase all of it at any time with **Settings → Panic wipe**, or by uninstalling the app.
Android backup is disabled for the app, so this data is not copied off the device by the system.

## What other people can see

Murmur is a radio. When it is on, it broadcasts, over Bluetooth Low Energy to phones in range:

- Your nickname and avatar (in the clear), so people can recognise you.
- Public `#nearby` and open-channel messages (readable by anyone in range).
- For direct messages and password channels: only ciphertext. Relaying phones can see *that* a
  message passed through and roughly how big it was, but not its contents.
- Voice calls are end-to-end encrypted; relaying phones carry the audio without being able to
  decode it.

Channel *names* are visible to phones nearby (that is how people discover and join channels), but
the *contents* of a password channel are not.

## Permissions Murmur asks for, and why

- **Nearby devices / Bluetooth** — to find people and exchange messages. This is the whole app.
- **Location (Android 11 and below only)** — Android historically required it to scan for
  Bluetooth. Murmur never uses your location and declares `neverForLocation` on Android 12+.
- **Microphone** — only for voice calls, and only while a call is active.
- **Notifications** — to tell you about messages and calls.

## Children

Murmur collects no data and has no online services, but it is a general-purpose communication tool.
Supervise younger users as you would with any chat app.

## Contact

Murmur is open-source software. Questions and issues:
https://github.com/markovo1234/Ohh-shitings-bloothuts-shity
