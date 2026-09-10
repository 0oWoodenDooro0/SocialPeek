# SocialPeek

<p align="center">
  <b>純 Kotlin (JVM) 社群平臺貼文解析與多媒體萃取函式庫</b>
</p>

---

## 🌟 特色與設計原則

1. **零外部機器人框架依賴**：完全不依賴任何 Discord / Telegram / Kord / JDA 框架，純粹專注於多平臺資料解析。
2. **極致簡潔的呼叫介面 (Deep Module)**：只需 `SocialPeek.peek(url)` 即可自動匹配平臺並提取結構化資料。
3. **策略模式 (Strategy Pattern)**：各平臺各自實作 `PlatformResolver`，易擴展、高維護性。
4. **協程友善 (Kotlin Coroutines)**：原生 `suspend fun` 支援與非阻塞 I/O。
5. **最新依賴與技術棧**：Kotlin 2.x、Ktor 3.5.x、kotlinx.coroutines 1.10.x、kotlinx.serialization、Jsoup 1.21.x。
6. **JitPack 開箱即用**：內建 `maven-publish` 配置。

---

## 📦 安裝方式 (JitPack)

在專案的 `settings.gradle.kts` 或根目錄 `build.gradle.kts` 中添加 JitPack 倉庫：

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```

在模組的 `build.gradle.kts` 中加入依賴：

```kotlin
dependencies {
    implementation("com.github.0oWoodenDooro0:SocialPeek:main-SNAPSHOT")
}
```

---

## 🚀 快速上手

### 1. 簡易解析 (開箱即用)

```kotlin
import dev.socialpeek.SocialPeek

suspend fun main() {
    // 解析 X (Twitter) 貼文
    val tweet = SocialPeek.peek("https://x.com/jack/status/20")
    println("Author: ${tweet.author.displayName} (@${tweet.author.username})")
    println("Content: ${tweet.content}")
    println("Likes: ${tweet.metrics?.likes}")

    // 解析 B站 影片或動態
    val bili = SocialPeek.peek("https://www.bilibili.com/video/BV1xx411c7mD")
    println("Title: ${bili.title}")
    println("Cover: ${bili.media.firstOrNull()?.previewUrl}")

    // 安全解析 (不匹配或失敗回傳 null)
    val post = SocialPeek.peekOrNull("https://unknown.com/abc")
}
```

### 2. 自訂 Client

```kotlin
import dev.socialpeek.SocialPeek

val client = SocialPeek.builder()
    .includeDefaultResolvers(true)
    // .addResolver(MyCustomResolver())
    .build()

val post = client.peek("https://www.reddit.com/r/Kotlin/comments/1cdefgh/")
```

---

## 📱 目前已支援的平臺 (全部通過 TDD 單元測試)

| 平臺 | 支援網址格式 | 提取內容 |
| :--- | :--- | :--- |
| **X (Twitter)** | `x.com/*/status/*`, `twitter.com/*/status/*` | 內文、作者認證、多圖、最高畫質 MP4 影片、讚數、回覆數 |
| **Reddit** | `reddit.com/r/*/comments/*`, `redd.it/*` | 標題、內文、作者、Subreddit、多圖 Gallery、原生影片、Upvotes、留言數 |
| **YouTube** | `youtube.com/watch?v=*`, `youtu.be/*`, `shorts/*` | 標題、作者名稱、作者頻道 handle、高畫質縮圖、影片連結 |
| **Bilibili** | `bilibili.com/video/BV*`, `opus/*`, `t.bilibili.com/*`, `b23.tv/*` | 標題、作者、多圖動態、封面、影片時長、播放量、點讚、彈幕、轉發、收藏數 |
| **Instagram** | `instagram.com/p/*`, `reel/*`, `tv/*` | 作者、大頭貼、貼文內文、照片直鏈、Reels 影片直鏈 |
| **Threads** | `threads.net/@*/post/*`, `threads.net/t/*` | 作者姓名、帳號、內文、高畫質圖片直鏈、影片直鏈 |

---

## 🧱 核心資料結構

### `PeekPost`
- `platform`: `Platform` (BILIBILI, X, INSTAGRAM, THREADS, YOUTUBE, REDDIT...)
- `id`: 平臺原生 ID
- `originalUrl`: 標準化後的貼文網址
- `author`: `Author` (id, username, displayName, avatarUrl, profileUrl, isVerified)
- `content`: 貼文純文字內容
- `title`: 標題（影片或特定平臺文章具有）
- `media`: `List<Media>` (`Media.Image` 或 `Media.Video`，包含寬高、時長與直鏈)
- `metrics`: `Metrics` (likes, reposts, comments, views, bookmarks)
- `createdAtEpochSeconds`: 建立時間戳記

---

## 🛠️ 開發與測試

本專案全面採用 **TDD (Test-Driven Development)** 開發：

```bash
# 執行所有測試
./gradlew test

# 發佈到本地 Maven 庫驗證
./gradlew publishToMavenLocal
```
