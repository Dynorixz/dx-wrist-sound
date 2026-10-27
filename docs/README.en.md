# DX • Wrist Sound

[Русский README](../README.md)

**Your watch controls your phone's ringtone profile.** Wear the watch to put the phone on vibrate; take it off to restore an audible ringtone. Profiles and confirmation delays are configurable.

DX reads the watch's off-body sensor rather than treating a Bluetooth connection as proof that the watch is being worn. Version 1.2.4 preserves the watch's system sound settings.

When a confirmed phone disconnection occurs while the watch is worn, it can display a phone-left-behind alert with either a chime or Russian offline speech. An overlay permission is required to show the separate window above the watch face.

## Downloads

Install both applications from the same signing family:

- [Phone 1.2.4 · Release](https://github.com/Dynorixz/dx-wrist-sound/releases/download/v1.2.4/DX-Wrist-Sound-Phone-1.2.4-release.apk)
- [Wear 1.2.4 · Release](https://github.com/Dynorixz/dx-wrist-sound/releases/download/v1.2.4/DX-Wrist-Sound-Wear-1.2.4-release.apk)
- [All release files and legacy Compatible builds](https://github.com/Dynorixz/dx-wrist-sound/releases/tag/v1.2.4)

Android 11+ and Wear OS 3+ with Google Play services and a supported off-body sensor are required. Compatible builds preserve the author's earlier local signing certificate; they are not interchangeable with Release builds.

## Development

Three modules: `mobile`, `wear`, and `shared`. Kotlin, Jetpack Compose, Wear Compose, Coroutines / Flow, DataStore, and Play services Data Layer. JDK 17; Gradle 9.6.0; AGP 9.4.1; compile SDK 37.2.

See [build instructions](building.md), [architecture](../ARCHITECTURE.md), [permissions](permissions.md), and [privacy](../PRIVACY.md). The documented 1.2.4 verification passed 52 unit tests; hardware behavior and background recovery depend on firmware and permissions. Data Layer selects its own underlying transport.

By [Dynorixz](https://github.com/Dynorixz) · [Telegram](https://t.me/dynorixz) · [Support](https://pay.cloudtips.ru/p/5d56061d).
