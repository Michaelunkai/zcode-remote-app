# ZCode — your ZCode remote session, as a native app

One tap from your launcher to your ZCode remote-control session, fullscreen — no browser, no address bar, no lost tabs.

[![Release](https://img.shields.io/badge/release-v1.0.0-blue)](https://github.com/Michaelunkai/zcode-remote-app/releases)
[![Min Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green)](#requirements)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](#license)
![Package](https://img.shields.io/badge/package-com.michaelunkai.zcoderemote-9cf)

The ZCode desktop app generates a personal URL for remote control — something shaped like
`https://zcode.z.ai/remote/v4?sid=...&hash=...&t=...&mid=...&name=...&app_version=...`.
This app wraps that URL in a fullscreen, dark-themed WebView and behaves like a real app: launcher icon (a terminal `>_` glyph), no browser UI, and a session that stays alive.

## 📱 What it does

Open **ZCode** from your launcher and your remote session loads fullscreen — no address bar, no browser tabs, no accidental tab swipes. The screen stays awake while the app is open, so you can supervise a run on your PC from across the room.

| Live session | Settings |
|---|---|
| ![Live session — the app fullscreen, task list pixelated for privacy](docs/screenshot-app.png) | ![Change server URL screen](docs/screenshot-settings.png) |
| *Live session — the app fullscreen, task list pixelated for privacy* | *Change server URL screen* |

## 🤔 Why

The remote URL is awkward in a browser:

- you have to retype it or dig it out of your desktop app every time,
- it drowns among your other tabs,
- one accidental tab closure drops the session.

The app pins it. One tap away, always the same URL, and the phone screen stays awake during remote supervision.

## ✨ Features

- Fullscreen WebView session — zero browser UI
- Pull-to-refresh
- In-page progress bar
- Back button walks web history, then **minimizes** (never kills the session)
- Keep-screen-on while the app is open
- Error screen with **Retry** / **Change URL** buttons when the session cannot load
- File upload through the system file picker
- Downloads routed through the system DownloadManager, with a "Download started" toast
- Share a URL from any app (Android share sheet) to load it in ZCode
- Launcher app shortcut: **Change server URL**
- Survives rotation without reloading (configChanges handled)
- Session URL stored only in app-private storage

## 📋 Requirements

| Requirement | Value |
|---|---|
| Android | 8.0+ (API 26+) |
| Permissions | `android.permission.INTERNET` only |
| Analytics / tracking | None |
| Network calls | Only loading your session URL |

## 📥 Install

1. Download `zcode-remote-v1.0.0.apk` (~2.5 MB) from the [latest release](https://github.com/Michaelunkai/zcode-remote-app/releases/latest).
2. Android will ask you to allow installs from your browser/file manager (**Settings > Apps > Special access > Install unknown apps**). This APK is self-signed, sideloaded, and permission-minimal.
3. Open **ZCode**.

> The release APK already has the owner's session URL embedded. If none is embedded (a build from source without `remote.properties`), the app opens the URL settings screen on first launch.

## 🚀 Usage

- Open **ZCode** from the launcher — the session loads fullscreen.
- Refresh: pull down.
- Share a URL to **ZCode** from the Android share sheet to load it.

## 🔁 Changing the session URL

If your remote session expired (the desktop app revokes/regenerates the URL):

1. Long-press the **ZCode** launcher icon and tap **Change server URL**.
2. Paste the new URL from the ZCode desktop app.
3. Tap **Save & Open**.

## 🛠 Build from source

Requirements:

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK Platform | 34 |
| Build-Tools | 34.0.0 |
| Gradle | 8.9 (wrapper included, AGP 8.5.2) |

Commands (Windows; on Linux/macOS use `./gradlew`):

```bat
gradlew.bat :app:assembleDebug          :: debug APK
gradlew.bat :app:testDebugUnitTest      :: unit tests
gradlew.bat :app:assembleRelease        :: signed release APK
```

The signed release APK lands at `app/build/outputs/apk/release/app-release.apk`.

## ⚙️ Configuration (source builds)

**Embed a default session URL** — copy `remote.properties.example` to `remote.properties` and set:

```properties
defaultUrl=<your session URL>
```

It is embedded at build time and `remote.properties` is gitignored. Copy nothing and the app starts with an empty URL, opening the settings screen.

**Signing** — copy `keystore.properties` pointing at your keystore (gitignored):

```properties
storeFile=<path to your keystore>
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

Without it, `assembleRelease` produces an unsigned APK.

## 📂 Project layout

```
app/src/main/java/com/michaelunkai/zcoderemote/
    MainActivity.java
    SettingsActivity.java
    UrlStore.java
app/src/main/res/…                          # resources (launcher icon, layouts, themes)
app/src/test/java/…/UrlStoreTest.java       # unit tests
remote.properties.example                   # template for the embedded default URL
gradlew / gradlew.bat                       # Gradle wrapper included
```

## 🔧 Troubleshooting

1. **"Connection problem" error screen** — the session URL expired or the desktop isn't reachable. Generate a fresh remote URL in the ZCode desktop app and use **Change server URL**, or pull down to retry.
2. **Blank dark screen behind the lock screen** — normal. The session resumes when you unlock.
3. **WebView renderer crash on emulators** — an emulator-only artifact with software rendering; on real devices hardware rendering is used.
4. **Downloads not starting** — check the system Download manager is enabled.

## 🔒 Privacy

- Your session URL is stored only in app-private SharedPreferences.
- The app makes no connections other than loading your session URL and collects nothing.

Treat your session URL like a password — it grants access to this machine's ZCode. Never commit it (`remote.properties` is gitignored for exactly this reason).

## 📄 License

[MIT](LICENSE) — © Michaelunkai. See the [LICENSE](LICENSE) file.
