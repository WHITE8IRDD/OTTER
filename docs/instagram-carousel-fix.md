# Instagram carousel fix — OTTER 26.1

Report: the picker opened for an Instagram carousel, the preview stayed blank, the
counter said **1/1 selected**, and *Download Selected* saved nothing.

Test post (used verbatim, query string included):

```
https://www.instagram.com/p/DeEo7SjjLYz/?img_index=2&mdxt=MTB3NWI3aGFiYWg3eQ==
```

## What was actually wrong

| Symptom | Cause | Fix |
| --- | --- | --- |
| Picker showed 1/1 | Instagram answers a logged-out **browser** user agent with a JavaScript shell that contains no post payload at all (617 KB, zero `display_url`). The one image it found came from `og:image`. | The post is now also requested as **Googlebot** and **facebookexternalhit**: Instagram still serves those crawler user agents the server-rendered page, which contains the whole carousel (`carousel_media_count: 20`, 26 signed CDN URLs). |
| Slides lost / wrong count | Carousel entries in that payload are wrapped as `{"node": {...}}`, and only a window of the slides is inline while the total is declared separately. | The parser unwraps `node`/`media`, honours `carousel_media_count`, keeps payload order, and picks the carousel whose subtree contains the requested post id — so other posts' pictures never leak into the picker. |
| Blank previews, empty downloads | CDN URLs were being stored without their signed query string (Instagram then answers 403), and Coil loaded thumbnails without a browser user agent / instagram.com referer. | Signed query strings are never stripped, HTML/JS escapes (`&amp;`, `\u0026`, `\/`) are decoded, and Coil sends browser headers for `cdninstagram.com` / `fbcdn.net`. |
| "Saved" but no file in the folder | `Download/Otter` needs *All files access* on Android 11+, which the app never requested, so every write failed silently. | Before downloading, the folder is write-probed; if it is not writable the app saves into its own folder (`Android/data/com.otter.app/files/Pictures/Otter`) and says so in the toast. |
| Release builds | — | `arm64-v8a` ABI split (not universal), published as a debug-signed test APK. |

## Verification (automated, on CI, using the app's own code)

`InstagramPostFetcher` + `InstagramMediaParser` + `ImageDownloadCore` are Android-free, so the
exact code path the app runs is executed against the live site and against a real
download folder. Result of run
[38143735576](https://github.com/WHITE8IRDD/OTTER/actions/runs/38143735576):

```
LIVE-CHECK url=https://www.instagram.com/p/DeEo7SjjLYz/?img_index=2&mdxt=MTB3NWI3aGFiYWg3eQ==
LIVE-CHECK http=page=200,page-mobile=200,page-crawler=200
LIVE-CHECK sources=page(0),page-mobile(0),page-crawler(20/20)
LIVE-CHECK slides=20 declared=20
LIVE-CHECK saved=OTTER-26.1_1.jpg  bytes=71695    type=image/jpeg
LIVE-CHECK saved=OTTER-26.1_2.jpg  bytes=116812   type=image/jpeg
...
LIVE-CHECK saved=OTTER-26.1_20.jpg bytes=33152    type=image/jpeg
LIVE-CHECK real-download=OTTER-26.1_live.jpg bytes=282530 type=image/png extension=png
```

* Both live tests **PASSED**: `InstagramLiveVerificationTest.carouselSlidesAreReadAndDownloadedToDisk`
  (20/20 slides read, every selected slide written to a download folder and confirmed
  to be a real picture by signature sniffing) and `LiveImageDownloadTest`
  (a genuine HTTP download through the same save path).
* 30 JVM unit tests cover the parser shapes (web payload, crawler payload,
  GraphQL sidecar, embed, partial reads, login wall, escaped inline JS, ordering,
  duplicate edges, single-photo posts) plus the download core.
* The same URL was used with `img_index=2` and `mdxt=...` preserved.

## Test APK

[OTTER-26.1-arm64-v8a-debug.apk](https://github.com/WHITE8IRDD/OTTER/releases/tag/otter-26.1-arm64-v8a)

* arm64-v8a only (built for phone testing), debug-signed with the public Android
  debug key committed at `.github/debug.keystore` (stable across runs, so a newer
  test build installs over the previous one).
* app sha256 `83612c37d1274004fd0e6030d5652734a16a99eb551e737e7c78f162024c8eec`,
  signing certificate SHA-256 digest `d21fe965b440a2b06fd2199c32a5703664c17aab39519ecbdbf98912b4059273`.
* Release signing is not configured for this repository (no keystore, and it must
  not be committed). Adding the `OTTER_KEYSTORE_BASE64`, `OTTER_KEYSTORE_PASSWORD`,
  `OTTER_KEY_ALIAS` and `OTTER_KEY_PASSWORD` repository secrets makes the workflow
  build and publish the signed **release** variant instead.

## On the phone

1. Uninstall an older OTTER build first if Android refuses to install over it.
2. Open the post link → the picker should list **20 photos**, "Select all" → 20/20.
3. *Download Selected* → 20 JPEGs in the chosen folder. If the folder is
   `Download/Otter` without *All files access*, OTTER saves to its own app folder
   and tells you in the toast.
