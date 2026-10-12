# OTTER 26.2 — mixed carousels and history metadata

> Note: this session was asked to follow `docs/next-session-tasks.md`. That file is not
> in the repository (it is absent from `master` and from every branch), so the work below
> follows the request text itself: (1) mixed carousel posts, (2) history metadata.

## 1. Mixed carousel posts (photos **and** videos)

Before this change a carousel slide that was a video was thrown away: the parser only
collected `image_versions2`/`display_url`-style URLs, and `FormatParser.extractCarouselImages`
skipped every entry with `is_video`. A mixed post therefore offered fewer slides than it had
and there was no way to download its video slides.

| Area | Change |
| --- | --- |
| `engine/InstagramMediaParser` | Slides are classified by `media_type` (2 = video, 1 = photo), `is_video`, or a non-empty `video_versions`. Photos and videos are returned as two ordered lists (`Result.videoUrls`, `Result.videoPosters`), plus `Result.caption`. `expectedCount` (from `carousel_media_count`) now covers both kinds, and `isComplete`/`isPartial` count photos + videos. |
| `engine/InstagramImageScraper` | `ScrapeResult` carries `videoUrls`, `videoPosters` and `caption`; every endpoint (page, mobile page, web API, embed, `__a=1`, WebView) merges both lists. The WebView extractor returns `{"images":[…],"videos":[…]}` and collects `video.src`, `video.currentSrc` and `<source>` entries. |
| `data/model/VideoInfo` | New `carouselVideos`, `carouselVideoPosters`, `carouselExpectedCount`, plus `carouselMediaCount` (photos + videos) and `isCarouselPartial`. |
| `engine/FormatParser` | `extractCarouselMedia` returns photos **and** videos (video entries keep a URL yt-dlp can extract); `createImageGalleryInfo` takes videos, posters, expected count and description; Instagram posts get a title from the caption and hashtags extracted from it. |
| `ui/components/FormatSheet` | The picker shows a **Photos** section and a **Videos** section, each with its own grid and selection. Video tiles show their poster frame and a play badge. |
| `ui/screens/home` | `selectedPhotoIndices` / `selectedVideoIndices` replace the single index set. *Download Selected* sends photos through `downloadImages` (image path) and each video slide through `DownloadService` (video path), one at a time. |

### Count and partial-read warning

The counter is one number for the whole post: `selected / (photos + videos)`. When the
source declares more slides than could be read (`carouselExpectedCount > carouselMediaCount`)
the picker shows a warning: *"Only N of M slides could be read. The rest may need site
cookies or may not be public."* The same condition now drives `InstagramMediaParser.Result.note`
(`page listed M slides but only N were readable`), which the scraper logs.

### Posters

A video slide's `image_versions2` is only its poster frame, so it is kept as that slide's
preview and never offered as a photo of the post (`collectMediaUrls` consumes
`image_versions2` / `display_resources` / `video_versions` instead of walking into them).

## 2. History: title, description and hashtags

| Area | Change |
| --- | --- |
| `data/model/DownloadItem` | New `description` and `hashtags` columns. |
| `data/db` | Room `version = 5` with an explicit `Migration(4, 5)` adding both columns (`fallbackToDestructiveMigration` stays as the last resort), and `Converters` gained a unit-separator string-list converter. |
| `service/DownloadService` | New extras `EXTRA_UPLOADER`, `EXTRA_DESCRIPTION`, `EXTRA_HASHTAGS`, `EXTRA_DURATION`, `EXTRA_IS_AUDIO_ONLY`, `EXTRA_HAS_SUBTITLES` and `EXTRA_SKIP_HISTORY`. On `Success` the service now writes the History row itself (file size and extension read from the finished file). This also means retry and resume produce a History entry, which they previously did not. |
| `ui/screens/home/HomeViewModel` | Sends the metadata with every download and no longer writes the row itself (that was the duplicate-entry risk); carousel photo downloads get one row for the post, with the caption and its hashtags. |
| `ui/screens/history/HistoryScreen` | Tapping a card opens a details dialog with the title, description and hashtags, each with its own copy button, plus a *Copy all* action. |
| `ui/screens/history/HistoryViewModel` | Retry re-sends the metadata the original entry had. |

Quick downloads (`share/QuickDownloadActivity`) skip the info step, so they have no
description or hashtags to save — those entries simply show the title and a
"no description was saved" note. Set `EXTRA_SKIP_HISTORY` on any `DownloadService`
intent to leave a download out of History entirely.

## Tests

`./gradlew :app:testDebugUnitTest`

* `app/src/test/kotlin/com/otter/app/engine/InstagramMixedCarouselTest.kt` — web
  `carousel_media`, GraphQL `edge_sidecar_to_children`, crawler page (inline JSON),
  partial reads, single-photo and single-video posts, signed query strings, caption
  reading, profile-picture filtering.
* `app/src/test/kotlin/com/otter/app/data/model/CarouselMediaTest.kt` — the picker
  counter and partial-read logic on `VideoInfo`.
* `org.json:json` was added as a `testImplementation` dependency: the Android platform
  `org.json` classes are only stubs under a plain JVM.

## Release

The `Android APK` workflow now also runs on `arena/3dd8a9d3-otter`, derives the artifact
name from `versionName`, summarises the unit-test results and publishes the
`arm64-v8a` debug APK to the `otter-<version>-arm64-v8a` release with `gh release create`.

---

## Follow-up: saving the slides (post-release fixes)

Three reports came back after the first 26.2 build: posts with more than two videos
saved nothing at all, posts with only pictures saved nothing at all, and mixed posts
still saved only their pictures. All three had the same root cause.

### Video slides never reached the CDN

`HomeViewModel.downloadSlideVideos()` sent each video slide — a raw
`scontent…fbcdn.net/….mp4?oh=…&oe=…` link — to `DownloadService`, which runs it through
yt-dlp with `-f best`. yt-dlp has no extractor for a bare CDN link, so nothing was
ever fetched, and each slide then sat on a 30 minute timeout before being counted as
failed. Two videos merely took an hour to do nothing; three or more looked like the
app had stopped responding.

Slides now travel the same path the photo slides always used. `Downloader` grew
`downloadMedia(slides: List<MediaSlide>, …)`: a plain `HttpURLConnection` GET that
keeps the signed query string and sends the post URL as `Referer`, four slides at a
time, with `Accept: video/…` and an mp4/webm/mov container check for video slides
(`isLikelyMediaFile`, `sniffVideoExtension`, `videoExtensionFromUrl`). One call saves
the photos and the videos of a post together, in one progress run, and the post gets
a single History entry instead of one per slide.

| File | Change |
| --- | --- |
| `engine/MediaSlide.kt` | New: a slide URL plus whether it is a video. |
| `engine/Downloader.kt` | `downloadMedia()`; `downloadSingleImage` generalised into `downloadSingleMedia(url, …, isVideo)`; `downloadImages` is now a thin wrapper; `sanitizeBaseName` also strips control characters. |
| `data/repository/DownloadRepository` | `downloadMedia()` passthrough. |
| `ui/screens/home/HomeViewModel` | `downloadCarouselSelected()` builds one slide list (photos then videos) and saves it in a single pass. `downloadSlideVideos` / `startSlideVideoService` and the 30 minute timeout are gone. The history write is wrapped so it can no longer abort the download. |

### Fetching a post was five round trips in a row

`InstagramImageScraper` tried the post page, the mobile page, the web API, the embed
page and the legacy `?__a=1` JSON one after another, each with its own 15 s timeout —
up to 75 s before the picker appeared. The post page, mobile page and web API are now
requested together in a `coroutineScope`, and the embed page and legacy JSON only run
when the post is still not fully read. The per-request timeout stayed at 15 s and the
overall HTTP budget at 40 s: cutting them to 10 s/30 s along with the parallel rounds
made every request time out on anything slower than a fast connection, which is how
*every* Instagram post ended up with an empty picker.

The declared slide count also came from *every* JSON blob on the page, including the
ones for suggested posts, so a complete post was routinely reported as a partial read.
`InstagramMediaParser.parseHtml` now trusts only the payload that produced the most
slides.

### UI

* The partial-read note in the picker is a quiet `surfaceVariant` row with an info
  icon instead of a red `errorContainer` block.
* History's *Clear all* is a plain text button instead of a red brutalist one.
* An Instagram playlist that could not be broken into slides but did come back with
  formats now opens the format sheet instead of an empty picker.

### Working without a login

The app must download public posts with no cookies at all, and nothing in the fetch
sends a cookie unless one is stored, so that stays true. What did change is how much
of a post is read before giving up: the embed page and the legacy `?__a=1` payload are
now merged whenever the first parallel round left the post unfinished or found a single
slide, because Instagram routinely spreads one post across several payloads and another
round in parallel is cheap.

An Instagram post that still exposes no slides used to open a picker with nothing in
it and no download button — indistinguishable from a download that silently does
nothing. It now fails with `error_instagram_no_slides`, which says the post could not
be read and can be retried, in all three locales.

### Every Instagram post stopped working

Three separate faults, all of which end the same way: the screen sits on "Parsing
formats…" and then shows no picker and no error.

1. **Timeouts.** See above — 10 s per request meant slow connections read nothing.
2. **Silence.** `fetch_stage_parsing` is not a real stage; it is the third step of a
   cosmetic timer (800 ms, 1200 ms, 1500 ms), so it says nothing about where a fetch
   died. What actually freezes the screen is an exception escaping `parseVideoInfo`:
   it kills the coroutine that owns the fetch, and the screen keeps whatever stage it
   had last. Instagram work is now wrapped in `runCatching` plus a 60 s budget, so it
   can only ever fall through to the next engine, never out of the function.
3. **The WebView fallback never ran.** It was gated on `context is Activity`, but
   `FormatParser` only ever holds the application context, so the one path that still
   reaches a logged-out page was dead code. It now runs either way: a detached view is
   sized by hand with `layout(0, 0, 1080, 4000)` because a 0×0 page never loads its
   images, and the paging script falls back to the first matching *Next* button when
   nothing reports as visible — which in a detached view is everything.

The scrape result is also kept and reused by the two later fallback points instead of
being fetched a second time, and `InstagramMediaParser` stops feeding every JSON blob
on the page through `org.json`: a post page carries payloads for suggested posts too,
which cost time and memory and only ever contributed other posts' media.

Because CI cannot prove Instagram works — an unauthenticated probe gets a login wall or
a page with no media markers — the empty-picker error now carries what actually
happened: the scraper's per-endpoint counts (`page(0) page-mobile(0) webview(2) …`)
plus the yt-dlp response type, entry count and format count. A failure report from a
device says which endpoint to look at instead of just "nothing appeared".

## Follow-up: reading a carousel from the page payload

A carousel was only recognised when its node carried `media_type: 8`. That field belongs
to Instagram's private API. The payload that is actually available without a session types
the post with `__typename: GraphSidecar` (or `XDTGraphSidecar`) and carries no
`media_type` at all.

So the sidecar was never recognised, and the post fell through to `collectMedia`, a blind
breadth-first scan of the whole payload. That scan reads whatever it walks into first —
on a post page that is the suggested posts around the real one — and it reads a video
slide as the video's poster frame unless `is_video` happens to be present. The result was
a carousel that looked like a picture-only post.

Two rules changed, both in `InstagramMediaParser`:

* `slides()` recognises a sidecar by `edge_sidecar_to_children`, the field only a sidecar
  has, rather than requiring `media_type` 8.
* `isVideoNode()` reads `__typename` before anything else: a slide typed `*GraphVideo` is
  a video on that evidence alone, and one typed `*GraphImage` is a picture, so a missing
  `is_video` can no longer demote a video slide to a poster.

### Why the old tests did not catch it

`InstagramMixedCarouselTest` was written against fixtures shaped like the parser: every
slide had `media_type` and `video_versions`. Real payloads do not. The fixtures passed
while the app did not work, which is the opposite of what a test is for.

`InstagramPagePayloadTest` is written against the source instead. Its fixtures use
`__typename`, have no `media_type`, give the video slide its video as `video_url`, and
include suggested posts around the real one — so a post that is not recognised genuinely
fails rather than quietly returning the wrong media.

## Follow-up: aria2c, tried and reverted

The *Use Aria2c* setting was wired up in an attempt to speed downloads, and reverted in
the next commit: it stalled YouTube, Shorts and Instagram video posts. Instagram picture
posts were unaffected, which was the tell — slides take a plain-HTTP path that never saw
the option, while everything running through yt-dlp did.

That is the whole lesson: the change was reasonable on paper and unverifiable here, and
shipping it cost more than it was worth. The setting was dead before and is dead now, so
the toggle has been removed from Settings rather than left as a promise the app does not
keep.

What remains of the speed work is the one change that removes work instead of adding a
component: a format that already carries audio is no longer paired with a second audio
stream (`FormatSpec`). That halved the data and dropped the merge on muxed picks.

## Tests

`FormatSpec` is a pure object so the format-selection rules can run on the JVM, and
`FormatSpecTest` pins them. `InstagramPagePayloadTest` covers the real page shapes.
CI runs the suite before the APK is published; 43 tests, all passing.

What CI cannot do is run the app. Instagram serves the runner a login wall on every
endpoint, so the probe job reports shapes and nothing more — behaviour on a real device
still has to be confirmed by using it.

## Follow-up: YouTube speed and long videos

The report was that YouTube downloads take forever, and that long videos (an hour) do
not complete smoothly. Facebook, Instagram and TikTok were reported as working well and
were left alone.

Two things were costing time, and they were the only two touched.

### The finished file was written twice

Every download staged its output in the app cache and copied it out at the end. For a
one-hour video that is a second full write, and it needs room for the video in internal
storage as well as on the card. On a phone with a full internal storage that is a
download that never finishes rather than a slow one.

YouTube now writes straight into the download folder (`-P <outputDir>`), so there is one
write and no cache space used. Three things keep that from being reckless:

- `cleanup(dir)` only deletes a directory inside `context.cacheDir`. A download folder
  is never cleared, however the download ended.
- The finished file is identified by the video id yt-dlp puts in the file name
  (`MediaFileLocator`), not by "newest media file in the folder" — in a shared download
  folder the newest file is often somebody else's.
- Live streams are excluded and still stage in the cache.

### Two other changes were tried and taken back out

Both shipped in `2e54218` and were removed again after testing on a device.

**16 concurrent fragments instead of 8.** This broke normal, non-Short YouTube videos
outright: they would neither fetch nor download. Shorts were unaffected, which is what
made it easy to misread at first — a Short is served as a single muxed stream, so
`--concurrent-fragments` does not apply to it, while a normal video is DASH with many
fragments and gets the flag on every attempt. Back to 8.

Worth remembering: the reason Shorts surviving proved the direct-write path sound is
that Shorts and normal videos run the *same* `download()` function
(`normalizeYouTubeShortsUrl` rewrites a Shorts link to a watch URL before the request is
built). So "Shorts work, normal videos do not" rules out anything that treats them
alike, and points straight at the one input that differs between them — the number of
fragments.

**Reusing the extraction (`--load-info-json`).** The video was extracted a second time
for the download after already being extracted to show the format list, which for YouTube
means starting Python, fetching the watch page and solving the throttling challenge
again. The extraction was saved (`YoutubeInfoCache`) and handed back. It was taken out
with the fragment change rather than left in on its own: it cannot be verified here, and
a flag that makes the first attempt fail is a poor trade when the retry ladder has to
re-extract anyway. If it is ever worth another attempt, it should be on its own, with
nothing else changed at the same time.

### Not touched

The 3-attempt retry ladder with its 10 second socket timeout, `--extractor-retries 5`,
the always-on thumbnail conversion, the quick-download fetch-then-download, and the
`youtube:` extractor arguments. If YouTube is still slow after this, those are the
places to look next.

### Tests

`YoutubeDownloadTest` reads the video id out of watch, Shorts, shared Shorts and
youtu.be links; picks the right file out of a folder that already holds other videos;
and pins the window in which a saved extraction may be reused. `MediaUrlNormalizer` and
`MediaFileLocator` are pure, so this runs on the JVM.

CI still cannot tell us whether downloading feels faster. It can only confirm the
suite passes and the APK builds; the speed has to be judged on a device.
