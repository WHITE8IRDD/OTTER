package com.otter.app.util

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * OTTER writes downloads as plain files, so it needs a directory it can really
 * create and write. On Android 11+ the default `Download/Otter` path is only
 * writable with "All files access"; without it every download failed silently
 * and nothing appeared in the download folder. When the preferred folder is not
 * writable the app now falls back to its own external folder, which never needs
 * a permission, and tells the user about it.
 */
object StorageAccess {

    data class OutputTarget(val directory: String, val usedFallback: Boolean)

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun needsLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Preferred directory when writable, otherwise the app's own external folder. */
    fun resolveWritableDirectory(context: Context, preferred: String): OutputTarget {
        val candidate = preferred.trim().ifEmpty { fallbackDirectory(context) }
        if (isWritable(candidate)) return OutputTarget(candidate, usedFallback = false)
        val fallback = fallbackDirectory(context)
        val usable = if (isWritable(fallback)) fallback else internalFallback(context)
        return OutputTarget(usable, usedFallback = true)
    }

    fun isWritable(path: String): Boolean {
        return try {
            val directory = File(path)
            if (!directory.exists() && !directory.mkdirs()) return false
            if (!directory.isDirectory) return false
            val probe = File(directory, ".otter_write_probe")
            probe.writeText("ok", Charsets.UTF_8)
            val ok = probe.length() > 0
            probe.delete()
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun fallbackDirectory(context: Context): String {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File(base ?: context.filesDir, "Otter").absolutePath
    }

    private fun internalFallback(context: Context): String =
        File(context.filesDir, "Otter").apply { mkdirs() }.absolutePath
}
