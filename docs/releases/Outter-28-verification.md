
# Outter 28 verification record

**Status: NOT RELEASE-APPROVED.** Do not interpret implemented code or a green
build as successful Instagram/YouTube/TikTok downloading.

## Environment and blockers

- Arena checkout has no JDK, Android SDK, adb, emulator or FFmpeg system install.
- Sandbox outbound access is limited to GitHub/package registries. Instagram,
  YouTube, TikTok and their media CDNs cannot be exercised directly here.
- Real, accessible, permission-to-download reference posts covering all five
  Instagram shapes, genuine Shorts, long videos and TikTok links are still needed
  for representative end-to-end verification. Login/DRM/anti-bot restrictions
  must not be bypassed. Tests on a CI data-center network alone cannot establish
  device/region compatibility.
- Existing signing configuration is preserved. Secret enumeration is denied by
  the GitHub integration (HTTP 403); this does not establish that Actions cannot
  access the existing signing secret. The signed-candidate workflow tests that.

## Automated coverage (results appended after runs)

- Existing JVM regression suite plus typed Instagram regressions: HTML poster
  reintroduction, play-button overlays, distinct child shortcodes, 3-picture /
  2-video ordering, inaccessible video slides, alternate renditions, unrelated
  metadata and lookalike hosts.
- Loopback HTTP tests transfer **real synthetic PNG and H.264/AAC MP4 files**
  through the app's transfer implementation. They compare bytes and exercise
  wrong MIME, image-as-video, HTML/JSON errors, HTTP failure/partial responses,
  incomplete bodies and cancellation. These are not live-platform tests.
- Android tests call the batch downloader, decode actual frames and images,
  require audio/video tracks, reject poster substitution and check temporary-file
  cleanup. A native yt-dlp + QuickJS + FFmpeg test merges separate synthetic
  video/audio files, then Android decodes the resulting MP4.
- Compose tests cover Pictures/Videos sections, hidden empty sections, visible
  Download action and original selection indexes after grouping.
- Official workflow builds signed minified release APKs/AAB, checks signing and
  version metadata, then installs and launches the universal APK on API 35.

## Mandatory real-source sign-off matrix

All entries below are **pending** until actual outputs and device checks exist.
Use multiple reference posts per platform, include different carousel orders,
short/full TikTok links, multiple YouTube resolutions and separate-stream merging.

| Requirement | Automated coverage | Live/device acceptance |
|---|---|---|
| Single Instagram image | Typed parser; real PNG transfer | Pending |
| Single Instagram video | No poster substitution; real MP4 transfer | Pending |
| Image carousel | Shape/order tests | Pending |
| Video carousel | Shape/order/poster tests | Pending |
| Mixed carousel (3 pictures + 2 videos) | Parser and Android batch test | Pending |
| Thumbnails/previews not substituted | Parser, HTTP and Android negative tests | Pending |
| Genuine YouTube Shorts | URL/policy tests; native engine exercised offline | Pending |
| Standard long YouTube videos | Format policy; offline separate-stream merge | Pending |
| TikTok video and supported short links | Identity/routing tests | Pending |
| Correct extensions, viewable/playable output | HTTP byte comparison, Android decode/track tests | Pending for live outputs |
| Invalid/unavailable/network failure | URL, HTTP and retry tests | Pending for live source errors |
| Grouped picker, responsiveness | Compose device tests | Pending physical-device review |
| Existing functionality | Existing JVM suite | Pending physical-device regression pass |

## Publication procedure

1. Dispatch **Android Release (official)** on `arena/15cd6d1b-otter` with
   `publish=false`. Download the **Outter-28-signed-candidate** artifact. This is
   not an official GitHub release.
2. Install `Outter 28.apk` on representative Android devices. Verify every
   scenario above with permitted source media. Record source URLs (without private
   tokens), item counts, selected qualities, output hashes, audio/video tracks,
   actual playback/viewing, error recovery, OS/device, and tester identity. Keep
   copyrighted test outputs out of Git.
3. Fill `outter-28-verification.json` honestly. Calculate the source fingerprint
   with `python3 .github/scripts/verify_release_evidence.py --source-digest` and
   the tested APK hash with `sha256sum`. Every scenario needs `status: passed`
   and a meaningful evidence reference. A source change requires re-verification.
4. Publication requires this exact APK hash and source fingerprint, successful
   Android validation (including emulator tests), and the signed-APK install job.
   Until then `publish=true` fails closed rather than publishing an untested APK.
   If a rebuild changes the APK bytes, test that APK again; do not copy a prior
   sign-off onto different bytes.
5. Update the release notes from candidate status only after sign-off. Publish
   with the exact title **Outter 28**, tag `v28`, and universal asset
   `Outter 28.apk`, retaining ABI builds/AAB and SHA-256 checksums.
