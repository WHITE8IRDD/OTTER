# Outter 28 — investigation and root causes

## Confirmed code defects and corrections

| Area | Confirmed defect | Correction / verification |
|---|---|---|
| Instagram HTML | With fewer than two parsed items, `parseHtml` scanned every CDN string. A valid single video therefore gained its poster and play-overlay pictures as extra items. | Only typed post snapshots produce items. HTML video + poster + overlay regression test passes. |
| Instagram carousels | Child `shortcode` values were compared with the parent post's shortcode, excluding valid children. Array length was not retained when a video had no readable URL. | Identify the parent first, then classify children independently; retain declared/array counts. Five-slide and missing-video regressions pass. |
| Single media / renditions | The broad JSON walk collected multiple image/video renditions and could confuse an unflagged `video_url` node with its image. | One best rendition per media node. Video fields/typings take precedence over posters. Largest `config_width` is supported. |
| Browser fallback | JavaScript scanned `img`, `srcset`, background images, source tags and raw script URLs throughout the page. These are assets, not a typed album. | Remove the asset scan. Rendered hydration goes through the same parser. Do not mutate global WebView cookie acceptance. |
| Combining endpoints | URL-set unions mixed alternate renditions into fake extra slides and could make partial reads look complete. | Prefer a richer typed snapshot, keep conservative expected counts, warn on unresolved items. |
| Engine galleries | Flat extraction left webpage links unclassified; a thumbnail or presence of `formats` could be mistaken for media. | Resolve full JSON, classify individual entries, exclude thumbnails, unresolved flat links and storyboard formats. Preserve order/posters. |
| TikTok | Metadata and download requests used different browser identities. | One policy supplies the same UA/referer for supported full and short hosts; no static mobile installation ID. Policy/routing regressions pass. |
| YouTube | The APK relied on an old wrapper-bundled extractor or a delayed startup updater; app code overrode the engine's client defaults. | Ship SHA-pinned stable yt-dlp 2026.08.19 with EJS, retain wrapper QuickJS/FFmpeg, migrate existing installs and use maintained client defaults. Native version/merge/decode test passes. Live source success remains unproven. |
| Engine reliability | `ignore-errors` could hide extraction failure. An update could delete files used by another process. | Propagate failures; read/write exclusion between engine processes and updates. No new bypass of platform restrictions. |
| Transfers | MIME labels/URL extensions were accepted as proof of contents; missing/truncated bodies could look successful. AVIF/HEIF `ftyp` headers were classified as MP4. | Compare length, inspect bytes, reject unknown/mismatched data and require video tracks. Real HTTP bytes and Android decoding tests pass. |
| File organization | Shared staging could mix different URLs; file lookup with a missing YouTube ID fell back to unrelated media; copy could clobber an existing file. | URL-scoped staging, exact-ID lookup, collision-safe output, same-filesystem rename for galleries. |
| Audio / quality | A combined `bestvideo+bestaudio` selection was treated as audio by a substring check. Format refresh could drop the merge spec or choose an unrelated format. | Explicit kind travels from service to validator; retain the selected format expression, clear stale metadata before refresh. Audio-only and native merge tests pass. |
| Picker | One undifferentiated grid, and a clickable backdrop ancestor merged labels/dismiss semantics. | Pictures/Videos sections keep source indexes and omit empty categories. Independent backdrop, bounded lazy grid and original selection mapping. Eight device tests including native and picker checks pass. |

## Scope of evidence

These are observed code defects and reproducible synthetic tests, not assertions
about what Instagram, YouTube or TikTok currently return to a particular device.
Live source failures can additionally depend on availability, authentication,
region, extractor changes and anti-bot controls. See the verification matrix;
all real-platform acceptance scenarios remain pending and block publication.
