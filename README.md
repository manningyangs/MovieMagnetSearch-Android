# 🎬 MovieMagnetSearch Android

> 豆瓣 Top250 + 多源磁力搜索，一款纯原生 Android Jetpack Compose 应用。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![MinSdk](https://img.shields.io/badge/minSdk-24-important.svg)](https://android-arsenal.com/api?level=24)
[![TargetSdk](https://img.shields.io/badge/targetSdk-34-brightgreen.svg)](https://android-arsenal.com/api?level=34)
[![Version](https://img.shields.io/badge/version-1.0.0-green.svg)](#)

## ✨ 功能特性

### 🏆 豆瓣发现
- **Top 250 榜单** —— 10 页并行抓取，秒加载豆瓣 Top 250 经典影片
- **关键词搜索** —— 实时调用豆瓣 `subject_suggest` API，精准匹配
- **电影详情** —— 完整解析导演、演员、编剧、类型、评分分布、热门短评
- **反爬对抗** —— 内置 SHA-512 PoW 挑战自动求解，豆瓣详情页秒开
- **显式分页器** —— 20 部一页，页码导航不迷路

### 🔍 磁力搜索
- **五大搜索源并发**：
  | 源 | 类型 | 特点 |
  | --- | --- | --- |
  | Pirate Bay (`apibay.org`) | 英文 | JSON API，速度快 |
  | YTS (`yts.mx`) | 英文 | 限 50 条，多域 fallback |
  | Nyaa.si | 英文 | 页面默认 ~75 条 |
  | 1337x | 英文 | 两步抓取：搜索 → 详情页取磁链 |
  | BT之家 | 中文 | Discuz 论坛，两个域自动切换 |

- **中文片名智能翻译分发** 🌐
  ```
  用户输入 "泰坦尼克号"（检测到中文）
    ├─→ 中文源（BT之家）── 立即用中文搜索（~200ms）
    ├─→ 翻译层 ── 豆瓣 subject_suggest 优先 → "Titanic"
    │             └── 维基百科 fallback（5s 超时）
    └─→ 英文源 ── 翻译完成后用英文 "Titanic" 搜索
  ```
- **分辨率筛选** —— 4K / 1080p / 720p / 其他
- **多维排序** —— 做种数 / 大小 / 下载数
- **一键操作** —— 磁力链接复制 / 唤起下载器

### 🎯 跨页联动
- 豆瓣详情页点「搜磁力」→ 自动带片名跳转到磁力搜索 Tab

### ⚡ 网络优化
- **IPv4 强制 DNS** —— BT 站 IPv6 普遍被墙，自动过滤只走 IPv4，避免 15s timeout
- **桌面 Chrome UA** —— BT 站对 Mobile UA 返回更少结果，统一桌面 UA

## 🏗️ 技术栈

| 类别 | 技术 |
| --- | --- |
| UI | Jetpack Compose (Material 3) |
| 语言 | Kotlin 1.9 |
| 网络 | OkHttp 4.12 + Jsoup 1.17 |
| 异步 | Coroutines + Flow |
| 图片加载 | Coil 2.5 |
| 构建 | Gradle KTS + AGP 8.2 |
| 最低版本 | API 24 (Android 7.0) |
| 目标版本 | API 34 (Android 14) |

## 📁 项目结构

```
app/src/main/java/com/magnetsearch/
├── MainActivity.kt                    # 双 Tab 入口
├── data/
│   ├── api/HttpClient.kt              # OkHttp Client 配置（IPv4 Dns + CookieJar）
│   ├── model/                         # 数据类
│   └── repository/
│       ├── DoubanRepository.kt        # 豆瓣 Top250 / 搜索 / 详情（含 PoW）
│       └── MagnetRepository.kt        # 磁力搜索（多源并发 + 智能翻译分发）
└── ui/
    ├── douban/
    │   ├── DoubanScreen.kt            # 豆瓣发现主页面
    │   ├── DoubanViewModel.kt
    │   └── MovieCard.kt
    ├── magnet/
    │   ├── MagnetScreen.kt            # 磁力搜索主页面
    │   └── MagnetViewModel.kt
    └── theme/
```

## 🚀 快速开始

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更新版本
- JDK 17（Gradle Toolchain 会自动下载）
- Android SDK 34

### 构建 Release APK

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本（当前使用 debug keystore 签名，正式发布请替换为自有 keystore）
./gradlew assembleRelease
```

输出产物：`app/build/outputs/apk/release/app-release.apk`

### Release 签名配置

编辑 `app/build.gradle.kts` 中的 `signingConfigs`：

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

MIT License — 详见 [LICENSE](LICENSE) 文件。

## 🤝 贡献

欢迎 Issue / PR！
