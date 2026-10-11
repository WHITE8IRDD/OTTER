# Media-download research and implementation choices

Research was completed before changing the downloader. The goal was to reuse mature extractor behavior without replacing OTTER's working download flow.

## Open-source references reviewed

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) maintains extractors for a broad range of video/audio websites. Its YouTube documentation describes changing client requirements and recommends a JavaScript runtime for full YouTube extraction support.
- [yt-dlp-android](https://github.com/yausername/youtubedl-android) is the Android wrapper already used by OTTER. Its [`0.18.1` release](https://github.com/yausername/youtubedl-android/releases/tag/0.18.1) adds QuickJS and updates the bundled yt-dlp binary, so OTTER now uses that release consistently for the library, FFmpeg, and aria2 artifacts. [Maven Central lists the `library:0.18.1` artifact](https://central.sonatype.com/artifact/io.github.junkfood02.youtubedl-android/library), and the upstream installation guide documents the matching dependency coordinates.
- [gallery-dl](https://github.com/mikf/gallery-dl) provides image/gallery extractors, including Instagram support. Its [Instagram carousel discussion](https://github.com/mikf/gallery-dl/discussions/8198) and [rate-limit issue](https://github.com/mikf/gallery-dl/issues/5677) document endpoint changes, cookie requirements, and throttling of automated requests.
- [DownVid](https://github.com/reinanbr/downvid) and [Comfort](https://github.com/d-gibr1l/comfort) are open-source Android examples that combine yt-dlp-style video extraction with explicit image/gallery handling and a selectable gallery UI. Their architecture informed the decision to keep OTTER's existing picker and add a bounded webpage-image fallback rather than replace the app or bundle a second Python extractor stack.

## Changes informed by the research

1. Update the Android yt-dlp wrapper and allow yt-dlp to use its current YouTube client headers instead of forcing OTTER's old static YouTube user-agent.
2. Canonicalize YouTube Shorts links to their ordinary `watch?v=` URL for extraction and download.
3. Preserve query parameters on Instagram CDN image URLs because those URLs can carry media-delivery parameters.
4. Keep the existing Instagram image selection grid and improve image downloads with MIME/extension handling, collision-safe names, transient retries, referer support, and accurate partial-success feedback.
5. Add a bounded fallback for direct images and images exposed through standard static-page metadata and tags. Keep yt-dlp as the primary route for site-specific video extraction.

## Limits

No single extractor can guarantee every website. The generic image fallback reads static HTML conventions; it does not execute arbitrary page JavaScript or bypass login, DRM, paywalls, anti-bot controls, or source restrictions. yt-dlp support also changes as sites change. Failures should be explained to the user rather than hidden behind claims of universal support.
