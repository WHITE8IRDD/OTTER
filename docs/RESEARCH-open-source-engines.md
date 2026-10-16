# OTTER — New Perspective 0.1 · Open-source engine study (Phase 2)

Sources were read as checked-out revisions, not as blog posts:

| Project | Revision studied | Date | Licence |
|---|---|---|---|
| YTDLnis `deniscerri/ytdlnis` | `7cd926e4` (branch `master`) | 2026-10-16 | GPL-3.0 |
| Gabi `Hotaro26/gabi` (`kyoyacchi/gabi` is a fork of the same project) | `9a797c03` | 2026-09-30 | MIT |
| youtubedl-android `yausername/youtubedl-android` | tag `0.18.1` (the version OTTER pins) | 2025-11-16 | Unlicence/MIT |
| yt-dlp `yt-dlp/yt-dlp` | `2026.08.19` (current stable) | 2026-08-19 | Unlicence |

The directive named Gabi with only `https://github.com/` as its URL. It is identified here as
**`Hotaro26/gabi`** — "All in one media/image downloader app for android based on yt-dlp,
gallery-dl python library and newpipe extractor along with cobalt api instance" — which matches the
context (Android media downloader, cited next to YTDLnis) and is the project `kyoyacchi/gabi`
forks. Verified before drawing any conclusion: package `com.material.downloader`, README, `app/build.gradle.kts`.

## 1. What YTDLnis does that OTTER does not

Read from `app/src/main/java/com/deniscerri/ytdl/util/extractors/ytdlp/YTDLPUtil.kt`,
`core/RuntimeManager.kt`, `app/build.gradle`.

1. **One extraction, reused by every later step.** `getFormats()` asks for
   `--print "%(formats)j"` (plus `%(duration)s`) and, when allowed, first tries
   `--load-info-json <file>` if a saved info json is younger than **5 hours**, otherwise writes one
   with `--write-info-json`. The download step then reuses the same file. If the format list comes
   back empty the saved json is **deleted**, so a poisoned cache cannot stick.
   → OTTER already reuses the extraction (`YoutubeInfoCache`, 30 min) but never invalidates it after
   a failed download. Adopted: invalidate on failure (kept short-lived, since a shorter window is
   strictly safer against expired signature URLs).
2. **Cheap, quiet data-fetch defaults.** `--skip-download --quiet --ignore-errors --no-warnings
   -R 1 --compat-options manifest-filesize-approx --socket-timeout <user, default 5 s>`.
   `-R 1` (one network retry) matters: the *app* decides whether to retry, so the engine is not
   allowed to sit in a private retry ladder on top of the app's ladder. Adopted for the fetch
   request; the app-side ladder is now driven by an explicit retryability decision instead.
3. **Streaming JSON read instead of one giant payload.** they read the formats array with a lenient
   `JsonReader` off the process' stdout stream and return `List<Format>`; nothing is re-parsed.
   Adopted only in spirit (single parse, reuse of the parsed object) because OTTER's metadata
   surface (subtitles, audio tracks, hashtags, carousel) is built from the full info dict, and a
   streaming rewrite of `mapper.VideoInfo` was judged too large a change to land safely in a patch.
4. **Killable processes with real cancellation.** `RuntimeManager.runKillable { processId -> … }`
   cancels the coroutine *and* `destroyProcessById` (with `pstree` child cleanup, which is why
   0.17.4 exists upstream). OTTER passes a `processId` to `execute` but derives it from
   `url.hashCode().toUInt()` and only calls `YoutubeDL.terminateProcessWithId` on user cancel; a
   cancelled *fetch* leaves the engine process running to completion. Adopted: unique process ids
   (`OTTER-<url hash>-<seq>`) and terminate-on-cancel, which is the difference between "cancel
   worked" and "the UI moved on and Python kept downloading".
5. **JS runtime + PoToken for YouTube are explicit, user-visible settings**
   (`--js-runtimes node:…` / `deno:…` / `quickjs:…`, `--extractor-args youtube:player_client=…`,
   `po_token=…`, bgutil integration). Nothing is smuggled in silently, because those knobs are the
   only legitimate answer when YouTube changes its client requirements.
   → OTTER inherits `--js-runtimes quickjs:…` from the wrapper unconditionally (verified in
   `YoutubeDL.executeImpl`), so it has the solver for free and needs no client impersonation. The
   part worth adopting is the *maintenance* stance: a maintained engine that is refreshed
   automatically is worth more than any per-site hack. Adopted: `--extractor-args
   youtube:player_client=default` is **not** set (it would pin behaviour this project cannot test),
   but the auto-update path is restored (§3.2 of the audit) and the engine version is shown.
6. **Aria2c is optional and separated** (`aria2c:0.18.1` only for the flavour that wants it, and it
   is an *external downloader* — `-x`, `-s`, `--concurrent-fragments` tune the native one).
   Confirms OTTER's decision to drop aria2c (the ~70 MB of `.so` files) was correct.

## 2. What Gabi does that OTTER does not

Read from `app/src/androidMain/.../api/PythonExtractor.kt`, `api/CobaltRepository.kt`,
`app/src/main/python/downloader.py`, `ui/DownloaderViewModel.kt`, `app/build.gradle.kts`.

1. **A Python runtime (Chaquopy) with `yt-dlp` *and* `gallery-dl` installed**, plus a
   `newpipe-extractor` path and a **cobalt API instance** as a fourth engine. Gallery platforms
   (Instagram/Reddit/Pinterest/Twitter image sets) go through gallery-dl rather than hand-written
   page parsers, which is why Gabi can list a 20-slide sidecar without a browser.
2. **A `CookieSession` model**: the user's logged-in session is a first-class, per-host object that
   the engines are handed, rather than a single global `cookies.txt` that leaks into every request —
   the exact design flaw found in OTTER at §5 of the audit. Adopted in OTTER's minimal form: a
   per-host policy for the saved jar.
3. **"Smart preview" + instant/one-tap share actions**: metadata (title/author/thumbnail/size) is
   fetched for display *before* a download, and clipboard/share links start a job with one tap.
   Same split OTTER now formalises: extract → present → select → download.

## 3. Techniques chosen for OTTER, and why

| Technique | Source | Adopted? | Reason |
|---|---|---|---|
| `--load-info-json` reuse + delete-on-failure | YTDLnis | **yes** | removes a whole extraction from the "Preparing" wait and stops a stale cache sticking |
| `-R 1`, `--quiet`, `--socket-timeout` on the fetch request | YTDLnis | **yes** | stops the engine retrying under the app's own ladder |
| `--compat-options manifest-filesize-approx` | YTDLnis | **yes (fetch only)** | sizes are shown in the sheet; without it HLS/DASH report no size |
| Unique, killable process ids + terminate on cancel | YTDLnis | **yes** | cancellation is a stated requirement and OTTER's hash-based id collides |
| Per-host cookie policy | Gabi | **yes (policy, not UI)** | public pages must stay logged-out; see audit §5 |
| Streamed/one-pass JSON parse | YTDLnis | **partial** | single parse now; a full `--print` rewrite of the mapper path is a larger change |
| Chaquopy + gallery-dl as a second engine | Gabi | **no** | GPLv2 (gallery-dl) + a 40 MB+ Python payload, a second dependency graph and a second updater, for the one platform family where OTTER's own parser already works for photos; would also re-add what was deliberately removed as "duplicate extraction layers" |
| cobalt API instance | Gabi | **no** | shipping a third-party service URL as the extraction path is a privacy, availability and ToS question the user has not asked to take on |
| aria2c external downloader | both | **no** | ~70 MB APK growth for a speed win that `--concurrent-fragments` + `-N` mostly covers |
| `--js-runtimes`/PoToken impersonation knobs | YTDLnis | **no** | already provided by the pinned wrapper (QuickJS + bundled `yt_dlp_ejs`); impersonation is the part that breaks without warning |

Licence check for what *was* adopted: nothing was copied. Every adopted item is a command-line flag
or a policy that this repository implements itself, so no GPL/MIT source is linked or duplicated;
yt-dlp (Unlicence) and the wrapper remain the only upstream code, unchanged, with their own
licences intact and referenced in `README.md`.
