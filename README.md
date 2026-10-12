# OTTER

OTTER is an Android media downloader built with Kotlin and Jetpack Compose. It uses the yt-dlp Android wrapper for supported video/audio extractors and has a selectable image-gallery flow for Instagram posts and ordinary web pages.

## Media support

- YouTube videos, Shorts, playlists, and live streams (subject to the source's access rules).
- Video and audio from sites with a yt-dlp extractor.
- Instagram posts and carousels: a picker that lists photo slides and video slides separately, and downloads both (photos through the image path, videos through the video path).
- Direct image links and images exposed by common page metadata (`og:image`, Twitter cards, JSON-LD, `img`, and `srcset`).
- Quality/format and audio-track selection, subtitles, cookies for sites that require sign-in, background downloads, pause/resume, and download history.
- History entries keep the title, description and hashtags of what was downloaded; tap an entry to read them and copy each one.
- Share-sheet quick downloads for video links; image and Instagram post links open the selectable media flow. Quick downloads skip the metadata step, so their history entries may have no description or hashtags.
- Custom download location, accent-color/dark-theme options, and English, Arabic, and French UI.

Site extractors change over time. Support depends on the current site, network access, login requirements, and available media. JavaScript-only pages, private content without valid cookies, DRM-protected streams, and sources that block automated access may not be downloadable. Only save media you have permission to keep, and follow each site's terms.

## Build

Requires JDK 17 and Android SDK Platform 35.

```sh
./gradlew :app:assembleDebug
```

The build creates ABI-specific debug APKs and a universal debug APK under `app/build/outputs/apk/debug/`.
