# 🎬 MovieMagnetSearch Android

> Douban Top 250 + Multi-source Magnet Search — a pure native Android Jetpack Compose app.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![MinSdk](https://img.shields.io/badge/minSdk-24-important.svg)](https://android-arsenal.com/api?level=24)
[![TargetSdk](https://img.shields.io/badge/targetSdk-34-brightgreen.svg)](https://android-arsenal.com/api?level=34)
[![Version](https://img.shields.io/badge/version-1.0.0-green.svg)](#)

[中文文档](README.md)

## ✨ Features

### 🏆 Douban Discovery
- **Top 250** — 10 pages fetched in parallel for instant loading
- **Keyword Search** — Real-time search via Douban `subject_suggest` API
- **Movie Details** — Full director, cast, writer, genre, rating distribution, top short reviews
- **Anti-bot Bypass** — Built-in auto-solver for SHA-512 Proof-of-Work challenges
- **Explicit Pagination** — 20 movies per page with page navigation

### 🔍 Magnet Search
- **5 concurrent search sources**:
  | Source | Language | Notes |
  | --- | --- | --- |
  | Pirate Bay (`apibay.org`) | EN | JSON API, fast |
  | YTS (`yts.mx`) | EN | Limit 50, multi-domain fallback |
  | Nyaa.si | EN | ~75 items per page |
  | 1337x | EN | Two-step: search → detail page for magnet |
  | BT Home | ZH | Discuz forum, dual-domain auto-switch |

- **Smart Chinese Title Translation Router** 🌐
  ```
  User enters "泰坦尼克号" (Chinese detected)
    ├─→ Chinese sources (BT Home) → search directly in Chinese (~200ms)
    ├─→ Translation layer → Douban subject_suggest first → "Titanic"
    │                        └─→ Wikipedia fallback (5s timeout)
    └─→ English sources → search with translated English "Titanic"
  ```
- **Quality Filtering** — 4K / 1080p / 720p / Other
- **Multi-dimensional Sorting** — Seeders / Size / Leechers
- **One-tap Actions** — Copy magnet / open downloader

### 🎯 Cross-tab Navigation
- Tap "Search Magnet" from a Douban detail page → auto-navigate to Magnet Search tab with the movie title

### ⚡ Network Optimizations
- **IPv4-Only DNS** — BT trackers' IPv6 addresses are typically unreachable from GFW regions; auto-filter to IPv4 only to avoid 15s timeouts
- **Desktop Chrome UA** — BT sites return fewer results to Mobile UA; unified desktop User-Agent

## 🏗️ Tech Stack

| Category | Technology |
| --- | --- |
| UI | Jetpack Compose (Material 3) |
| Language | Kotlin 1.9 |
| Networking | OkHttp 4.12 + Jsoup 1.17 |
| Async | Coroutines + Flow |
| Image Loading | Coil 2.5 |
| Build | Gradle KTS + AGP 8.2 |
| Min SDK | API 24 (Android 7.0) |
| Target SDK | API 34 (Android 14) |

## 📁 Project Structure

```
app/src/main/java/com/magnetsearch/
├── MainActivity.kt                    # Dual-tab entry point
├── data/
│   ├── api/HttpClient.kt              # OkHttp Client config (IPv4 DNS + CookieJar)
│   ├── model/                         # Data classes
│   └── repository/
│       ├── DoubanRepository.kt        # Douban Top250 / search / detail (incl. PoW)
│       └── MagnetRepository.kt        # Magnet search (multi-source + smart translation router)
└── ui/
    ├── douban/
    │   ├── DoubanScreen.kt            # Douban Discovery main screen
    │   ├── DoubanViewModel.kt
    │   └── MovieCard.kt
    ├── magnet/
    │   ├── MagnetScreen.kt            # Magnet Search main screen
    │   └── MagnetViewModel.kt
    └── theme/
```

## 🚀 Quick Start

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 (auto-downloaded via Gradle Toolchain)
- Android SDK 34

### Build Release APK

```bash
# Debug build
./gradlew assembleDebug

# Release build (currently signed with debug keystore; replace with your own for production)
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Configure Release Signing

Edit `signingConfigs` in `app/build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("/path/to/your/keystore.jks")
        storePassword = "yourStorePassword"
        keyAlias = "yourKeyAlias"
        keyPassword = "yourKeyPassword"
    }
}
```

## 📜 License

MIT License — see [LICENSE](LICENSE) for details.

## 🤝 Contributing

Issues and PRs welcome!
