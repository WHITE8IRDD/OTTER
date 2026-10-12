package com.otter.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.otter.app.R

fun Context.getClipboardText(): String? {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    return clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
}

/** Copies [text] to the clipboard and confirms it with a short toast. */
fun Context.copyToClipboard(text: String, label: String) {
    if (text.isBlank()) return
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(this, getString(R.string.copied_toast, label), Toast.LENGTH_SHORT).show()
}

fun Context.showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun Long.toReadableDuration(): String {
    if (this <= 0) return ""
    val hours = this / 3600
    val minutes = (this % 3600) / 60
    val seconds = this % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun String.isValidUrl(): Boolean {
    return try {
        val url = java.net.URL(this)
        url.protocol in listOf("http", "https")
    } catch (e: Exception) {
        false
    }
}

fun Long.toReadableFileSize(): String {
    return when {
        this > 1_073_741_824L -> "%.1f GB".format(this / 1_073_741_824.0)
        this > 1_048_576L -> "%.1f MB".format(this / 1_048_576.0)
        this > 1_024L -> "%.1f KB".format(this / 1_024.0)
        else -> "$this B"
    }
}

fun String.extractUrlFromText(): String? {
    val urlRegex = Regex("""https?://\S+""")
    return urlRegex.find(this)?.value
}
