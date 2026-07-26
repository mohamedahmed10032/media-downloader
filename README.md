# Media Downloader

An Android media **browser + download manager** built with a clean, extensible architecture.
It lets you browse the web, paste links, and download **media you have the right to download** —
direct video/audio/image files, openly‑licensed content, HLS (`.m3u8`) streams, and media
embedded in ordinary web pages.

> **Built by Mohamed El‑Meligy.**

---

## ⚖️ Scope & responsible‑use statement (read this first)

This project is a **general‑purpose download manager and browser**. It deliberately **does not**:

- bypass DRM, encryption, or platform access controls;
- log into or scrape login‑gated content from platforms whose Terms of Service forbid downloading
  (e.g. YouTube, Instagram, TikTok, Facebook);
- remove or forge watermarks.

The extractor layer resolves **direct media links, openly‑licensed/authorized sources, HLS streams,
and media that pages expose in standard HTML**. You are responsible for only downloading content you
own or are permitted to download, and for complying with the terms of service of any site you visit.
This statement is also shown in‑app under **Settings → Legal & disclaimer** and **About the Developer**.

---

## ✨ Features

- **Built‑in browser** — embedded `WebView` (not a redirect to the system browser) with address bar,
  Google search, back/forward/refresh/home, **bookmarks**, **multi‑tab** support, cookie/session
  handling, and automatic **media detection** that surfaces a floating **Download** button.
- **Extensible extractor engine** — a plugin architecture (one extractor per source type) with a
  fallback chain: direct file, image, HLS manifest, and generic HTML page scraping.
- **Quality/format selection** — every detected stream is listed (resolution / container / size
  estimate) before download.
- **Robust download engine** — multi‑threaded, **segmented, resumable** downloads (HTTP range
  requests) with a sidecar progress file; **pause / resume / cancel / retry**; auto‑retry with
  exponential backoff; a **queue** with a configurable concurrency limit; a **foreground service**
  (via WorkManager) with a live progress notification (percentage, speed, ETA).
- **Library & player** — completed items in **Videos / Audio / Images** tabs with thumbnails, an
  **in‑app Media3/ExoPlayer** player, plus **share / open‑with / rename / delete**.
- **Modern, dark‑first UI** — Jetpack Compose + Material 3, edge‑to‑edge, light/dark/system theme
  toggle, bottom navigation (Home · Downloads · Library · Settings).
- **Branding** — splash screen with the logo and “Built by Mohamed El‑Meligy”, an **About the
  Developer** screen, and a credit line in Settings.

---

## 🏗️ Architecture

**Clean Architecture + MVVM**, single Gradle module, organized by layer. Dependencies point inward
(UI → domain ← data); the domain layer knows nothing about Android UI or specific data sources.

```
com.melmeligy.mediadownloader
├─ core/            Cross‑cutting: Result/Error types, dispatchers, NetworkMonitor, formatting, URL utils
├─ domain/          Pure business layer (no Android UI deps)
│  ├─ model/        MediaStream, ResolvedMedia, DownloadItem, AppSettings, enums
│  ├─ repository/   Repository interfaces (Media, Download, Bookmark, Settings)
│  └─ extractor/    MediaExtractor plugin contract + ExtractorRegistry (fallback chain)
├─ data/            Implementation of the domain contracts
│  ├─ local/        Room database, DAOs, entities, type converters
│  ├─ remote/       Retrofit service for page/playlist text
│  ├─ prefs/        EncryptedSharedPreferences-backed settings store
│  └─ repository/   Repository implementations
├─ extractor/       Concrete extractors: Direct, Image, HLS, HTML (+ shared ExtractorHttp)
├─ download/        SegmentedDownloader, HlsSegmentDownloader, DownloadManager (queue),
│                   DownloadQueueWorker (WorkManager foreground), MediaStoreWriter,
│                   DownloadNotifier, MediaActions
├─ di/              Hilt modules (Network, Database, Bindings, Extractors)
└─ ui/              Compose screens + ViewModels (splash, home, browser, quality,
                    downloads, library, player, settings, about) + navigation + theme
```

**Key patterns**

- **Dependency injection:** Hilt throughout (`@HiltAndroidApp`, `@HiltViewModel`, `@HiltWorker`).
- **Extractor plugin/registry:** each `MediaExtractor` is contributed with `@Binds @IntoSet`; the
  `ExtractorRegistry` orders them by priority, tries the willing ones first, then the rest as a
  fallback chain, and surfaces the most specific typed error. **Adding a new source = adding one
  extractor class + one line in `ExtractorModule`** — no UI or download‑manager changes.
- **State survives process death:** the queue lives in Room; `DownloadQueueWorker` re‑queues any
  interrupted download on restart and segmented downloads resume from their sidecar `.idx` file.
- **Typed errors:** every failure maps to an `AppError` and a friendly, localized message — no
  silent crashes for no‑internet / invalid link / geo‑blocked / expired / out‑of‑storage / etc.

### Tech stack

Kotlin · Jetpack Compose · Material 3 · Hilt · Room · WorkManager · Media3/ExoPlayer ·
OkHttp + Retrofit · Jsoup · Coil · EncryptedSharedPreferences · Scoped Storage / MediaStore.
`minSdk 26 (Android 8.0)` · `targetSdk 35` · Java 17.

---

## 🔧 Build

### Option A — Android Studio (recommended)
1. Install **Android Studio Koala (2024.1)** or newer.
2. **File → Open** this folder and let it sync (Android Studio provisions the correct Gradle
   automatically).
3. Select the `app` configuration and **Run**, or **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

### Option B — Command line
Requires JDK 17 and the Android SDK. If the Gradle wrapper isn’t present yet, generate it once
(needs Gradle 8.7 installed):

```bash
gradle wrapper --gradle-version 8.7      # one‑time, if ./gradlew is missing
./gradlew assembleDebug                   # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest               # run unit tests
```

### Option C — GitHub Actions (produces the APK for you)
Push to `main`/`master` (or run the workflow manually). `.github/workflows/android.yml`:
- runs the unit tests and lint,
- builds the debug APK,
- uploads it as the **`media-downloader-debug-apk`** artifact (plus test/lint reports).

Download the APK from the workflow run’s **Artifacts** section.

> **Note on the Gradle wrapper:** the binary `gradle-wrapper.jar` is intentionally not committed.
> Android Studio and the CI workflow generate the wrapper automatically (`gradle wrapper`), so no
> build step is blocked.

---

## 🔐 Signing a release build

By default `assembleRelease` falls back to the debug signing key so the APK is always installable.
To sign with your own key, create a `keystore.properties` file in the repo root (it is git‑ignored):

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=****
keyAlias=****
keyPassword=****
```

Then `./gradlew assembleRelease` produces a release‑signed, minified (R8) APK. For CI signing,
store these as GitHub **Secrets** and write them into `keystore.properties` in a workflow step.

---

## 📲 Installing the APK on a real device (sideload)

1. Transfer `app-debug.apk` to your phone (USB, cloud, or download the CI artifact directly).
2. Open it with a file manager.
3. When prompted, allow installation from this source:
   **Settings → Apps → Special access → Install unknown apps → (your file manager) → Allow**
   (on older Android: **Settings → Security → Unknown sources**).
4. Tap **Install**, then **Open**.

On first launch you’ll see the splash screen, then the app requests notification permission
(Android 13+) so it can show download progress.

---

## ➕ Adding support for a new source

1. Create a class implementing `MediaExtractor` in the `extractor/` package. Use the injected
   `ExtractorHttp` for network access, return a `ResolvedMedia` with one or more `MediaStream`s
   (or `null` to defer to the next extractor), and throw a `MediaException(AppError.…)` for typed
   failures.
2. Register it in `di/ExtractorModule`:
   ```kotlin
   @Binds @IntoSet
   abstract fun bindMySource(extractor: MySourceExtractor): MediaExtractor
   ```
That’s it — the registry, quality screen, and download engine pick it up automatically. **Only add
extractors for sources you are authorized to download from.**

---

## ✅ Testing & quality

- **Unit tests** (`app/src/test`): formatting utilities, URL utilities, the extractor registry
  (priority + fallback + typed errors), the HLS playlist parser, and a ViewModel. Run with
  `./gradlew testDebugUnitTest`.
- **Instrumentation tests** (`app/src/androidTest`): a Hilt test runner and a smoke test; run on a
  device/emulator with `./gradlew connectedDebugAndroidTest`.
- **R8/ProGuard** rules are provided for the minified release build.
- The codebase contains **no TODO/FIXME/stub/mock/placeholder** production code.

---

## 📌 Honest limitations

These are deliberate scope boundaries, documented rather than faked:

- **HLS download** merges **clear (non‑encrypted) MPEG‑TS** segments. Encrypted/DRM streams and
  fMP4 (`#EXT‑X‑MAP`) segment formats are **declined with a clear message** — the app does not
  circumvent protection, and does not bundle a muxer.
- **“Audio only”** downloads any **separate audio stream a source actually provides**. Extracting an
  audio track *out of a video* (transcoding to MP3/M4A) requires an on‑device transcoder such as
  FFmpeg, which is not bundled; the setting stores your preferred bitrate for when multiple audio
  streams are available.
- Watermarks are never fabricated or removed; the app downloads the original stream a source exposes.
- The developer environment used to generate this project cannot run an Android emulator, so runtime
  “crash‑free” verification and instrumentation tests must be executed on a device/CI. The code is
  written defensively (typed errors, no silent crashes) and unit‑tested; CI compiles and tests it.

---

## 📄 License & credits

Developed by **Mohamed El‑Meligy**. Use responsibly and in accordance with the terms of service of
any website or platform you access.
