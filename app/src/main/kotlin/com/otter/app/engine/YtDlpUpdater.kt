package com.otter.app.engine

import android.content.Context
import android.util.Log
import com.otter.app.R
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the extraction engine current *and* complete.
 *
 * The engine the APK ships is a Python zipapp holding `yt_dlp` plus the `yt_dlp_ejs` challenge
 * solver (see [EngineBundle] for why both halves are needed). The previous release wired the
 * automatic update to the wrapper's own `updateYoutubeDL`, which replaces that file with the plain
 * upstream asset — an engine without a solver. YouTube is the only thing in this app that needs the
 * solver, so the update brought other sites' extractors up to date and broke YouTube for everyone
 * whose automatic pass ran, which is the state this class now has to heal as well as prevent.
 *
 * Two entry points, one rule — **an engine is installed only if it can solve challenges**:
 *
 *  * [update] fetches the newest yt-dlp build for the channel and pairs it with the solver release
 *    whose version that build names, so the two agree;
 *  * [ensureSolver] repairs an installed engine that lost its solver (what the previous release left
 *    behind) and restores the APK's own known-good build when repairing is not possible.
 *
 * Both write through a staged file, prove the result by running the engine once, and put the
 * previous file back if it does not start. A failed update therefore costs a download, not the app.
 */
class YtDlpUpdater(private val context: Context) {

    companion object {
        private const val TAG = "YtDlpUpdater"
        private const val UPDATE_INTERVAL_MS = 86_400_000L // 24 hours
        private const val PREF_LAST_UPDATE = "ytdlp_last_update_time"
        private const val PREF_INSTALLED_VERSION = "ytdlp_installed_version"
        /** The wrapper's own preference keys, so its `version()` and this app agree. */
        private const val WRAPPER_PREFS = "youtubedl-android"
        private const val WRAPPER_KEY_VERSION = "dlpVersion"
        private const val WRAPPER_KEY_VERSION_NAME = "dlpVersionName"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_ENGINE_BYTES = 64L * 1024 * 1024
        private const val MAX_WHEEL_BYTES = 16L * 1024 * 1024
        private const val MAX_JSON_BYTES = 512L * 1024
        private const val UA = "Otter/2 (Android media downloader)"

        /** The pairing rule lives in [EngineBundle] so it can be tested without Android. */
        fun solverVersionsCompatible(engine: String, solver: String): Boolean =
            EngineBundle.solverVersionsCompatible(engine, solver)
    }

    sealed class UpdateResult {
        object AlreadyUpToDate : UpdateResult()
        data class Updated(val version: String) : UpdateResult()
        data class Failed(val error: String) : UpdateResult()
    }

    sealed class HealResult {
        /** The installed engine is complete; nothing to do. */
        object NotNeeded : HealResult()

        /** No engine file has been laid out yet — the APK's own build will be used. */
        object NoInstalledEngine : HealResult()

        /** The solver was added to the installed engine. */
        data class Repaired(val version: String) : HealResult()

        /** The solver could not be obtained, so the APK's known-good build was put back. */
        data class RestoredBundled(val version: String) : HealResult()

        /** Neither repair was possible; [reason] is what the user should see. */
        data class Unrepaired(val reason: String) : HealResult()
    }

    /** An update or a repair rewrites the binary every download uses; never run two at once. */
    private val busy = AtomicBoolean(false)

    private val engineFile: File get() = EngineBundle.engineFile(context.noBackupFilesDir)

    // ─── public API ────────────────────────────────────────────────────────────────────────

    /**
     * Installs the newest engine for [channel] together with the challenge solver it expects.
     *
     * @param channel unchanged from before this fix: the Settings button still asks for the newest
     *   thing that exists (nightly) and the automatic background pass still asks only for an
     *   official release (its own call site passes `STABLE` explicitly). A nightly engine whose
     *   solver release has not been published yet now fails with a clear reason instead of being
     *   installed without one - which is strictly safer than what either channel did before.
     */
    suspend fun update(
        onProgress: (String) -> Unit = {},
        channel: YoutubeDL.UpdateChannel = YoutubeDL.UpdateChannel.NIGHTLY
    ): UpdateResult = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) {
            return@withContext UpdateResult.Failed(context.getString(R.string.update_busy))
        }
        try {
            onProgress(context.getString(R.string.update_checking))
            val release = fetchRelease(channel.apiUrl)
                ?: return@withContext UpdateResult.Failed(
                    context.getString(R.string.update_release_lookup_failed)
                )
            val tag = release.optString("tag_name")
            val current = EngineBundle.inspect(engineFile)
            if (tag.isNotBlank() && tag == current.ytDlpVersion) {
                // Same engine version. If it is missing its solver, pairing is still owed.
                if (current.isUsable) {
                    saveUpdateTime()
                    return@withContext UpdateResult.AlreadyUpToDate
                }
                return@withContext when (val healed = healLocked(current)) {
                    is HealResult.Repaired -> UpdateResult.Updated("healed ${healed.version}")
                    is HealResult.RestoredBundled -> UpdateResult.Updated("restored ${healed.version}")
                    is HealResult.Unrepaired -> UpdateResult.Failed(healed.reason)
                    else -> UpdateResult.AlreadyUpToDate
                }
            }
            val assetUrl = pickAsset(release, "yt-dlp")
                ?: return@withContext UpdateResult.Failed(context.getString(R.string.update_no_asset))

            val work = File(context.cacheDir, "engine-update")
            runCatching { work.deleteRecursively() }
            val staged = File(work, "upstream")
            val downloadError = download(assetUrl, staged, MAX_ENGINE_BYTES)
            if (downloadError != null) {
                runCatching { work.deleteRecursively() }
                return@withContext UpdateResult.Failed(downloadError)
            }
            if (!EngineBundle.looksLikeEngine(staged)) {
                runCatching { work.deleteRecursively() }
                return@withContext UpdateResult.Failed(context.getString(R.string.update_bad_download))
            }

            // Pair the solver *before* anything on disk changes: an engine without it is the exact
            // state that broke YouTube, so it must never be installed, not even briefly.
            val incoming = EngineBundle.inspect(staged)
            val candidates = if (incoming.needsSolver) {
                val solverVersion = incoming.solverVersion
                if (solverVersion.isBlank()) {
                    runCatching { work.deleteRecursively() }
                    return@withContext UpdateResult.Failed(context.getString(R.string.update_solver_unknown))
                }
                val wheel = File(work, "ejs.whl")
                val wheelUrl = solverWheelUrl(solverVersion)
                val wheelError = wheelUrl?.let { download(it, wheel, MAX_WHEEL_BYTES) }
                val wheelVersion = if (wheelError == null && wheel.isFile) {
                    EngineBundle.overlaySolverVersion(wheel)
                } else {
                    ""
                }
                if (wheelUrl == null || wheelError != null ||
                    !solverVersionsCompatible(solverVersion, wheelVersion)
                ) {
                    runCatching { work.deleteRecursively() }
                    return@withContext UpdateResult.Failed(
                        context.getString(R.string.update_solver_missing, solverVersion)
                    )
                }
                listOf(
                    File(work, "merged-p"),
                    File(work, "merged-n")
                ).mapNotNull { candidate ->
                    val written = runCatching {
                        EngineBundle.merge(staged, wheel, candidate, keepPrefix = candidate.name.endsWith("-p"))
                    }.getOrDefault(-1)
                    candidate.takeIf { written > 0 }
                }
            } else {
                listOf(staged)
            }

            val installed = installCandidates(candidates)
            runCatching { work.deleteRecursively() }
            if (installed == null) {
                return@withContext UpdateResult.Failed(context.getString(R.string.update_smoke_failed))
            }
            saveUpdateTime()
            context.getSharedPreferences(WRAPPER_PREFS, Context.MODE_PRIVATE).edit()
                .putString(WRAPPER_KEY_VERSION, tag)
                .putString(WRAPPER_KEY_VERSION_NAME, release.optString("name"))
                .apply()
            Log.i(TAG, "engine installed: ${installed.ytDlpVersion} (solver ${installed.solverVersion})")
            UpdateResult.Updated(installed.ytDlpVersion.ifBlank { tag })
        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            UpdateResult.Failed(e.message ?: context.getString(R.string.update_unknown_error))
        } finally {
            busy.set(false)
        }
    }

    /**
     * Puts a broken engine right, if the installed one cannot solve challenges.
     *
     * Called at startup, because the damage the previous release did is precisely this: an engine
     * file on disk with no solver inside, which every later run keeps using. Repairing means adding
     * the solver version that engine asks for; when the network will not allow that, the APK's own
     * build is restored instead, which is a complete (if older) engine.
     */
    suspend fun ensureSolver(): HealResult = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) return@withContext HealResult.Unrepaired("busy")
        try {
            healLocked(EngineBundle.inspect(engineFile))
        } catch (e: Exception) {
            Log.w(TAG, "Engine check failed", e)
            HealResult.Unrepaired(e.message ?: "engine check failed")
        } finally {
            busy.set(false)
        }
    }

    /** The repair itself. The caller holds [busy]. */
    private fun healLocked(installed: EngineBundle.Info): HealResult {
        val file = engineFile
        if (!file.isFile) return HealResult.NoInstalledEngine
        if (installed.ytDlpVersion.isBlank()) {
            // Not a readable engine at all (a truncated update, say): start from the APK's copy.
            return restoreBundled()
                ?: HealResult.Unrepaired(context.getString(R.string.update_restore_failed))
        }
        if (installed.isUsable) return HealResult.NotNeeded

        val solverVersion = installed.solverVersion
        if (solverVersion.isNotBlank()) {
            val wheelUrl = solverWheelUrl(solverVersion)
            if (wheelUrl != null) {
                val work = File(context.cacheDir, "engine-repair")
                runCatching { work.deleteRecursively() }
                val wheel = File(work, "ejs.whl")
                val error = download(wheelUrl, wheel, MAX_WHEEL_BYTES)
                // The same pairing check `update()` applies before a merge: the release and the
                // wheel filename both encode the target version, but nothing stops a mirror or a
                // renamed asset from disagreeing with its own contents, and that disagreement is
                // exactly the failure mode this whole class exists to prevent.
                val wheelVersion = if (error == null && wheel.isFile) {
                    EngineBundle.overlaySolverVersion(wheel)
                } else {
                    ""
                }
                if (error == null && solverVersionsCompatible(solverVersion, wheelVersion)) {
                    val candidates = listOf(File(work, "merged-p"), File(work, "merged-n")).mapNotNull { out ->
                        val written = runCatching {
                            EngineBundle.merge(file, wheel, out, keepPrefix = out.name.endsWith("-p"))
                        }.getOrDefault(-1)
                        out.takeIf { written > 0 }
                    }
                    if (installCandidates(candidates) != null) {
                        runCatching { work.deleteRecursively() }
                        Log.i(TAG, "engine solver restored for ${installed.ytDlpVersion}")
                        return HealResult.Repaired(installed.ytDlpVersion)
                    }
                }
                runCatching { work.deleteRecursively() }
            }
        }
        return restoreBundled()
            ?: HealResult.Unrepaired(context.getString(R.string.update_solver_missing, solverVersion))
    }

    /** True when the daily automatic pass should run. */
    fun shouldAutoUpdate(): Boolean {
        val lastUpdate = context.getSharedPreferences("ytdlp_prefs", Context.MODE_PRIVATE)
            .getLong(PREF_LAST_UPDATE, 0L)
        return System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL_MS
    }

    fun getVersion(): String {
        val fromWrapper = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
        return fromWrapper ?: installedVersion() ?: "Unknown"
    }

    // ─── internals ─────────────────────────────────────────────────────────────────────────

    /**
     * Copies a staged engine into place behind a smoke test and returns the first candidate that
     * starts; when none does, the previous file is put back (or the APK's build re-extracted).
     *
     * The wrapper runs the engine as `libpython.so <file>`, so the file is imported as a zipapp. A
     * zipapp may carry a `#!` line in front of the archive; whether that prefix is wanted depends on
     * how the archive is read, so both shapes are offered and the first one that starts wins.
     */
    private fun installCandidates(candidates: List<File>): EngineBundle.Info? {
        val target = engineFile
        val backup = File(target.parentFile, "${target.name}.bak")
        val hadPrevious = target.isFile
        if (hadPrevious) runCatching { target.copyTo(backup, overwrite = true) }

        for (candidate in candidates) {
            val info = EngineBundle.inspect(candidate)
            if (!info.isUsable) {
                Log.w(TAG, "candidate unusable (${candidate.name}): $info")
                continue
            }
            val copied = runCatching {
                target.parentFile?.mkdirs()
                candidate.copyTo(target, overwrite = true)
                target.isFile && target.length() == candidate.length()
            }.getOrDefault(false)
            if (!copied) {
                Log.w(TAG, "could not copy ${candidate.name} into place")
                continue
            }
            if (smokeTest() == null) {
                runCatching { backup.delete() }
                return EngineBundle.inspect(target)
            }
        }

        if (hadPrevious && backup.isFile) {
            runCatching { backup.copyTo(target, overwrite = true) }
            runCatching { backup.delete() }
        } else {
            restoreBundled()
        }
        return null
    }

    /** Re-extracts the engine the APK shipped, which is complete by construction. */
    private fun restoreBundled(): HealResult? {
        val dir = EngineBundle.engineDir(context.noBackupFilesDir)
        runCatching { dir.deleteRecursively() }
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return try {
            YoutubeDL.getInstance().init_ytdlp(context, dir)
            val info = EngineBundle.inspect(File(dir, "yt-dlp"))
            saveUpdateTime()
            Log.i(TAG, "engine restored to the build the APK ships (${info.ytDlpVersion})")
            HealResult.RestoredBundled(info.ytDlpVersion.ifBlank { "bundled" })
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore the bundled engine", e)
            null
        }
    }

    /** Runs the engine once. Null means it started and answered. */
    private fun smokeTest(): String? {
        return try {
            YoutubeDL.getInstance().init(context)
            val request = YoutubeDLRequest("").apply { addOption("--version") }
            val out = runCatching { YoutubeDL.getInstance().execute(request).out }.getOrNull()
            val version = out?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty()
            if (version.isBlank()) {
                "the engine did not start after the update"
            } else {
                context.getSharedPreferences("ytdlp_prefs", Context.MODE_PRIVATE).edit()
                    .putString(PREF_INSTALLED_VERSION, version)
                    .apply()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "smoke test failed", e)
            e.message ?: "the engine could not be run"
        }
    }

    private fun installedVersion(): String? =
        context.getSharedPreferences("ytdlp_prefs", Context.MODE_PRIVATE)
            .getString(PREF_INSTALLED_VERSION, null)

    private fun fetchRelease(apiUrl: String): JSONObject? = runCatching {
        JSONObject(getText(apiUrl) ?: return@runCatching null)
    }.onFailure { Log.w(TAG, "Release lookup failed: ${it.message}") }.getOrNull()

    /**
     * The published wheel for the solver release [version] names.
     *
     * `yt-dlp/ejs` tags its releases exactly as yt-dlp's vendored `vendor.VERSION`, so the pairing
     * is a lookup rather than a guess; when the release or the wheel is missing, null comes back and
     * the caller keeps the engine it has.
     */
    private fun solverWheelUrl(version: String): String? {
        val release = runCatching {
            JSONObject(getText("https://api.github.com/repos/yt-dlp/ejs/releases/tags/$version") ?: "")
        }.getOrNull() ?: return null
        return pickAsset(release, "yt_dlp_ejs-$version-py3-none-any.whl")
    }

    private fun pickAsset(release: JSONObject, assetName: String): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == assetName) {
                return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun getText(url: String): String? = runCatching {
        val connection = open(url)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "GET $url -> HTTP $status")
                return null
            }
            connection.inputStream.use {
                String(EngineBundle.readCapped(it, MAX_JSON_BYTES), Charsets.UTF_8)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }.onFailure { Log.w(TAG, "GET $url failed: ${it.message}") }.getOrNull()

    /** Downloads to [target]; returns a message when it could not be done, null on success. */
    private fun download(url: String, target: File, maxBytes: Long): String? {
        return try {
            target.parentFile?.mkdirs()
            val connection = open(url)
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    Log.w(TAG, "download $url -> HTTP $status")
                    return "HTTP $status"
                }
                connection.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) return "the download was larger than expected"
                            out.write(buffer, 0, read)
                        }
                    }
                }
                if (!target.isFile || target.length() == 0L) "empty download" else null
            } finally {
                runCatching { connection.disconnect() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.message}")
            e.message ?: "download failed"
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
        }

    private fun saveUpdateTime() {
        context.getSharedPreferences("ytdlp_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }
}
