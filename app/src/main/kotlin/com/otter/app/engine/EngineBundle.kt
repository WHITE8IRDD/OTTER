package com.otter.app.engine

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The extraction engine as an APK ships it, and as it has to be on disk.
 *
 * The engine file the wrapper runs is not "yt-dlp": it is a Python zipapp built by the wrapper's own
 * release process, holding `yt_dlp` *plus* `yt_dlp_ejs`, the JavaScript challenge solver package
 * (confirmed by unzipping the exact asset this app's pinned wrapper version bundles: `yt_dlp/`,
 * `yt_dlp_ejs/` and `__main__.py` sit side by side at the archive root). That second package is what
 * lets YouTube's `n`/`sig` challenges be solved by the QuickJS runtime the wrapper also ships.
 * Plain upstream `yt-dlp` has no such bundling step — it is one pure-Python package, and every
 * optional dependency (`yt_dlp_ejs` included, per `yt_dlp/dependencies/__init__.py`'s
 * `try { import X } except ImportError { X = None }` pattern) is detected at run time, not vendored
 * into the release asset. Swapping the wrapper's merged engine for that plain asset — which is
 * exactly what `YoutubeDL.updateYoutubeDL` does — therefore removes the solver package outright.
 * Upstream's own fallback (a vendored *core* script for exactly this situation, read from
 * `yt_dlp/extractor/youtube/jsc/_builtin/vendor/`) only ships the `core` script, not the `lib`
 * script a quickjs/node runtime also needs to run it, so the fallback cannot fully substitute for
 * the missing package either: the engine answers "No usable challenge solver … script available"
 * and every YouTube format that needs a solved URL disappears. That is why the automatic update
 * check added in the last release broke YouTube on every install's first Wi-Fi launch while leaving
 * Facebook, Instagram and browser downloads untouched — none of those extractors call a JS solver.
 *
 * So an update has to move *both* halves together. This object holds the archive rules that make
 * that possible, kept free of Android so they are unit tested (see `EngineBundleTest`):
 *
 *  * [inspect] reads an engine file and reports its yt-dlp version, the solver version it expects
 *    (`yt_dlp/extractor/youtube/jsc/_builtin/vendor/_info.py`) and whether the solver is present;
 *  * [merge] writes a new engine file from an upstream yt-dlp zipapp and a solver wheel, so the
 *    engine and its solver versions agree — yt-dlp rejects a solver whose version does not match
 *    its own (`version_tuple(script.version)[:2] != version_tuple(vendor.VERSION)[:2]`), so pairing
 *    is not optional.
 */
object EngineBundle {

    /** The Python package the challenge solver ships as, at the root of the zipapp. */
    const val SOLVER_PACKAGE = "yt_dlp_ejs/"

    private const val VERSION_ENTRY = "yt_dlp/version.py"
    private const val VENDOR_INFO_ENTRY = "yt_dlp/extractor/youtube/jsc/_builtin/vendor/_info.py"
    private const val SOLVER_VERSION_ENTRY = "yt_dlp_ejs/_version.py"
    private const val ZIP_MAGIC_OFFSET_SEARCH = 64 * 1024

    /**
     * Where the wrapper keeps the engine it actually runs.
     *
     * Built from the wrapper's own public constants (`YoutubeDL.baseName`, `.ytdlpDirName`,
     * `.ytdlpBin`) rather than a copy of their current values, so a future wrapper release that
     * changes this layout cannot silently point an update or a repair at the wrong file.
     */
    fun engineDir(appNoBackupDir: File): File =
        File(File(appNoBackupDir, com.yausername.youtubedl_android.YoutubeDL.baseName), com.yausername.youtubedl_android.YoutubeDL.ytdlpDirName)

    fun engineFile(appNoBackupDir: File): File =
        File(engineDir(appNoBackupDir), com.yausername.youtubedl_android.YoutubeDL.ytdlpBin)

    data class Info(
        /** yt-dlp's own version, e.g. `2026.08.19`. Blank when the file is not an engine at all. */
        val ytDlpVersion: String,
        /** The challenge-solver version this engine was built against, e.g. `0.8.0`. */
        val solverVersion: String,
        /** True when the zipapp already carries the solver package. */
        val hasSolver: Boolean,
        /** True when the engine expects an external solver (it has the EJS vendor manifest). */
        val needsSolver: Boolean
    ) {
        /** The condition an installed engine has to satisfy for YouTube extraction to work. */
        val isUsable: Boolean get() = ytDlpVersion.isNotBlank() && (!needsSolver || hasSolver)
    }

    /**
     * Reads what an engine file is, without unpacking it. Never throws.
     *
     * One pass over the archive: this runs at every app start, and a zipapp's entries are only
     * reachable in order, so asking for three files separately would mean reading the whole engine
     * three times to answer a question that costs a few hundred bytes.
     */
    fun inspect(file: File?): Info {
        if (file == null || !file.isFile) return Info("", "", false, false)
        var versionEntry: String? = null
        var vendorEntry: String? = null
        var solverEntry: String? = null
        var hasSolver = false
        runCatching {
            zipStream(file).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    when (entry.name) {
                        VERSION_ENTRY -> if (versionEntry == null) versionEntry = input.readText(entry)
                        VENDOR_INFO_ENTRY -> if (vendorEntry == null) vendorEntry = input.readText(entry)
                        SOLVER_VERSION_ENTRY ->
                            if (solverEntry == null) solverEntry = input.readText(entry)
                        else -> if (entry.name.startsWith(SOLVER_PACKAGE) && !entry.isDirectory) {
                            hasSolver = true
                        }
                    }
                    if (versionEntry != null && vendorEntry != null && (solverEntry != null || hasSolver)) {
                        input.closeEntry()
                        break
                    }
                    input.closeEntry()
                }
            }
        }
        val version = versionEntry?.let { ASSIGNMENT.firstMatchIn(it) }?.groupValues?.getOrNull(1).orEmpty()
        val solverExpected = vendorEntry?.let { ASSIGNMENT.firstMatchIn(it) }?.groupValues?.getOrNull(1).orEmpty()
        val solverInside = solverEntry?.let { ASSIGNMENT.firstMatchIn(it) }?.groupValues?.getOrNull(1).orEmpty()
        return Info(
            ytDlpVersion = version,
            solverVersion = solverExpected.ifBlank { solverInside },
            hasSolver = solverInside.isNotBlank() || hasSolver,
            needsSolver = vendorEntry != null
        )
    }

    private fun ZipInputStream.readText(entry: ZipEntry): String =
        if (entry.isDirectory) "" else readBytes().toString(Charsets.UTF_8)

    /** True when the file has an entry with this name (or, for a trailing `/`, under this prefix). */
    fun hasEntry(file: File, nameOrPrefix: String): Boolean = runCatching {
        zipStream(file).use { input ->
            while (true) {
                val entry = input.nextEntry ?: return@use false
                val name = entry.name
                input.closeEntry()
                if (name == nameOrPrefix ||
                    (nameOrPrefix.endsWith("/") && name.startsWith(nameOrPrefix) && !name.endsWith("/"))
                ) return@use true
            }
            false
        }
    }.getOrDefault(false)

    fun readTextEntry(file: File, name: String): String? = runCatching {
        zipStream(file).use { input ->
            while (true) {
                val entry = input.nextEntry ?: return@use null
                if (entry.name == name && !entry.isDirectory) {
                    return@use input.readBytes().toString(Charsets.UTF_8)
                }
                input.closeEntry()
            }
            null
        }
    }.getOrNull()

    /**
     * Writes `out` = [base] with `yt_dlp_ejs` replaced by the copy inside [overlay].
     *
     * [base] is a plain upstream engine (a zipapp, sometimes with a `#!` line in front of the zip —
     * that prefix is preserved, and dropped when [keepPrefix] is false so a caller can try the other
     * shape if an interpreter disagrees with it). [overlay] is a Python wheel: only the solver
     * package is taken from it, never the packaging metadata.
     *
     * @return the number of entries written, or -1 when the base archive could not be read.
     */
    fun merge(base: File, overlay: File?, out: File, keepPrefix: Boolean = true): Int {
        val prefix = if (keepPrefix) readPrefix(base) else ByteArray(0)
        val solverEntries = overlay?.let { collectSolverEntries(it) }.orEmpty()
        var written = 0
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { fileOut ->
            if (prefix.isNotEmpty()) fileOut.write(prefix)
            BufferedInputStream(FileInputStream(base)).use { source ->
                skipToZipStart(source)
                ZipInputStream(source).use { input ->
                    ZipOutputStream(fileOut).use { output ->
                        while (true) {
                            val entry = input.nextEntry ?: break
                            // The solver comes from the overlay only, so an old copy can never
                            // shadow the version that matches this engine.
                            if (entry.name.startsWith(SOLVER_PACKAGE)) {
                                input.closeEntry()
                                continue
                            }
                            val copy = ZipEntry(entry.name)
                            if (entry.isDirectory) {
                                output.putNextEntry(copy)
                                output.closeEntry()
                            } else {
                                copy.method = ZipEntry.DEFLATED
                                output.putNextEntry(copy)
                                input.copyTo(output)
                                output.closeEntry()
                            }
                            written++
                        }
                        solverEntries.forEach { (name, bytes) ->
                            val copy = ZipEntry(name)
                            copy.method = ZipEntry.DEFLATED
                            output.putNextEntry(copy)
                            output.write(bytes)
                            output.closeEntry()
                            written++
                        }
                    }
                }
            }
        }
        return if (written == 0) -1 else written
    }

    /** The solver package of a wheel (or of another zipapp), as name → bytes. */
    private fun collectSolverEntries(source: File): List<Pair<String, ByteArray>> = runCatching {
        val out = mutableListOf<Pair<String, ByteArray>>()
        zipStream(source).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name
                if (name.startsWith(SOLVER_PACKAGE) && !entry.isDirectory &&
                    !name.contains("__pycache__") && !name.endsWith(".pyc")
                ) {
                    out += name to input.readBytes()
                }
                input.closeEntry()
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * yt-dlp's own pairing rule: a challenge solver is used only when its first two version
     * components equal the ones the engine recorded (`version_tuple(script.version)[:2] !=
     * version_tuple(vendor.VERSION)[:2]` skips it with a warning). Installing an engine with an
     * unmatched solver is therefore the same as installing it with none, which is why this is
     * checked before anything is written to disk.
     */
    fun solverVersionsCompatible(engine: String, solver: String): Boolean {
        if (engine.isBlank() || solver.isBlank()) return false
        return majorMinor(engine) == majorMinor(solver)
    }

    fun majorMinor(version: String): String = version.split('.').take(2).joinToString(".")

    /** The solver version a wheel declares, so it can be checked against what the engine expects. */
    fun overlaySolverVersion(overlay: File?): String =
        overlay?.let { readTextEntry(it, SOLVER_VERSION_ENTRY) }
            ?.let { ASSIGNMENT.firstMatchIn(it)?.groupValues?.getOrNull(1) }.orEmpty()

    private val ASSIGNMENT = Regex("""(?:VERSION|__version__|version)\s*=\s*['"]([^'"]+)['"]""")

    /**
     * A stream positioned at the start of the zip data, i.e. after any `#!` line a zipapp carries.
     * Java's zip readers start at byte 0, so a self-executing header has to be skipped by hand.
     */
    private fun zipStream(file: File): ZipInputStream {
        val input = BufferedInputStream(FileInputStream(file), 64 * 1024)
        skipToZipStart(input)
        return ZipInputStream(input)
    }

    private fun skipToZipStart(input: BufferedInputStream) {
        input.mark(ZIP_MAGIC_OFFSET_SEARCH)
        val window = ByteArray(ZIP_MAGIC_OFFSET_SEARCH)
        var read = 0
        while (read < window.size) {
            val n = input.read(window, read, window.size - read)
            if (n < 0) break
            read += n
        }
        input.reset()
        val offset = indexOfZipStart(window, read)
        if (offset > 0) input.skip(offset.toLong())
    }

    private fun indexOfZipStart(bytes: ByteArray, limit: Int): Int {
        val end = minOf(limit - 4, bytes.size - 4)
        for (i in 0..end) {
            if (bytes[i] == 'P'.code.toByte() && bytes[i + 1] == 'K'.code.toByte() &&
                bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()
            ) return i
            if (bytes[i] == 'P'.code.toByte() && bytes[i + 1] == 'K'.code.toByte() &&
                bytes[i + 2] == 5.toByte() && bytes[i + 3] == 6.toByte()
            ) return i
        }
        return 0
    }

    /** The bytes before the zip data (the `#!` line of a zipapp), verbatim. */
    fun readPrefix(file: File): ByteArray {
        runCatching {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(ZIP_MAGIC_OFFSET_SEARCH)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                val offset = indexOfZipStart(buffer, read)
                if (offset < 0) return ByteArray(0)
                return buffer.copyOf(offset)
            }
        }
        return ByteArray(0)
    }

    /** True when the file looks like a Python zipapp holding yt-dlp (used to validate a download). */
    fun looksLikeEngine(file: File): Boolean = file.isFile && hasEntry(file, VERSION_ENTRY)

    /** Reads a whole small response into memory, capped so a bad endpoint cannot exhaust the heap. */
    internal fun readCapped(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw java.io.IOException("response larger than $maxBytes bytes")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
