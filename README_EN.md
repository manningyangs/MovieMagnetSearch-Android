# 🎬 MovieMagnetSearch Android

> Magnet Search + Douban Discovery + Bilibili Native Playback — a pure native Android Jetpack Compose app.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![MinSdk](https://img.shields.io/badge/minSdk-24-important.svg)](https://android-arsenal.com/api?level=24)
[![TargetSdk](https://img.shields.io/badge/targetSdk-34-brightgreen.svg)](https://android-arsenal.com/api?level=34)
[![Version](https://img.shields.io/badge/version-1.3.0-green.svg)](#)
[![Stars](https://img.shields.io/github/stars/manningyangs/MovieMagnetSearch-Android?style=social)](https://github.com/manningyangs/MovieMagnetSearch-Android)

[简体中文](README.md) · English

---

## 📦 Download

👉 **Latest Release APK**: [V1.3.0 · Mid-Autumn Native Playback Edition](https://github.com/manningyangs/MovieMagnetSearch-Android/releases)

> ⚠️ App filing with MIIT is in progress. On phones from Huawei / Xiaomi / OPPO etc., you may need to manually allow "Unknown Sources" to install.

---

## ✨ Features

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

### 🏆 Douban Discovery
- **Top 250** — 10 pages fetched in parallel for instant loading
- **Keyword Search** — Real-time search via Douban `subject_suggest` API
- **Movie Details** — Full director, cast, writer, genre, rating distribution, top short reviews
- **Anti-bot Bypass** — Built-in auto-solver for SHA-512 Proof-of-Work challenges
- **Explicit Pagination** — 20 movies per page with page navigation

### 📺 Bilibili Channel (New in V1.3.0)
- **ExoPlayer Native DASH Streaming** 🆕 — Replaces WebView entirely; gesture controls,倍速, native fullscreen
- **Quality Switching** — 720P without login; 1080P / high-bitrate / member-only with SESSDATA cookie
- **Multi-part Switching** — One-tap to switch between P1, P2...
- **Local Sharded Cache** — 512MB LRU auto-cache; watch offline later
- **Immersive Fullscreen** — Landscape + hide status bar / navigation bar + auto-hide bottom tabs
- **Bilibili QR Login** — Embedded WebView; CookieManager persists SESSDATA automatically
- **Anti-hotlinking** — All HTTP requests include `Referer: https://www.bilibili.com/` so Bilibili CDN accepts them

### 🎯 Cross-tab Navigation
- Tap "Search Magnet" from a Douban detail page → auto-navigate to Magnet Search tab with the movie title
- Each tab has its own brand color: 🔍 Blue (Magnet) · 🎬 Green (Douban) · ▶ Bilibili Pink

### ⚡ Network Optimizations
- **IPv4-Only DNS** — BT trackers' IPv6 addresses are typically unreachable from GFW regions; auto-filter to IPv4 only to avoid 15s timeouts
- **Desktop Chrome UA** — BT sites return fewer results to Mobile UA; unified desktop User-Agent
- **Invisible WebView Scraping** — Bilibili category videos fetched via 1×1px transparent WebView executing fetch API, bypasses anti-scrape

---

## 🏗️ Tech Stack

| Category | Technology |
| --- | --- |
| UI | Jetpack Compose (Material 3) · Floating transparent bottom tabs |
| Language | Kotlin 1.9 |
| Networking | OkHttp 4.12 + Jsoup 1.17 |
| Video Playback | AndroidX Media3 (ExoPlayer 1.3.1) · DASH · MergingMediaSource |
| Video Cache | SimpleCache + CacheDataSource.Factory (512MB LRU) |
| Async | Coroutines + Flow |
| Image Loading | Coil 2.5 |
| Build | Gradle KTS + AGP 8.2 |
| Min SDK | API 24 (Android 7.0) |
| Target SDK | API 34 (Android 14) |

---

## 📁 Project Structure

```
app/src/main/java/com/magnetsearch/
├── MainActivity.kt                    # 3-tab floating transparent nav entry
├── data/
│   ├── api/
│   │   ├── HttpClient.kt              # OkHttp Client (IPv4 DNS + CookieJar)
│   │   └── BiliPlayUrlApi.kt          # Bilibili playurl API (Long cid + legacy playurl)
│   ├── model/
│   │   ├── AppState.kt                # Global state (hides bottom tabs in fullscreen)
│   │   ├── BiliModels.kt              # Bilibili data models
│   │   ├── DoubanMovie.kt             # Douban models
│   │   └── MagnetResult.kt            # Magnet models
│   └── repository/
│       ├── DoubanRepository.kt         # Douban Top250 / search / detail (incl. PoW)
│       └── MagnetRepository.kt         # Magnet search (multi-source + smart translation router)
└── ui/
    ├── douban/
    │   ├── DoubanScreen.kt            # Douban Discovery main screen
    │   ├── DoubanViewModel.kt
    │   └── MovieCard.kt
    ├── magnet/
    │   ├── MagnetScreen.kt            # Magnet Search main screen
    │   └── MagnetViewModel.kt
    ├── bili/
    │   ├── BiliScreen.kt               # Bilibili category + video grid
    │   ├── BiliViewModel.kt            # Bilibili scraping (invisible WebView)
    │   ├── BiliPlayerScreen.kt         # ExoPlayer native player + fullscreen
    │   └── BiliLoginScreen.kt          # Bilibili WebView login page
    └── theme/
```

---

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

---

## 📝 Changelog

### V1.3.0 (2026-09-24) — Native Playback + Transparent Tabs
- 🎬 **Bilibili native player**: ExoPlayer replaces WebView; DASH + MergingMediaSource for A/V merge
- 🎛️ **Quality switching**: 720P without login, 1080P+ with SESSDATA
- 📺 **Immersive fullscreen**: Landscape + hide system bars + auto-hide bottom tabs
- 🔐 **Bilibili login**: WebView QR login + CookieManager auto-sync
- 💾 **Local sharded cache**: 512MB SimpleCache LRU
- 🎨 **Floating transparent bottom tabs**: Tri-color brand pills (blue/green/Bilibili pink) — no layout push
- 🐛 **Fix Long cid truncation**: New Bilibili cids exceed Int range; switched to `optLong()`
- 🐛 **Remove wbi/v2 dependency**: Directly use legacy `/x/player/playurl` (no WBI signature needed, more stable)

### V1.2.0 (2026-09-24)
- 📺 Basic Bilibili channel: category dropdown, video card grid, WebView playback

### V1.1.1 / V1.0.x
- Douban Top250 / search / detail
- Multi-source magnet search + smart translation router
- IPv4 DNS + Chrome UA anti-bot

---

## 📜 License

MIT License — see [LICENSE](LICENSE) for details.

## 🤝 Contributing

Issues and PRs welcome!

## 🌕 Thanks

Standing on the shoulders of open-source giants:
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI
- [AndroidX Media3](https://developer.android.com/media/media3) — Native video playback
- [ExoPlayer](https://exoplayer.dev) — Video engine
- [OkHttp](https://square.github.io/okhttp/) — Networking
- [Jsoup](https://jsoup.org) — HTML parsing
- [Coil](https://coil-kt.github.io/coil/) — Image loading

---

🍂 **Happy Mid-Autumn Festival** — may the moon be full and the stream be smooth 🎑
