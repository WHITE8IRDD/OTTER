package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The rules that decide whether an extraction engine may be installed.
 *
 * These tests are the reason the update path can be trusted at all: they build an engine archive
 * the same shape the wrapper ships (a `#!` line in front of a zipapp holding `yt_dlp`), strip the
 * challenge solver out of it, and assert that (a) the strip is *detected* as unusable, (b) merging
 * a solver back in makes it usable again, and (c) a solver of the wrong version is never accepted.
 * A release whose YouTube breakage came from exactly that strip would otherwise be verified only by
 * a phone.
 */
class EngineBundleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(
        file: File,
        entries: Map<String, String>,
        prefix: String = ""
    ): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            if (prefix.isNotEmpty()) out.write(prefix.toByteArray(Charsets.UTF_8))
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, body) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(body.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    private fun engineEntries(solverVersion: String? = "0.3.1"): Map<String, String> {
        val version = "yt_dlp" to """__version__ = '2025.11.12'"""
        val entries = linkedMapOf(
            "yt_dlp/version.py" to version.second,
            "yt_dlp/__init__.py" to "# engine",
            "__main__.py" to "import yt_dlp\nyt_dlp.main()",
            "yt_dlp/extractor/youtube/jsc/_builtin/vendor/_info.py" to
                "# generated\nVERSION = '0.3.1'\nHASHES = {}\n"
        )
        if (solverVersion != null) {
            entries["yt_dlp_ejs/_version.py"] = "__version__ = version = '$solverVersion'"
            entries["yt_dlp_ejs/yt/solver/lib.min.js"] = "// solver"
        }
        return entries
    }

    @Test
    fun `reads the engine and solver versions out of a zipapp with a shebang line`() {
        val file = zip(File(temp.root, "engine/yt-dlp"), engineEntries(), prefix = "#!/usr/bin/env python3\n")
        val info = EngineBundle.inspect(file)
        assertEquals("2025.11.12", info.ytDlpVersion)
        assertEquals("0.3.1", info.solverVersion)
        assertTrue("the solver inside the archive must be found", info.hasSolver)
        assertTrue("an engine with the EJS manifest needs a solver", info.needsSolver)
        assertTrue("a complete engine is usable", info.isUsable)
        assertEquals("#!/usr/bin/env python3\n", String(EngineBundle.readPrefix(file), Charsets.UTF_8))
        assertTrue(EngineBundle.looksLikeEngine(file))
    }

    @Test
    fun `an engine without its solver is reported unusable - the state that broke YouTube`() {
        val broken = zip(
            File(temp.root, "engine/yt-dlp"),
            engineEntries(solverVersion = null)
        )
        val info = EngineBundle.inspect(broken)
        assertEquals("2025.11.12", info.ytDlpVersion)
        assertFalse("no solver package must be read as missing, not as fine", info.hasSolver)
        assertTrue(info.needsSolver)
        assertFalse("this is the archive an update to the plain upstream asset leaves behind", info.isUsable)
    }

    @Test
    fun `merging a solver wheel back in produces a usable engine and drops packaging metadata`() {
        val base = zip(File(temp.root, "in/upstream"), engineEntries(solverVersion = null))
        val wheel = zip(
            File(temp.root, "in/ejs.whl"),
            mapOf(
                "yt_dlp_ejs/_version.py" to "__version__ = version = '0.3.1'",
                "yt_dlp_ejs/yt/solver/lib.min.js" to "// solver",
                "yt_dlp_ejs-0.3.1.dist-info/METADATA" to "Metadata-Version: 2.1"
            )
        )
        val out = File(temp.root, "out/merged")
        val written = EngineBundle.merge(base, wheel, out)
        assertTrue("entries were copied", written > 0)
        val info = EngineBundle.inspect(out)
        assertTrue(info.hasSolver)
        assertTrue(info.isUsable)
        assertEquals("2025.11.12", info.ytDlpVersion)

        val names = mutableListOf<String>()
        ZipInputStream(out.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                names += entry.name
                input.closeEntry()
            }
        }
        assertTrue(names.contains("yt_dlp_ejs/yt/solver/lib.min.js"))
        assertTrue("the engine's own files survive the merge", names.contains("yt_dlp/__init__.py"))
        assertTrue("the shebang is preserved when asked for", names.isNotEmpty())
        assertFalse(
            "packaging metadata from the wheel must not be installed as code",
            names.any { it.contains("dist-info") }
        )
        assertEquals(String(EngineBundle.readPrefix(base), Charsets.UTF_8), String(EngineBundle.readPrefix(out), Charsets.UTF_8))
    }

    @Test
    fun `a merge without the prefix still opens as an archive`() {
        val base = zip(File(temp.root, "in/upstream2"), engineEntries(solverVersion = null), prefix = "#!/usr/bin/env python3\n")
        val out = File(temp.root, "out/merged-nopfx")
        assertTrue(EngineBundle.merge(base, null, out, keepPrefix = false) > 0)
        assertEquals(0, EngineBundle.readPrefix(out).size)
        // Every entry is still readable, i.e. the offsets Java wrote line up with the archive start.
        val names = mutableListOf<String>()
        ZipInputStream(out.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                names += entry.name
                input.closeEntry()
            }
        }
        assertTrue(names.contains("yt_dlp/version.py"))
    }

    @Test
    fun `an older solver inside the base is replaced rather than shadowed`() {
        val base = zip(File(temp.root, "in/old"), engineEntries(solverVersion = "0.1.0"))
        val wheel = zip(
            File(temp.root, "in/new.whl"),
            mapOf("yt_dlp_ejs/_version.py" to "__version__ = version = '0.3.1'")
        )
        val out = File(temp.root, "out/merged-replaced")
        EngineBundle.merge(base, wheel, out)
        assertEquals("0.3.1", EngineBundle.overlaySolverVersion(out))
    }

    @Test
    fun `solver versions must match the engine to two components`() {
        assertTrue(EngineBundle.solverVersionsCompatible("0.3.1", "0.3.9"))
        assertTrue(EngineBundle.solverVersionsCompatible("0.8.0", "0.8.0"))
        assertFalse("0.3 vs 0.8 is skipped by the engine at run time", EngineBundle.solverVersionsCompatible("0.8.0", "0.3.1"))
        assertFalse(EngineBundle.solverVersionsCompatible("", "0.3.1"))
        assertFalse(EngineBundle.solverVersionsCompatible("0.3.1", ""))
        assertEquals("0.8", EngineBundle.majorMinor("0.8.0"))
    }

    @Test
    fun `a file that is not an engine is not mistaken for one`() {
        val notAnEngine = zip(File(temp.root, "junk/yt-dlp"), mapOf("README" to "hello"))
        assertFalse(EngineBundle.looksLikeEngine(notAnEngine))
        val info = EngineBundle.inspect(notAnEngine)
        assertEquals("", info.ytDlpVersion)
        assertFalse(info.needsSolver)
        assertFalse(info.isUsable)
        assertFalse(EngineBundle.inspect(null).isUsable)
        assertFalse(EngineBundle.inspect(File(temp.root, "missing")).isUsable)
    }
}
