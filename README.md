# OTTER

OTTER is an Android media downloader built with Kotlin and Jetpack Compose. It uses the yt-dlp Android wrapper for supported video/audio extractors and has a selectable image-gallery flow for Instagram posts and ordinary web pages.

## Media support

- YouTube videos, Shorts, playlists, and live streams (subject to the source's access rules).
- Video and audio from sites with a yt-dlp extractor.
- Instagram posts and carousels: a selectable picker that labels photo and video slides separately, routes videos through the video path, and preserves signed CDN URLs.
- TikTok photo-mode posts: a no-cookie public-page parser reads `imagePost.images` and opens the same selectable image gallery; yt-dlp remains the primary route for TikTok videos.
- Direct image links and images exposed by common page metadata (`og:image`, Twitter cards, JSON-LD, `img`, and `srcset`).
- Quality/format and audio-track selection, subtitles, optional user cookies for less-common sites that explicitly require them, foreground-service downloads, pause/resume, and download history. Known public social extractors do not receive the saved cookie file.
- Clipboard links are detected while the Home screen is resumed, inserted automatically, and fetched without an extra paste tap. The Android foreground service keeps downloads running when the screen closes and restores an interrupted request after ordinary process reclamation when possible.
- History entries keep the title, description and hashtags of what was downloaded; tap an entry to read them and copy each one.
- Share-sheet quick downloads for video links; image and Instagram post links open the selectable media flow. Quick downloads skip the metadata step, so their history entries may have no description or hashtags.
- Custom download location, accent-color/dark-theme options, and English, Arabic, and French UI.

Site extractors change over time. No app can promise zero-second resolution or successful downloads from every public URL: support depends on the current extractor, network access, region, source availability, and anti-bot controls. Private content, login walls, DRM-protected streams, JavaScript-only pages, expired signed URLs, and sources that block automation may not be downloadable. OTTER does not bypass those restrictions. Only save media you have permission to keep, and follow each site's terms.

## Build

Requires JDK 17 and Android SDK Platform 35.

```sh
./gradlew :app:assembleDebug
```

The build creates ABI-specific debug APKs and a universal debug APK under `app/build/outputs/apk/debug/`.
