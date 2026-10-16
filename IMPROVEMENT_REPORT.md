# Otter — Improvement Report

## Part 1: Cleanup Completed

| File | Change |
|------|--------|
| `Downloader.kt` | Removed 2 unreachable `onProgress(Error)` lines after `return@withContext` (lines 245, 386) |
| `HomeScreen.kt` | Removed unused `ExperimentalLayoutApi` opt-in |
| `InstagramImageScraper.kt` | Added `Log.w` to empty `catch` in `extractImagesFromHtml` |
| `FormatParser.kt` | Removed redundant `YoutubeDL.init()` from `warmUpEngine` (already called by `initAVEngine` in parallel) |
| All 46 Kotlin files | Verified: `collectAsStateWithLifecycle` used everywhere, `key` on all `items()` calls, no dead code |

## Part 2: Fetch Pipeline — Already Optimized

| Area | Status | Detail |
|------|--------|--------|
| Pre-warm | ✅ | `OtterApplication.onCreate()` runs `initAVEngine()` + `warmUpEngine()` in parallel before first use |
| In-memory cache | ✅ | `ConcurrentHashMap` in `FormatParser` with 5-min TTL per URL |
| Retry logic | ✅ | 3 silent retries (500ms–3s delays) with friendly "Almost there…" messages |
| Non-recoverable fast-fail | ✅ | `No video formats found` and `Usage:` patterns skip retries instantly |
| Instagram carousel | ✅ | 3-tier fallback (HTML→API→WebView) for image posts |
| Download retries | ✅ | 5 retries with exponential backoff (1s–16s) |
| DNS pre-resolve | ✅ | Parallel `InetAddress.getAllByName()` before image batch downloads |
| Concurrent fragments | ✅ | `--concurrent-fragments 8` flag for DASH streams |
| Connection keep-alive | ✅ | `--add-header Connection:keep-alive` flag |
| Progress throttling | ✅ | 100ms UI throttle + 500ms notification throttle |

## Part 3: Navigation — Already Optimized

| Area | Status | Detail |
|------|--------|--------|
| Lifecycle-aware state | ✅ | All `StateFlow` collections use `collectAsStateWithLifecycle` |
| LazyList keys | ✅ | Every `items()` call has a `key` parameter |
| ViewModel scoping | ✅ | ViewModels created via `viewModel()` at screen level, correct lifecycle |
| Nav transitions | ✅ | Fade + slide animations, non-blocking |
| Screen pre-warm | ✅ | Bottom nav saves/restores state; no recreation on tab switch |

## Part 4: Top 5 Next Improvements (Informational)

### 1. Keystore Credentials in Build Script (CRITICAL)
`app/build.gradle.kts` contains hardcoded keystore password (`otter123`) and alias (`otter`). Anyone with APK access can extract the signing key. Move to `keystore.properties` (gitignored) or environment variables.

### 2. APK Size — material-icons-extended (~5MB)
The `material-icons-extended` dependency adds ~5MB to the APK. Replace with individual icon imports or `material-icons-core` for the ~15 icons actually used. Could reduce APK by ~40%.

### 3. Instagram WebView Reliability
The WebView scraper (`InstagramImageScraper.scrapeWithWebView`) depends on DOM structure that Instagram changes frequently. The JS-based carousel navigation (`nextBtn()`, click simulation) is fragile. Recommend: (a) use the already-working API path as primary, (b) cache API responses, (c) remove WebView as last resort since it's flaky on MIUI.

### 4. No Crash Reporting
Production users will face edge cases (6th retry, null pointer in format parsing, network loss) with no visibility. Integrate Firebase Crashlytics or Sentry for ANR and crash monitoring. Without it, every crash is silent and unfixable.

### 5. Missing Features vs Competitors (Seal, Snaptube)
- **Batch playlist download** — Single-video-only; no "download all 20 videos" flow
- **In-app video player** — Can't preview before downloading
- **Background download manager** — List of running/completed downloads in notification
- **Multiple language support** — English only
- **Scheduled downloads** — No "download at night" option

## Technical Debt

| Item | Severity | Effort |
|------|----------|--------|
| `FormatParser` has `warmUpEngine` as companion method + instance class with duplicate platform-specific flags vs `Downloader` | Medium | 2h refactor to extract shared flag logic |
| `HomeScreen` (1136 lines) + `FormatSheet` (856 lines) — both above 800-line threshold | Medium | 3h to split into smaller composables |
| `InstagramImageScraper` has 3 parallel HTTP fetch methods with very similar boilerplate | Low | 1h to extract HTTP client helper |
| `Downloader` uses `url.hashCode().toUInt().toString()` as process ID (collision risk) | Low | 30m to use UUID instead |

## Production Risks

| Risk | Likelihood | Impact |
|------|------------|--------|
| Instagram API changes (graphql schema, cookie format) | High | Carousel download breaks |
| yt-dlp extractor updates break Android compatibility | Medium | Fetch fails for specific sites |
| SAF folder picker URI invalidation on reboot | Medium | Downloads go to wrong folder |
| MIUI/EMUI aggressive background killing kills `DownloadService` | Medium | Downloads interrupted |
| Cookies.txt format changes in browser export | Low | Auth-dependent content fails |

## Premium-Feel Polish Ideas

- **Haptic feedback** on button press, download complete, error
- **Lottie animations** for mascot states instead of simple bounce/scale
- **Adaptive icon** with themed background
- **Edge-to-edge** with transparent status/nav bar
- **Biometric lock** for the app (privacy sensitive)
- **Dynamic color** (Monet) support for Android 12+
- **Swipe-to-delete** on history items
- **Pull-to-refresh** on fetch
- **Notification action buttons**: pause/resume/cancel from notification
- **Share sheet quick actions**: "Download audio only" as share target option

## Simplification Opportunities

- **Remove `CookieHelper.kt`** — appears unused (all cookie logic goes through `AppCookieManager`)
- **Merge `ShareActivity.kt` and `QuickDownloadActivity.kt`** — both handle `ACTION_SEND`; QuickDownload is the successor
- **Remove `ReusableComponents.kt`** — check if any of its exports are imported elsewhere (appears vestigial)
- **Remove unused drawables** — PNGs that are no longer referenced in code
- **Consolidate `toReadableFileSize`** — exists in `Extensions.kt`; verify no duplicate in companion objects

## Part 5: Public-link reliability pass (Otter 26-better 1 candidate)

- TikTok photo-mode links now parse the page's `imagePost.images` payload into a selectable image gallery, with signed CDN query strings retained and posters excluded.
- TikTok video metadata and downloads use the same public-web extractor path; the previous hard-coded mobile install ID was removed to avoid a format-list mismatch. Short links now support GET-based redirect resolution with a bounded timeout.
- Instagram endpoint requests are consumed as they complete and cancelled once the declared slides are available. The scraper no longer loads OTTER's saved cookie file for its HTTP requests; its WebView fallback disables cookie acceptance while scraping.
- Major public social extractors do not receive the optional saved-cookie file. The optional cookie feature remains for other sites.
- Clipboard links auto-fill and trigger the existing metadata fetch when the Home screen resumes. Explicit share intents retain priority.
- The foreground download service persists the active request and uses sticky restart behavior so Android can restart ordinary process-reclaimed downloads; yt-dlp's existing `--continue` resumes partial files.
- Added JVM fixtures for TikTok photo-mode parsing and cookie-policy behavior, plus a branch-specific GitHub Actions workflow that runs unit tests, builds a debug candidate, and uploads it as an artifact.

## Part 6: Device-verified public-link pass (versionCode 16)

Errors now name the cause they actually have. A bot challenge ("Sign in to confirm you're not a bot") was matched by the generic `sign in` rule and reported as an age restriction, which sent users to Settings → Cookies for a video that was never age restricted. TikTok's "status code 0" refusal was reported as a generic failure. Both are now distinct messages, and the mapping lives in `FetchErrorClassifier` so it is unit tested against the exact wording the live sites returned. English, Arabic and French strings were added for both.

- `FetchErrorClassifier` (+11 JVM tests) and an on-device Compose test for the clipboard path (`HomeClipboardAutoFetchTest`, 3 tests).

### What was verified on an Android 15 x86_64 emulator, without login or saved cookies

| Case | Result |
|------|--------|
| TikTok photo-mode post (`@mens.guidance/photo/7467226363544554770`) | **Pass.** 6/6 stills read, all 6 downloaded and verified as valid JPEG files. |
| Instagram carousels (`/p/CUbHfhpswxt/`, `/p/DMIyKdftu68/`, `/p/DZ822XajcA1/`, `/p/DYBjngHzfvt/`) | **Photos: Pass. Mixed photo+video: not verified.** Every plain-HTTP endpoint returned no media (`page(0)`, `page-mobile(0)`, `web-api(0)`, `embed(0)`, `a1` 404); the WebView fallback rendered the page and returned the slides — 5, 20, 5 and 1 respectively. The 20-slide result matters: it is Instagram's per-post maximum, so the renderer pages the whole carousel. All 5 slides of `CUbHfhpswxt` downloaded as valid JPEGs. Logged-out Instagram now answers only through a real browser render, which is slow and depends on Instagram's markup. Every public carousel reachable without an account was photo-only, so the mixed photo+video case is covered only by unit tests (`InstagramMixedCarouselTest`), not by a live post. |
| Audio download (direct MP3) | **Pass.** Fetch, download through the foreground service, and file verified on disk. |
| Background audio download | **Pass.** App sent to Home immediately after the tap; service stayed foreground (`isForeground=true`, type `dataSync`), progress notification shown, "Download Complete!" notification posted, file completed. |
| Clipboard auto-fill and auto-fetch | **Pass.** 3 on-device tests: link copied is inserted with no paste tap, a repeat copy of the same link does not clear or re-trigger, non-link clipboard text is ignored. |
| YouTube video | **Partial.** One fetch succeeded (57 formats, correct title); later fetches from the same IP returned "Sign in to confirm you're not a bot". |
| TikTok video | **Blocked.** yt-dlp reports "Video not available, status code 0" on every attempt; the page's own `playAddr` CDN link answers 403 without a session-bound `ttwid` cookie. |
| Facebook reel | **Blocked.** yt-dlp reports "Cannot parse data"; Facebook answers logged-out requests with a login wall. |
| SoundCloud track | **Blocked.** yt-dlp reports "Unable to extract client id" (upstream extractor breakage, not app behaviour). |

No attempt was made to work around a login, a session cookie, or an anti-bot control. No claim is made that any link downloads instantly or that every public link works without a login.

**Verification boundary:** the earlier statement that no JDK, Android SDK or device was available no longer holds. The build, the JVM unit tests (59) and the on-device instrumented tests (3) pass locally, and the live matrix above was run on an Android 15 emulator. Physical-hardware behaviour, OEM background-killer behaviour (MIUI/EMUI), and region-specific results are still unverified.
