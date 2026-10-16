# OTTER

OTTER is an Android media downloader built with Kotlin and Jetpack Compose. It uses the yt-dlp Android wrapper for supported video/audio extractors and has a selectable image-gallery flow for Instagram posts and ordinary web pages.

## Media support

- YouTube videos, Shorts, playlists, and live streams (subject to the source's access rules). The bundled extraction engine is paired with its JavaScript challenge solver on every update and repaired automatically at startup if that pairing is ever broken, since a solver-less engine cannot resolve YouTube's playback URLs.
- TikTok videos: short links (`vm.tiktok.com/…`) and the forwarded share form (`tiktok.com/share/video/<id>/`) are resolved to the link shape the engine recognises before extraction, for both the fetch and the download.
- Video and audio from sites with a yt-dlp extractor.
- Instagram posts, reels and carousels (`/p/`, `/reel/`, `/reels/`, `/tv/` and forwarded `/share/…` links): the picker lists a gallery's slides in the order the post gave them, labels each as a picture or a video, previews clips with their poster frame, and offers one-tap "pictures only" / "videos only" selection. Signed CDN URLs are preserved, and a partially read album says so instead of presenting itself as complete.
- TikTok photo-mode posts, including the forwarded `/share/photo/<id>/` form: a no-cookie public-page parser reads `imagePost.images` and opens the same selectable image gallery; yt-dlp remains the primary route for TikTok videos.
- Direct image links, and media exposed by common page metadata: images (`og:image`, Twitter cards, JSON-LD, `img`, `srcset`) and, on pages a search engine can reach, the page's own video (`og:video`, a player stream, `<video>`/`<source>`, manifest links, JSON-LD `contentUrl`). A page that advertises a video it cannot hand over reports that instead of offering its thumbnails.
- Quality/format and audio-track selection — format rows show frame size, codec, frame rate and bitrate, not only a quality bucket — subtitles, optional user cookies, foreground-service downloads, pause/resume, and download history. Saved cookies go to the engine only when the switch in Settings says so, and never to the app's own public-page readers.
- Clipboard links are detected while the Home screen is resumed, inserted automatically, and fetched without an extra paste tap. The Android foreground service keeps downloads running when the screen closes and restores an interrupted request after ordinary process reclamation when possible.
- History entries keep the title, description and hashtags of what was downloaded; tap an entry to read them and copy each one.
- Share-sheet quick downloads for video links; a gallery link (Instagram post/reel, TikTok photo post, direct image) opens the same selectable media picker in a translucent window drawn over the app the link was shared from, so quick-downloading a carousel does not navigate away from Instagram (or wherever the link came from). Quick downloads skip the metadata step, so their history entries may have no description or hashtags.
- Custom download location, accent-color/dark-theme options, and English, Arabic, and French UI.

Site extractors change over time. No app can promise zero-second resolution or successful downloads from every public URL: support depends on the current extractor, network access, region, source availability, and anti-bot controls. Private content, login walls, DRM-protected streams, JavaScript-only pages, expired signed URLs, and sources that block automation may not be downloadable. OTTER does not bypass those restrictions. Only save media you have permission to keep, and follow each site's terms.

## Build

Requires JDK 17 and Android SDK Platform 35. Unit tests for the URL, ranking, retry and validation rules run on the JVM alone:

```sh
./gradlew :app:testDebugUnitTest
```

```sh
./gradlew :app:assembleDebug
```

The build creates ABI-specific debug APKs and a universal debug APK under `app/build/outputs/apk/debug/`.
