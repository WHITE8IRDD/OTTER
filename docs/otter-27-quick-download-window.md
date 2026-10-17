# OTTER 27 — Quick Download window, one media-selection logic everywhere

Release name: **Outer 27** (`versionName` 27, `versionCode` 21). The APK listed for
download is `Outer 27.apk` (the universal build).

## 1. The regression: Quick Download stopped opening its window

Sharing a post to **Quick Download** used to answer with a small download window over
the sharing app. It had been replaced by `QuickDownloadActivity.openNormalFlow()`, which
*forwarded Instagram posts into `MainActivity`* ("the main window IS the selection
window") and started engine downloads for everything else with no window at all. That is
exactly the behaviour the requirement forbids: the user is dragged out of Instagram to
pick files of a post that Instagram was already displaying.

### What the window is now

`share/QuickDownloadActivity` hosts `QuickDownloadWindow`, a compact dialog on a dimmed
backdrop — the app that shared the link stays visible (and resumed) underneath:

1. The shared link is read by the existing fetch pipeline (`DownloadRepository.fetchVideoInfo`):
   Instagram scraper first, TikTok photo parser, direct images, generic page media,
   yt-dlp last. Nothing new was added to extraction.
2. Every file of the post is listed — pictures **and** clips in post order, clips with
   their poster frame and a play badge — with select-all / clear, and `Pictures only` /
   `Videos only` on mixed posts. All items start selected (one tap on Download saves the
   whole post).
3. **Download** hands the picked files to `DownloadService`'s new
   `ACTION_START_MEDIA_BATCH`, a foreground-service job that fetches the slides straight
   from their signed CDN URLs (the same path the Home screen's picker uses — photos and
   clips together), posts progress notifications, and writes one History row for the
   post. The window then closes back into Instagram.
4. Plain video links (YouTube, Shorts, Vimeo, TikTok videos) are one item in the same
   window; Download sends them through the existing engine path with `bestvideo+bestaudio/best`
   and the post's real title/metadata (previously a synthetic "Quick Download" title and
   no metadata).
5. A failure shows the classified error *inside the window* with Retry — never a redirect.

`QuickDownloadRouting` holds the URL-shape decision (Instagram post vs TikTok photo vs
TikTok video vs direct image vs engine video) as Android-free rules, and
`QuickDownloadRouting.MediaBatchSpec` turns a selection into the exact service payload.

## 2. Instagram formats

The required matrix is pinned shape-by-shape in
`InstagramFormatMatrixTest`: single image, single video, image-only carousel,
video-only carousel, mixed carousel, partial read. Each asserts *every* slide is found
in post order with correct kinds and posters.

One real gap was found and fixed along the way: `InstagramMediaParser.findCarousel`
discarded a carousel that exposed only one readable slide, so a post that declared five
slides but read one reported "complete". Single-slide carousel payloads are now kept as
a fallback (a real multi-slide carousel still wins), so `expectedCount` survives and the
picker warns.

## 3. TikTok

- `TikTokPhotoParser.isPhotoModeUrl` accepts forwarded `/photo/<id>` links that drop the
  `@user` segment (tested).
- Photo-mode posts open the same selection window with their images; video posts use the
  engine path — the two kinds are distinguished by URL shape and, if that is ambiguous,
  by what the fetch pipeline actually extracted (`carouselMedia` non-empty ⇒ gallery).
- Engine fallback: `FormatParser.extractCarouselMedia` now also recognises TikTok
  photo-post entries the engine itself can read (page parser empty ⇒ engine entries,
  CDN files only — a flat playlist's video page links are never offered as "pictures").

## 4. YouTube (YTDLnis as the reference)

`docs/RESEARCH-open-source-engines.md` records the study of
[`deniscerri/ytdlnis`](https://github.com/deniscerri/ytdlnis) (GPL-3.0) and what was
already adopted from it: `--load-info-json` reuse with delete-on-failure
(`YoutubeInfoCache`), quiet single-retry fetches (`EngineArgs.FETCH_QUIET_ARGS`),
unique killable process ids, and a maintained engine (`YtDlpUpdater`). Nothing from
YTDLnis is copied — only command-line policies OTTER implements itself.

This release adds:

- **MP4 output for merged YouTube downloads** (long-form and Shorts):
  `--merge-output-format mp4` remuxes `bestvideo+bestaudio` into a container every
  Android player opens — the same goal as YTDLnis' "compatible" output mode, without
  capping quality to h264.
- Shorts keep the `watch?v=` normalisation; both fetch and download carry the
  SABR-workaround `player_client=default,-android_sdkless`.
- Quick-window downloads now travel with the real title, description and hashtags.

## 5. Other websites

The extraction stack is layered and reusable: yt-dlp extractors (Vimeo, Dailymotion,
…) with per-site headers first; `GenericPageMediaScraper` for pages no extractor owns
(declared og:/JSON-LD video first, images second, and a page that advertises a video it
cannot hand over says so); `FetchErrorClassifier` + `FriendlyErrors` for a message that
matches the cause (unsupported, DRM/login-walled, rate-limited, region-blocked…).
Support is deliberately *not* claimed to be universal.

## 6. Regression containment

- The Home screen's fetch/picker/auto-download flows are untouched except that their
  error wording now delegates to the shared `FriendlyErrors` (same strings).
- `DownloadService`'s single-download path (pause/resume/stop, restore-on-reclaim,
  History rows) is unchanged; the batch path is additive and restores itself after
  process death like the single path does.
- CI (`Android validation`) runs `:app:testDebugUnitTest` + `assembleDebug` for every
  push to the work branch before anything is published.

## 7. Testing status — honest

Verified in this pass: JVM unit tests (routing, batch spec, Instagram format matrix,
TikTok photo URLs, carousel ordering, retry ladder, file validation) and CI builds.
**Not** verified here: physical-device runs of the window on live Instagram/TikTok/YouTube
(this sandbox has no device). The first post-install check should be the exact scenario
of the requirement: browse Instagram → share a carousel → Quick Download → pick files →
Download → still inside Instagram, files saved.
