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
when the post is still not fully read; per-request timeouts dropped to 10 s and the
overall budget to 30 s.

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
