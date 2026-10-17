# Outter 28 — release candidate (not yet approved for publication)

Version name 28; Android version code 22. The release uses the existing official
signing key, application ID and ABI conventions. Universal and ABI-specific APKs
plus an AAB are built by the official release workflow.

## Implemented changes

- Instagram media is classified per typed post/slide. Video posters, play overlays,
  raw CDN strings, unrelated page images and alternate renditions are not download items.
- Carousel children may have different shortcodes from their parent. All readable
  image/video slides remain ordered; unresolved video slides count as missing instead
  of being silently replaced with their posters.
- Pictures and Videos sections in both selection windows, with original item numbers,
  previews, one selection set and one Download action. Empty sections are omitted.
- One TikTok extraction identity for metadata and downloads. YouTube uses maintained
  engine defaults rather than a frozen client exclusion. Metadata reads resolved JSON,
  not flat video-page entries; engine failures are no longer suppressed with ignore-errors.
- SHA-256-pinned yt-dlp 2026.08.19 is bundled at build time, including its EJS solver;
  the existing wrapper supplies QuickJS and FFmpeg. Old installs migrate on first use.
  Updates and running engine processes cannot modify the same files concurrently.
- Streaming transfers validate byte counts and contents instead of trusting MIME labels
  or extensions. Video downloads require an Android video track. AVIF/HEIF are not MP4.
  Interrupted/invalid responses fail; temporary files are removed; completed files are
  renamed without a redundant full copy where supported.

## Verification — read before release

The verification matrix and actual run results are recorded in
`docs/releases/Outter-28-verification.md`. Automated synthetic-media tests do **not**
prove that current Instagram, YouTube or TikTok endpoints work. Real source-media
verification and device sign-off are mandatory. No bypass of login walls, DRM,
anti-bot challenges, geographic restrictions or copyright controls is provided.
Only download content you are entitled to save.

This candidate must not be advertised as a verified official release while the
verification report has pending scenarios. The workflow builds candidates by
default; publishing is an explicit, evidence-gated operation.
