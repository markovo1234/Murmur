# Contributing to Murmur

Thanks for helping out! Murmur is a small, dependency-light Android app with a pure-Kotlin core.

## Ground rules

- **No new runtime dependencies** without discussion, and **never** add media/file transfer beyond
  what exists, accounts, servers, analytics, Google Play services, Firebase or the `INTERNET`
  permission. Murmur's whole point is that it works offline and phones are equal peers.
- Keep `:core` free of Android APIs — it is plain Kotlin/JVM and is where the protocol, crypto and
  mesh logic live (see [PROTOCOL.md](PROTOCOL.md)).
- Match the surrounding code style.

## Before opening a pull request

Run the same gates CI runs, and make sure they pass:

```sh
./gradlew :core:test        # protocol, crypto, mesh and call unit tests
./gradlew :app:lintDebug    # Android lint — 0 errors
./gradlew :app:dist         # builds dist/Murmur.apk and dist/Murmur-debug.apk
```

New protocol behaviour needs tests in `:core` and, where it changes the wire format, a note in
[PROTOCOL.md](PROTOCOL.md). Anything that changes what leaves the phone belongs in
[PRIVACY.md](PRIVACY.md) too.

## Compatibility

Installed phones run older versions. Keep existing packet formats unchanged, gate new features behind
new packet/DM types that old versions ignore, and bump `versionCode`/`versionName` in
`app/build.gradle.kts`.

## Licence

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
