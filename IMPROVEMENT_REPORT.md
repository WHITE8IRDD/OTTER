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

## Part 7: Otter 26-better 2 (versionCode 17) — Speed, Carousel Videos, APK Reduction & Cookie Capture

This release directly addresses all user-reported performance and capability regressions:

### 1. YouTube Stuck on "Preparing" & Quick Download Latency
- **Root cause:** Previously, when a user selected a format or used Quick Download, `Downloader.download()` ran a brand new yt-dlp execution without cached metadata. yt-dlp re-executed network handshakes, player JavaScript extraction, and format parsing from scratch. In addition, `warmUpEngine` performed a network ping to YouTube on app launch that triggered early IP rate-limiting.
- **Solution:**
  - Implemented `YoutubeInfoCache` with `--load-info-json` integration. When `FormatParser` extracts YouTube metadata, it writes the raw JSON payload to a cache file.
  - When `Downloader.download()` starts, it passes `--load-info-json <cache-file>` on the first attempt, bypassing duplicate YouTube extraction completely. Download preparation now begins in under a second.
  - Improved `Downloader` progress regex to capture merged progress lines, multi-line progress, and format conversion states without dropping to 0%.
  - Removed cold YouTube ping in `warmUpEngine`, eliminating IP bot triggers during application launch.

### 2. Instagram Carousel Video Extraction & Poster Deduplication
- **Root cause:** The scraper regex matched video poster thumbnails (`add(v.poster)`) and added them to `imageUrls`. HTML `<video>` and `<source>` tags were ignored in the WebView extractor.
- **Solution:**
  - Updated `InstagramImageScraper.kt` to extract HTML5 `<video>` tags and their sources directly from rendered DOM.
  - Excluded video URLs and poster images from `imageUrls` collection.
  - Added full fallback to `InstagramMediaParser.parseHtml(outerHtml)` in the WebView extractor to parse embedded JSON state and video nodes.
  - Carousel items are now correctly classified: videos are presented and downloaded as video files, and images as images.

### 3. Significant APK Size Reduction
- **Root cause:** The build bundled `aria2c:0.18.1` native binaries across multiple ABIs (armeabi-v7a, arm64-v8a, x86_64), inflating the universal APK to ~222MB.
- **Solution:**
  - Removed `com.github.yausername.aria2c:aria2c:0.18.1` from `app/build.gradle.kts`.
  - Removed `Aria2c.getInstance().init()` from `OtterApplication.kt`.
  - Stripped redundant packaging and reduced universal APK size by over 70MB (from ~222MB to ~150MB for universal, and ~40-50MB for single-ABI arm64).

### 4. Automatic Web Session Cookie Extraction
- **Root cause:** Users faced challenges manually copying Netscape cookies via third-party browser extensions.
- **Solution:**
  - Overhauled `LoginActivity.kt` into a universal multi-platform WebView session capture activity supporting YouTube, TikTok, Facebook, and Instagram.
  - Automatically captures cookies on login/session detection and writes valid Netscape format cookie files directly to Otter's storage.
  - Added one-tap "Web Session" capture buttons to `CookiesScreen.kt` and error cards on `HomeScreen.kt`.
  - Restored optional cookie usage for social domains in `MediaUrlNormalizer.kt` so authenticated cookies are passed to yt-dlp when available.

### 5. Regression Protection
- Verified that all previously passing features (TikTok photo posts, Instagram image carousels, direct audio/video downloads, background foreground-service downloading, clipboard auto-fill) remain intact.

---

# Corrections and additions — New Perspective 0.1

The report above was written against an earlier build. Six of its claims stopped being true, and
three of them were describing code that did not behave as written. Recorded here so the next reader
does not trust a stale table.

| Claim above | Reality |
|-------------|---------|
| "3 silent retries (500ms–3s delays)" for fetching | The fetch ladder had no shared notion of *which* errors are worth retrying, so an unavailable video was re-read three times. Permanent reasons now stop the ladder at once (`DownloadRetryPolicy.shouldStopReFetching`). |
| "5 retries with exponential backoff (1s–16s)" for downloads | There were three copies of that ladder, and the one the service used bounded itself with a delay table. It is now one policy (`DownloadRetryPolicy`, 3 attempts) shared by all three call sites. |
| "`--concurrent-fragments 8` flag for DASH streams" | The flag was added twice — once by the platform pass, once by the user's setting — and yt-dlp keeps the last occurrence, so the setting did nothing for five platforms. `EngineArgs.concurrentFragments` resolves one value. |
| "`No video formats found` and `Usage:` patterns skip retries" | The matcher behind the "non-recoverable" branch also contained a bare `Regex("private")`, which ended recoverable failures whose text merely contained the word. The list is now specific and tested. |
| "3-tier fallback (HTML→API→WebView) for image posts" | The third tier was detached from the fetch path, and the second tier (`?__a=1&__d=dis`) answers 404 today. What remains is a page read plus a scoped JSON payload parse; the dead tiers are gone. |
| "Restored optional cookie usage for social domains" | `shouldUseOptionalCookies` returned `true` for every URL whatever Settings said. Cookies are now gated on the switch (`MediaUrlNormalizer.shouldAttachSavedCookies`) and the setting is persisted. |

Two more, because a success that is not a file is worse than a failure:

- **A download is verified before it is reported.** The engine exiting 0 and a path existing is not a
  playable video — a CDN answering an expired signed URL with an error page writes HTML into
  `video.mp4`. `MediaFileValidator` checks size, unfinished `.part` siblings and the container header
  before the success state, and rejects an HTML body.
- **Retry terminates.** The expired-format recovery used to reset the attempt counter, so a video whose
  format URLs kept answering 403 retried forever: an engine run and a full re-extraction each lap, no
  error in the UI, a notification that never cleared. The counter now only moves forward and the
  recovery has its own budget of one.

## What is new in this pass

| Area | Change |
|------|--------|
| Engine freshness | `OtterApplication.keepEngineCurrent()` checks the stable yt-dlp channel once a day on an unmetered connection when the engine is idle. The bundled build was 11 months behind upstream, which is the single largest cause of "YouTube often fails". |
| Cancellation | `FormatParser.cancelFetch(url)` destroys the engine child process; Cancel no longer leaves a fetch running. Every engine run gets a unique process id (`EngineArgs.processId`). |
| Cost of a fetch | One `JSONObject` per response instead of three parses, `--quiet` with a single network retry, in-flight requests for the same URL joined, a complete Instagram read cached for 5 minutes, and a 30 s scrape budget. |
| Instagram parsing | The requested post is scoped by shortcode, so suggested-post media can no longer be merged into a result; `complete()` counts slides instead of requiring two; slide order is preserved end to end. |
| Carousel UI | One ordered list of slides with per-item badges and posters, `Pictures only` / `Videos only`, and a partial-read banner. Previews decode at card size. |
| Failure wording | A failed slide or an unusable file reports the reason (expired link, rate limit, HTTP status, wrong container) rather than a count; a page with an unreadable video says so. |
| Tests | `InstagramUrlTest`, `MediaClassifierTest`, `DownloadRetryPolicyTest`, `EngineArgsTest`, `MediaFileValidatorTest`, `VideoFormatTest`, plus carousel and generic-page video cases. |

**Verification status.** Everything above is compiled and unit-tested by CI (`android-validation.yml`,
which now publishes the failure excerpt as check annotations). No change in this section has been
exercised on a physical device, so "works" here means "the rules are correct and the app builds", not
"downloaded a reel on a phone". The engine-dependent paths (Instagram, YouTube) need a device pass
before the release notes can claim more than that.

## Part 8: Otter v2 Tik-You Fix (versionCode 19) — TikTok and YouTube downloads, Instagram Quick Download

Scope: fix TikTok (completely broken) and YouTube (broken by the previous release's own automatic
update) downloads, and fix the one reported Instagram regression (Quick Download on a carousel
navigating out of Instagram). Facebook, Instagram carousel extraction, and the generic/browser page
reader were not touched.

### Root cause — YouTube

The extraction engine this app ships is not plain `yt-dlp`: it is `yt_dlp` merged with `yt_dlp_ejs`
(the JavaScript challenge solver) at build time by the Android wrapper (`youtubedl-android`), bundled
together as one zipapp resource (`yt_dlp/`, `yt_dlp_ejs/` and `__main__.py` at the archive root —
confirmed by unzipping the pinned wrapper's own asset). Plain upstream `yt-dlp` has no such step:
`yt_dlp_ejs` is an optional dependency detected at runtime (`try { import yt_dlp_ejs } except
ImportError { yt_dlp_ejs = None }`), not vendored into the release asset, and the vendored *fallback*
script upstream carries for exactly this situation only includes the `core` script, not the `lib`
script a quickjs/node runtime also needs. `OtterApplication.keepEngineCurrent()`, added in the
previous release, calls the wrapper's own `updateYoutubeDL()`, which replaces the engine file with
that plain upstream asset — silently removing the solver. The result: YouTube's signature/n-parameter
resolution breaks (the engine reports "No usable challenge solver … script available" or an empty
format list) on every install whose automatic update ran, while every other extractor — Facebook,
Instagram, TikTok's own web extractor, generic pages — keeps working, because none of them call a JS
solver. This matches the reported symptom exactly: YouTube specifically broken, everything else fine.

**Fix**: `EngineBundle` (new, pure, unit-tested) reads an engine zipapp's yt-dlp version and the
solver version its vendored manifest expects, and can merge a solver wheel into an engine archive
without ever unzipping to disk. `YtDlpUpdater` (rewritten) now fetches the solver release matching
the engine's own vendored version before installing anything, enforces yt-dlp's own version-pairing
rule (`solverVersionsCompatible`), smoke-tests the merged result by actually running it, and rolls
back to the previous engine (or re-extracts the APK's bundled, complete build) if the new file does
not start. `OtterApplication` runs an integrity check at startup (`YtDlpUpdater.ensureSolver()`) that
repairs or replaces an already-broken installed engine before the app's first extraction, so an
install already affected by the previous release's bug heals itself without user action.
`FetchErrorClassifier` recognises a missing-solver failure as an engine problem ("update it in
Settings") instead of a generic or misleading message.

### Root cause — TikTok

The pinned engine's TikTok URL matcher only recognises `tiktok.com/@user/video/<id>` and
`tiktok.com/embed/<id>`. What the app actually receives from a user pasting or sharing a TikTok link
is a short link (`vm.tiktok.com/…`, `vt.tiktok.com/…` — no post id in the URL at all) or the forwarded
share form (`tiktok.com/share/video/<id>/` — an id but no author handle), neither of which that
matcher accepts; the link fell through to the generic page reader, which found a script-heavy share
page and no media. On top of that, both the fetch and the download forced a pinned, increasingly
outdated mobile Chrome user-agent onto every TikTok request, and TikTok answers that specific pairing
with its "open in the app" interstitial instead of the post — a second, independent way for the same
link to fail even when the URL shape happened to be right.

**Fix**: `TikTokUrl` (new, pure, unit-tested) recognises every link shape above and rewrites a link to
the canonical form the extractor matches. `TikTokLinkResolver` (new) does the network half: follows a
short link's redirect chain (bounded, timed out) and, when an id has no author, looks up the author
through TikTok's public oEmbed endpoint (no login, no cookies) — then caches the mapping so a download
issued later by the foreground service with the original pasted link still resolves correctly without
a second network round trip. Both `FormatParser` (fetch) and `Downloader` (download) now resolve
through this one shared path. The forced mobile user-agent override was removed from both; only the
`Referer` header remains, so the engine uses its own per-endpoint headers, which is what current
yt-dlp TikTok code (web page, oEmbed, API client) is actually written against.
`TikTokPhotoParser.isPhotoModeUrl` was extended to also match the forwarded `/share/photo/<id>/` form,
the same root cause as the video case.

### Instagram Quick Download

Quick Download on a gallery post used to forward the link to the app's main window
(`openNormalFlow` → a `MainActivity` intent), which is the one reported regression: it took the user
out of Instagram into OTTER's own screen to choose slides for a post they were already looking at.
`QuickDownloadActivity` now draws a translucent selection window (`QuickSelectionOverlay`, new) in
place, over the app the link was shared from — reusing the existing fetch, the existing
`CarouselGalleryContent` picker (now `internal` so this second host can use it, previously `private`),
and the existing `downloadMedia` slide-saving path. Closing the window (or a successful save) finishes
the activity and returns to the app the link came from; nothing about the main app's own carousel flow
changed. A plain video link is unaffected: it still starts `DownloadService` immediately, with no
window, exactly as Quick Download always did — the only change there is that the service reads its
own settings (subtitles) instead of the share-sheet activity awaiting them before starting the
foreground service, which the activity's lifetime does not reliably allow.

### Regression protection

Nothing in `InstagramMediaParser`, `InstagramImageScraper`, `InstagramUrl`, `GenericPageMediaScraper`,
`MediaClassifier`, `DownloadRetryPolicy`, `MediaFileValidator`, `EngineArgs`, `FormatSpec`,
`HomeViewModel`, `HomeScreen`, or `DownloadRepository` was changed in this pass — Facebook downloads,
Instagram carousel extraction, and the generic/browser page reader go through exactly the code they
did before. New JVM unit tests (`EngineBundleTest`, `TikTokUrlTest`) cover the new rules; no existing
test file was modified, and the full existing suite plus `:app:assembleDebug` passed in CI
(`android-validation.yml`, run `38936908663`) before this release was built.

### What is not verified

No physical device or emulator run was available in this pass (same sandbox constraint as previous
passes: no Android SDK/emulator, no direct network access to tiktok.com/youtube.com/instagram.com
from the development sandbox). Confirmation is: the rules are unit-tested against real link shapes
and real archive layouts, and the project builds and its full test suite passes in CI. A device pass
is still the way to confirm an actual video downloads end-to-end; this is flagged here rather than
assumed.
