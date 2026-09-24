# 🎬 MovieMagnetSearch Android

> 磁力搜影 —— 豆瓣发现 + 多源磁力搜索 + B站原生播放，一款纯原生 Android Jetpack Compose 应用。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![MinSdk](https://img.shields.io/badge/minSdk-24-important.svg)](https://android-arsenal.com/api?level=24)
[![TargetSdk](https://img.shields.io/badge/targetSdk-34-brightgreen.svg)](https://android-arsenal.com/api?level=34)
[![Version](https://img.shields.io/badge/version-1.3.0-green.svg)](#)
[![Stars](https://img.shields.io/github/stars/manningyangs/MovieMagnetSearch-Android?style=social)](https://github.com/manningyangs/MovieMagnetSearch-Android)

[English](README_EN.md) · 简体中文

---

## 📦 下载安装

👉 **最新 Release APK**: [V1.3.0 · 中秋原生播放版](https://github.com/manningyangs/MovieMagnetSearch-Android/releases)

> ⚠️ 应用备案进行中，华为/小米/OPPO 等国产手机安装可能需要手动允许「未知来源」。

---

## ✨ 功能特性

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

### 🏆 豆瓣发现
- **Top 250 榜单** —— 10 页并行抓取，秒加载豆瓣 Top 250 经典影片
- **关键词搜索** —— 实时调用豆瓣 `subject_suggest` API，精准匹配
- **电影详情** —— 完整解析导演、演员、编剧、类型、评分分布、热门短评
- **反爬对抗** —— 内置 SHA-512 PoW 挑战自动求解，豆瓣详情页秒开
- **显式分页器** —— 20 部一页，页码导航不迷路

### 📺 B站畅游（V1.3.0 新增）
- **ExoPlayer 原生 DASH 流播放** 🆕 —— 彻底替换 WebView，支持倍速/手势/全屏原生体验
- **清晰度切换** —— 720P 免登录；登录 B站 后支持 1080P / 高码率 / 大会员清晰度
- **分 P 切换** —— 多 P 视频一键切换
- **本地分片缓存** —— 512MB LRU 自动缓存，断网也能续播
- **全屏沉浸式** —— 横屏 + 隐藏状态栏/导航栏 + 自动隐藏底部 Tab
- **B站扫码登录** —— 内嵌 WebView，CookieManager 自动持久化 SESSDATA
- **反防盗链** —— HTTP 请求自动带 `Referer: https://www.bilibili.com/`，B站 CDN 正常返回

### 🎯 跨页联动
- 豆瓣详情页点「搜磁力」→ 自动带片名跳转到磁力搜索 Tab
- 三个 Tab 各有品牌色：🔍 蓝(磁力) · 🎬 绿(豆瓣) · ▶ B站粉

### ⚡ 网络优化
- **IPv4 强制 DNS** —— BT 站 IPv6 普遍被墙，自动过滤只走 IPv4，避免 15s timeout
- **桌面 Chrome UA** —— BT 站对 Mobile UA 返回更少结果，统一桌面 UA
- **invisible WebView 执行抓取** —— B站分类视频列表通过 1x1px 透明 WebView 执行 fetch，绕过风控

---

## 🏗️ 技术栈

| 类别 | 技术 |
| --- | --- |
| UI | Jetpack Compose (Material 3) · 悬浮透明底部 Tab |
| 语言 | Kotlin 1.9 |
| 网络 | OkHttp 4.12 + Jsoup 1.17 |
| 视频播放 | AndroidX Media3 (ExoPlayer 1.3.1) · DASH · MergingMediaSource |
| 视频缓存 | SimpleCache + CacheDataSource.Factory (512MB LRU) |
| 异步 | Coroutines + Flow |
| 图片加载 | Coil 2.5 |
| 构建 | Gradle KTS + AGP 8.2 |
| 最低版本 | API 24 (Android 7.0) |
| 目标版本 | API 34 (Android 14) |

---

## 📁 项目结构

```
app/src/main/java/com/magnetsearch/
├── MainActivity.kt                    # 三 Tab 悬浮透明导航入口
├── data/
│   ├── api/
│   │   ├── HttpClient.kt              # OkHttp Client（IPv4 Dns + CookieJar）
│   │   └── BiliPlayUrlApi.kt          # B站 playurl API（Long cid + playurl legacy）
│   ├── model/
│   │   ├── AppState.kt                # 全局状态（全屏时隐藏底部 Tab）
│   │   ├── BiliModels.kt              # B站数据模型
│   │   ├── DoubanMovie.kt             # 豆瓣模型
│   │   └── MagnetResult.kt            # 磁力模型
│   └── repository/
│       ├── DoubanRepository.kt         # 豆瓣 Top250 / 搜索 / 详情（含 PoW）
│       └── MagnetRepository.kt         # 磁力搜索（多源并发 + 智能翻译分发）
└── ui/
    ├── douban/
    │   ├── DoubanScreen.kt            # 豆瓣发现主页面
    │   ├── DoubanViewModel.kt
    │   └── MovieCard.kt
    ├── magnet/
    │   ├── MagnetScreen.kt            # 磁力搜索主页面
    │   └── MagnetViewModel.kt
    ├── bili/
    │   ├── BiliScreen.kt               # B站分类 + 视频网格
    │   ├── BiliViewModel.kt            # B站数据抓取（invisible WebView）
    │   ├── BiliPlayerScreen.kt         # ExoPlayer 原生播放器 + 全屏
    │   └── BiliLoginScreen.kt          # B站 WebView 登录页
    └── theme/
```

---

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

---

## 📝 版本更新日志

### V1.3.0 (2026-09-24) — 原生播放 + 透明 Tab
- 🎬 **B站原生播放器**：ExoPlayer 替换 WebView，DASH 流 + MergingMediaSource 合并音视频
- 🎛️ **清晰度切换**：720P 免登录，1080P+ 需 SESSDATA
- 📺 **全屏沉浸式**：横屏 + 隐藏状态栏/导航栏 + 底部 Tab 自动隐藏
- 🔐 **B站登录**：WebView 扫码登录 + CookieManager 自动同步
- 💾 **本地分片缓存**：512MB SimpleCache LRU
- 🎨 **悬浮透明底部 Tab**：三色品牌胶囊（蓝/绿/B站粉），不再挤占内容
- 🐛 **修复新版 B站 Long cid 被截断**：超 Int 上限的 cid 改用 optLong
- 🐛 **移除 wbi/v2 接口依赖**：直接用老版 playurl，无需签名更稳定

### V1.2.0 (2026-09-24)
- 📺 B站畅游基础功能：分类下拉、视频卡片网格、WebView 播放

### V1.1.1 / V1.0.x
- 豆瓣 Top250 / 搜索 / 详情
- 多源磁力搜索 + 智能翻译分发
- IPv4 DNS + Chrome UA 反反爬

---

## 📜 License

MIT License — 详见 [LICENSE](LICENSE) 文件。

## 🤝 贡献

欢迎 Issue / PR！

## 🌕 鸣谢

所有站在肩膀上的开源项目：
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI 框架
- [AndroidX Media3](https://developer.android.com/media/media3) — 原生视频播放
- [ExoPlayer](https://exoplayer.dev) — 视频引擎
- [OkHttp](https://square.github.io/okhttp/) — 网络
- [Jsoup](https://jsoup.org) — HTML 解析
- [Coil](https://coil-kt.github.io/coil/) — 图片加载

---

🍂 **祝中秋快乐，月圆人圆** 🌕
