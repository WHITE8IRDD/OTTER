# OTTER — New Perspective 0.1 · Technical audit (Phase 1)

Audited commit: `06381ba` (branch `master`, tagless squash "Merge pull request #7").
Auditor: Arena agent. Method: full read of `app/src/main/kotlin` (16.6 k lines, 46 files),
the build files, the three GitHub Actions workflows and the eight JVM test classes, plus a
source study of YTDLnis and Gabi and of the pinned extraction engine (see
[RESEARCH-open-source-engines.md](RESEARCH-open-source-engines.md)).

## 0. Verification environment (read this before trusting any statement below)

This is stated up front because it bounds what can be called "verified":

| Capability | Status in this workspace |
|---|---|
| Read/modify the repository, git, `gh` | **available** |
| JDK 17 + Android SDK (local `./gradlew` build/test) | **not available** — no `java`, and `dl.google.com` / `repo1.maven.org` / `services.gradle.org` are unreachable from the sandbox |
| Android emulator / physical device | **not available** |
| Direct network access to instagram.com / youtube.com / tiktok.com | **not available** (only github.com, codeload, api.github.com, npmjs, pypi are reachable) |
| GitHub Actions (full toolchain + open internet) | **available** — this is the build/test/probe rig used for every claim below |

Consequence: every statement is labelled **CONFIRMED** (proved by reading code that cannot be
interpreted otherwise, or by a test that runs in CI), **MEASURED** (a CI probe reported it), or
**HYPOTHESIS** (the most likely cause that still needs a device). No claim of "works on a phone"
is made anywhere in this project unless it cites a device log.

## 1. What the application actually is

* **Language/UI**: Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material3, single-Activity
  with `navigation-compose`; Room 2.6.1 (history, paused downloads), DataStore preferences,
  Coil for image loading. `minSdk 26 / target 35 / compileSdk 35`, ABI splits + universal APK.
* **Extraction engine**: `io.github.junkfood02.youtubedl-android:library:0.18.1` (+ `ffmpeg`).
  That is youtubedl-android, which embeds a CPython runtime and the yt-dlp zipimport
  (`library/src/main/res/raw/ytdlp`). Inside the pinned artifact the bundled yt-dlp is
  **`2025.11.12`** and it ships `yt_dlp_ejs` (`core.min.js`, `lib.min.js`) plus the wrapper
  injects `--js-runtimes quickjs:<nativeLibDir>/libqjs.so` on *every* run — i.e. the
  signature/n-solver path for current YouTube exists in this build.
* **Two download mechanics**, and this is the architectural core to understand:
  1. *Engine path* — `Downloader.download()` → one `YoutubeDLRequest` per file, progress
     callback, foreground `DownloadService`, file located afterwards by `MediaFileLocator`.
  2. *Direct path* — `Downloader.downloadMedia(slides)` → plain `HttpURLConnection` GETs of
     already-signed CDN URLs (4 at a time), used for every Instagram/TikTok gallery slide,
     photo **and** video.
* **Platform routing** is not a resolver class. It is a chain of `MediaUrlNormalizer.hostMatches`
  checks duplicated in four places (`FormatParser.applyPlatformArgs`, `Downloader.applyPlatformArgs`,
  `MediaUrlNormalizer.isKnownExtractorHost`, `FetchErrorClassifier.detectPlatform`) plus ad-hoc
  `url.contains("instagram.com")` tests in the ViewModels. There is no shared "platform" concept,
  which is the direct cause of two of the reported bugs (§3.1, §5).
* **Instagram is special-cased before the engine**: `FormatParser.fetchVideoInfo` runs
  `InstagramImageScraper` (HTML page → web API → embed → legacy JSON → WebView render) and only
  falls through to yt-dlp if the scraper found nothing. TikTok photo-mode has its own parser
  (`TikTokPhotoParser`). Everything else is "yt-dlp, then generic page images if no formats".

### Full lifecycle of one download (traced)

```
HomeScreen (clipboard listener / paste / share intent)
  → HomeViewModel.onUrlChanged → 300 ms debounce → fetchVideoInfo()
  → DownloadRepository.fetchVideoInfo → FormatParser.fetchVideoInfo (Dispatchers.IO)
      ├─ MediaUrlNormalizer.normalizeYouTubeShortsUrl + resolveTikTokShortUrl (blocking redirect walk)
      ├─ isDirectImageUrl → image info (no network)
      ├─ videoInfoCache hit (5 min) → return            [Instagram deliberately skips this cache]
      ├─ TikTokPhotoParser path (8 s budget)
      ├─ Instagram scraper path (60 s budget; 5 endpoints; WebView last)
      └─ YoutubeDLRequest --dump-json … up to 3 attempts (1.5 s + 5 s backoff)
            → parseFormats / parseSubtitleTracks / parseAudioTracks / extractCarouselMedia
            → YoutubeInfoCache.keep(videoId, rawJson)
  → VideoInfo → HomeViewModel state → FormatSheet (format list) | CarouselGalleryContent (picker)
  → onFormatSelected → FormatSpec.forVideo → Intent → DownloadService (foreground, START_STICKY)
      → Downloader.download → YoutubeDL.execute(request, processId) → progress → DownloadStateHolder
      → MediaFileLocator.findDownloaded → FileHelper.scanFile → Success → Room history row
  → downloadCarouselSelected → DownloadRepository.downloadMedia → per-slide GET → save + scan
```

## 2. Instagram (Phase 3)

Working today, and to be preserved: image-only carousels. The parsing layer
(`InstagramMediaParser`) is genuinely good: slides are read in payload order, photo and video
slides are kept in separate lists, signed query strings are preserved (Instagram 403s without
them), duplicates are keyed on host+path, and the declared slide count is carried so a partial
read can be reported instead of celebrated.

### 2.1 CONFIRMED — reels and `/tv/` links never reach the Instagram extractor

`FormatParser.isInstagramPostUrl()` (`FormatParser.kt:516`) returns true only for a path that
`contains("/p/") && !contains("/reel/")`. `InstagramImageScraper.canExtract()` (`:665`) repeats the
same exclusion, and `QuickDownloadActivity.isInstagramPostUrl()` (`:141`) a third time. Instagram
shares single-video posts as `https://www.instagram.com/reel/<code>/` (and the modern
`/share/reel/<code>/` redirect form), so for exactly the media type that is reported broken:

* the scraper, the WebView fallback and the "no readable slides → say why" diagnostic are all skipped;
* the URL goes straight to yt-dlp's `instagram` extractor, which answers logged-out requests with a
  login wall (device-verified in `IMPROVEMENT_REPORT.md` §Part 6: "Instagram answers logged-out
  scrapers with a login page"), so both workflows fail — Quick Download with a toast, normal download
  with the raw engine error;
* `InstagramMediaParser.shortcodeFromUrl` **does** accept `p|reel|tv`, so the tooling needed for
  reels already exists — only the routing excludes them.

Fix: one recognition helper (`InstagramUrl`) accepting `/p/`, `/reel/`, `/reels/`, `/tv/` and the
`/share/...` forms, used by all three call sites, with the canonical URL keeping the original kind
(`/reel/` renders a `<video>` player; rewriting it to `/p/` is what makes a reel page yield nothing).

### 2.2 CONFIRMED — the post-scoped payload is never selected, so suggested posts pollute the result

`InstagramMediaParser.parseHtml` (lines ~430-470) walks **up to 12** `<script type=application/json>`
blobs of the page (`MAX_JSON_BLOBS = 12`, budget 1.2 MB) and merges *every* media URL of *every*
blob into one photo set:

```kotlin
for (blob in jsonBlobs(html)) {
    val partial = parseJson(blob, HTML_PAYLOAD)
    ...
    partial.imageUrls.forEach { photos.add(it) }      // ← blobs of suggested posts included
}
```

Its own comment says a post page "carries payloads for suggested posts too". The `expected` count
is taken from the richest payload, but the media are not scoped to it. This is the direct cause of
the Phase 5 complaint *for Instagram*: a page whose post has one video yields one video plus up to
40 unrelated stills (`MAX_URLS = 40`), and because `createImageGalleryInfo` sets `isCarousel = true`
whenever anything was read, the picker opens as a 41-item gallery, photos first, and
`downloadCarouselSelected` tries them in that order — most of those signed URLs belong to other
posts and expire, which is why the user sees dozens of thumbnails, "nothing saved", or a partial save.

The same problem is already solved for TikTok in this repository: `TikTokPhotoParser.parseHtml`
selects the payload node whose id matches the URL (`findImagePostOwner(root, targetId)`) and
therefore cannot mix in unrelated posts. The fix is to apply that rule to Instagram's HTML walk.

### 2.3 CONFIRMED — single-slide posts pay for two extra endpoint rounds and a dead endpoint

`InstagramImageScraper.scrapeDetailed` stops early only when `complete()` is true:

```kotlin
fun complete(): Boolean = total() > 0 && (expected == 0 && total() >= 2 || expected > 0 && total() >= expected)
```

`total() >= 2` means "a post is only finished when it has at least two slides", so a one-slide post
never completes and the second HTTP round (`embed` + `?__a=1&__d=dis`) always runs before the result
is returned. `?__a=1&__d=dis` was measured dead on device ("a1 404", `IMPROVEMENT_REPORT.md` §Part 6)
and is still requested. This is pure added latency (~2 × 6 s of timeout) on the single most common
post type, and it is also why "paste a link" feels slow.

### 2.4 HYPOTHESIS — video slide *download* can still fail after extraction

Once a video slide is extracted it is fetched by `downloadSingleMedia` with a plain GET and
`Referer: <post url>`; that worked for photo slides on device (5/5 JPEGs verified). Instagram has
served some `video_versions` entries that additionally require the session that fetched the page,
in which case the slide returns 403. Cannot be confirmed from code. Mitigation implemented: the
per-slide failure reason (HTTP status) is now carried in `ImageDownloadSummary.firstFailure` and
shown in the toast instead of a bare "nothing saved", and a 403 on a video slide suggests the
normal download path, which uses the engine rather than a raw GET.

### 2.5 CONFIRMED — a whole-UI-blocking detail in the WebView fallback

`scrapeWithWebView` is entered with `withContext(Dispatchers.Main)` and, when the scraper was built
with an application context (which is what `DownloadRepository` does), `context as? Activity` is
null, so the WebView is never attached to a window — it is manually laid out at 1080×4000 and
expected to render. Media is still produced (device log: 5, 20, 5, 1 slides) but only because
`done()` also hands back `outerHTML` for `parseHtml`. An attached-WebView path would be the honest
fix; out of scope for a patch release, recorded as a known limitation (and it is why the
mixed photo+video case has never been observed live).

## 3. YouTube (Phase 4)

### 3.1 CONFIRMED — an unbounded retry loop in `Downloader.download()`

`Downloader.kt:451-635`. `attempt` is the loop bound (`maxAttempts = retryWaitMs.size = 3`), and the
"format URL expired" recovery decrements it:

```kotlin
attempt++
formatAlreadyRefreshed = false          // reset at the top of every try
try { ... } catch (e: Exception) {
    if (isFormatExpired(msg) && formatParser != null && !formatAlreadyRefreshed) {
        val newFmtId = refreshFormatInfo(formatParser, url, localFormatId)
        if (newFmtId != null) { currentFormatId = newFmtId; formatAlreadyRefreshed = true
                               attempt = (attempt - 1).coerceAtLeast(1); continue }
    }
    if (attempt >= maxAttempts) { onProgress(Error(...)); return }
}
```

Because `formatAlreadyRefreshed` is reset *before* the `try`, the guard `!formatAlreadyRefreshed` is
always true when the catch runs, and `attempt` is pushed back to 1 on every expiry. A video whose
format URLs keep answering 403/410 therefore loops forever: each lap = a full yt-dlp download
attempt **plus** `refreshFormatInfo` → `FormatParser.fetchVideoInfo` → up to 3 more engine runs with
1.5 s and 5 s sleeps. Nothing is reported to the UI (the progress bar just sits there), the
foreground service never finishes, and the network is hammered — which matches the three reported
symptoms at once ("often fails to download anything", "extremely slow", "stuck"), and matches the
directive's warning about endless background loops. Fix: a pure, unit-tested
`DownloadRetryPolicy` that allows exactly one format refresh per download and always advances
`attempt` monotonically.

### 3.2 CONFIRMED — the extractor is never updated, despite code written to do it

`YtDlpUpdater.shouldAutoUpdate()` (`YtDlpUpdater.kt:57`) has **zero callers**; `EngineLock` exists
("the nightly yt-dlp update … runs from the application startup coroutine") and is likewise only
*marked used*, never read; the only reachable update is the manual Settings button. So the engine
stays at the bundled **2025.11.12** forever. Today's stable yt-dlp is **2026.08.19** (pypi), i.e.
the app ships ~11 months of missed YouTube/Instagram/TikTok extractor fixes unless the user finds a
button. That is the single biggest reliability gap for YouTube and it is a wiring bug, not a
missing feature. Fix: run the update from `OtterApplication` after warm-up, only when
`shouldAutoUpdate()` and `!EngineLock.wasUsed` (the guard the class was written for), behind a
default-on preference, and surface the engine version on the same settings row (already present).

### 3.3 CONFIRMED — the same JSON is parsed three extra times per fetch

`FormatParser.fetchVideoInfo` runs `YoutubeDL.objectMapper.readValue(out)`, then
`JSONObject(ytResponse.out)` for `rawJson`, and `parseSubtitleTracks(ytResponse.out)` and
`parseAudioTracks(ytResponse.out)` each build *another* `JSONObject` of the same multi-megabyte
string — four full parses of a YouTube format list on a phone, per fetch, in the UI-blocking
window. Fix: parse once and pass the object.

### 3.4 CONFIRMED — user settings are silently overridden

`applySettings` adds `--concurrent-fragments <user value>` and ~30 lines later `download()` adds
`--concurrent-fragments <platform value>` again; last wins, so the Settings value (1-16) does
nothing for YouTube/Facebook/Instagram/TikTok/Vimeo. The same pair of methods both pass
`--user-agent` for some platforms. Fix: one resolver (`EngineArgs.concurrentFragments`) that lets an
explicit non-default user value win, so the option is emitted once.

### 3.5 CONFIRMED — an over-broad "permanent error" matcher aborts recoverable failures

`nonRecoverableDownloadPatterns` contains a bare `Regex("private", IGNORE_CASE)`. Any message that
merely contains "private" (e.g. Python `ssl`/`private key` tracebacks, `[Instagram] ... private
network` phrasing, `HTTP Error 403 ... private` from a CDN) is treated as permanent and the download
stops immediately without retry, while the narrower `private video` entry above it already covers
the real case. Fix: drop the bare pattern, keep the specific ones, and make the list testable.

### 3.6 CONFIRMED — resolution vs. real quality is not distinguishable in the UI

`VideoFormat.qualityLabel` (`VideoFormat.kt:17`) reports `4K`/`2K`/`1080p` from `height` only, and
`parseFormats` shows `formatNote`. Two 2160p entries (AV1 vs VP9 vs HEVC, `fps 60`, HDR) are
indistinguishable, and "2K" is not a YouTube resolution name. Fix: label as `2160p (4K)` style with
`fps` and a codec name derived from `vcodec` (`av01`→AV1, `vp9`→VP9, `avc1`→H.264, `vp09`…), keep
the real `height`, and keep the format list exactly as the engine reported it (nothing is capped —
verified: `parseFormats` filters only "no video and no audio" streams, so 1440p/2160p and their
separate `m4a`/`opus` audio streams reach the sheet, and `bestvideo+bestaudio` merges with the
bundled ffmpeg). No claim of "guaranteed 4K": availability is the source's, and the sheet reflects
it.

### 3.7 MEASURED earlier / still open — YouTube's bot challenge

`IMPROVEMENT_REPORT.md` §Part 6 records one successful YouTube fetch (57 formats) and later fetches
from the same IP returning "Sign in to confirm you're not a bot". That is an anti-bot response to the
network, not an app defect; the app correctly classifies it (`FetchErrorClassifier.BOT_CHALLENGE`)
and does not tell the user to add cookies for an age check. It cannot be fixed by this app without
circumventing the control, which is out of scope; the legitimate lever is a fresh engine (§3.2) and
user-provided cookies, both of which are wired.

## 4. Performance (Phase 8) — measured where possible

| # | Finding | Evidence | Cost |
|---|---|---|---|
| 1 | Instagram skips the 5-minute result cache **unconditionally** (`if (!isInstaCarousel)` guard) | `FormatParser.kt:199-206` | every retry/back-navigation re-runs up to 5 endpoints + a WebView render |
| 2 | No in-flight dedupe: `fetchJob?.cancel()` + a fresh run for the same URL | `HomeViewModel.fetchVideoInfo`, `quickDownload`, `setSharedUrl` | one paste can start 2-3 extractions (debounce, paste listener, share intent) |
| 3 | Dead endpoint still requested (`?__a=1&__d=dis`) | §2.3 | ~6 s per single-slide post |
| 4 | Four JSON parses per fetch | §3.3 | CPU, big YouTube format lists |
| 5 | Blocking engine retry ladder 1.5 s + 5 s even for permanent errors | `FormatParser.kt:283` | up to 6.5 s of guaranteed waiting before a failure is shown |
| 6 | Slide previews load the full-size CDN image (`AsyncImage(model = url)`, no `size`, no `allowHardware` off) | `FormatSheet.kt` `SlideCard` | decodes up to 1440×1440 bitmaps per row in a 2-column grid |
| 7 | `resolveTikTokShortUrl` walks up to 5 redirects synchronously inside the fetch | `FormatParser.kt:110-152` | up to ~17 s before the engine even starts on `vm.tiktok.com` links |
| 8 | Infinite retry loop | §3.1 | unbounded |

All eight are addressed except §2.5/§3.7 (device work). Timings are instrumented with a single
`Log.i` line per stage (`[TIMING]`) so a device run can produce the before/after table the
directive asks for; I cannot produce that table from this sandbox and do not pretend otherwise.

## 5. Other platforms (Phase 6)

`MediaUrlNormalizer.publicExtractorDomains` (`:14-22`) is **dead code**, and `shouldUseOptionalCookies`
is `= true` for every URL (`:114`) with a KDoc that claims the opposite — so the saved cookie jar is
passed to *all* engines and scrapers, contradicting `README.md` ("Known public social extractors do not
receive the saved cookie file"). The intended policy is restored (cookies only when the user asks for
them for a given host, never for the public paths that must stay logged-out to work), because sending
a YouTube cookie jar to an Instagram HTML request changes the response and can turn a readable public
page into a session-bound one.

TikTok video: `Downloader`/`FormatParser` add a mobile UA + Referer and `--extractor-retries`; the
device report shows TikTok refusing with "status code 0" and its CDN answering 403 without a
session-bound `ttwid`. No app-side fix exists that is not an anti-bot workaround, so TikTok video
stays "engine-dependent" in the matrix, TikTok photo-mode stays supported (it is parser-based), and
the error is classified as `SOURCE_RESTRICTED` rather than "video unavailable" — which the code
already does correctly. Facebook is likewise login-walled for logged-out requests; it is untouched by
this release's changes except that cookies no longer leak into unrelated hosts, and it is a
regression target.

See [PLATFORM-MATRIX.md](PLATFORM-MATRIX.md) for the per-platform table with evidence and status.

## 6. Quick Download (Phase 7)

There is no selection window today. Two different code paths exist:

* share sheet → `QuickDownloadActivity`: galleries/direct images are *forwarded to the normal flow*
  (`openNormalFlow`) and everything else is started immediately as `bestvideo+bestaudio/best` with a
  synthetic title, no metadata, and no user choice;
* Home screen "Quick download" switch → `HomeViewModel.quickDownload()` → fetch, then for a carousel
  just open the picker (select-all), for a video call `downloadBestFormat` directly.

So the difference between "quick" and "normal" is whether the format sheet is shown, not a selection
workflow. Reusing the existing `CarouselGalleryContent` + `FormatSheet` selection UI (rather than a
WebView) is both the smallest change and the safer one: a WebView would need to be added to
`ShareActivity`'s task, would carry cookie/navigation risk, and its extraction is already the least
reliable of the available paths (§2.5). Implementation: the share target now always lands in a
*selection* mode of the same screen (`EXTRA_QUICK_SELECT`), which shows every extracted item with
checkboxes, select-all/clear, a live count and one confirm action, and downloads only the selection
(carousel slides through the direct path, video qualities through the engine path, exactly as today).
The "skip quality picker" preference keeps the old one-tap behaviour for users who want it, so nothing
that exists today is silently upgraded or downgraded.

## 7. Regression risks and how they are contained

| Risk | Containment |
|---|---|
| Breaking Instagram image carousels while scoping payloads to the post | `InstagramMixedCarouselTest` + new `InstagramPostScopingTest` (suggested-post payload must not appear) run in CI before any APK is produced |
| Reels routing sending *bad* URLs into the picker | `InstagramUrlTest` (recognises `p|reel|reels|tv|share/reel`, rejects profiles/tags/stories) |
| Retry policy changing behaviour for non-YouTube platforms | `DownloadRetryPolicyTest` enumerates: expiry→refresh-once, transient→bounded, permanent→immediate fail, cancel→no retry |
| Cookies policy change breaking age-restricted downloads | policy is explicit + unit-tested; the Settings cookie flow is unchanged and the error text still points at it |
| `--concurrent-fragments` dedup changing download speed | value is identical when the user never touched the setting; `EngineArgsTest` pins both cases |
| Picker reuse for Quick Download regressing the one-tap path | `autoDownload` (skip quality picker) keeps the previous branch; selection mode is opt-in from the share sheet |
| Build breakage in code I cannot compile here | every push runs `Android validation` (unit tests + `assembleDebug` for all ABIs) before anything is published |

## 8. Deliberately **not** done (honest scope)

* No emulator/device: the on-device matrix in `IMPROVEMENT_REPORT.md` §Part 6 remains the only
  installed-build evidence, and this release does not silently re-verify it.
* No new extraction engine (Chaquopy/gallery-dl/cobalt, as in Gabi) and no aria2c: see
  RESEARCH-open-source-engines.md §4 for the evaluation and the licensing/size/behaviour reasons.
* No `--js-runtimes`/`--remote-components` tuning: the wrapper already provides QuickJS + bundled
  `yt_dlp_ejs`; adding a GitHub fetch dependency to every extraction would trade a rare staleness
  failure for a common one.
* No in-app player, playlist batch downloads, Firebase/Sentry, or Crashlytics (needs a vendor + keys).
* No removal of the plaintext release-keystore password from `app/build.gradle.kts` in a way that
  would break the existing `RELEASE_KEYSTORE_BASE64` CI secret; it now reads
  `OTTER_KEYSTORE_PASSWORD` with the old value as fallback, and rotating the key itself is the
  owner's task (documented in RELEASE-NOTES).
