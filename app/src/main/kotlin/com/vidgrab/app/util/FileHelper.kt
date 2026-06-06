package com.vidgrab.app.util

import android.content.Context
import android.content.Intent

import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileHelper {

    private const val TAG = "FileHelper"

    fun openFile(context: Context, filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = getMimeType(file.extension)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file: $filePath", e)
        }
    }

    fun locateFile(context: Context, filePath: String) {
        val folder = try {
            val f = File(filePath)
            if (!f.exists()) {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            f.parentFile ?: run {
                Toast.makeText(context, "Cannot locate folder", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve parent for: $filePath", e)
            Toast.makeText(context, "Could not open file location", Toast.LENGTH_SHORT).show()
            return
        }

        val relativePath = folder.absolutePath
            .removePrefix("/storage/emulated/0/")
            .removePrefix("/sdcard/")

        // Attempt 1: file:// URI (works on MIUI file explorer and many others)
        try {
            val fileUri = Uri.parse("file://${folder.absolutePath}")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = fileUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "locateFile: file:// URI succeeded")
            return
        } catch (e: Exception) {
            Log.i(TAG, "locateFile: file:// URI failed", e)
        }

        // Attempt 2: Document URI with directory MIME
        if (relativePath.isNotEmpty()) {
            try {
                val docUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:$relativePath"
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "locateFile: document URI succeeded")
                return
            } catch (e: Exception) {
                Log.i(TAG, "locateFile: document URI failed", e)
            }
        }

        // Attempt 3: Tree URI (shows files app or system picker)
        if (relativePath.isNotEmpty()) {
            try {
                val treeUri = DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:$relativePath"
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = treeUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "locateFile: tree URI succeeded")
                return
            } catch (e: Exception) {
                Log.i(TAG, "locateFile: tree URI failed", e)
            }
        }

        Log.w(TAG, "All locate attempts failed for: $filePath")
        Toast.makeText(context, "Could not open file location", Toast.LENGTH_SHORT).show()
    }

    fun shareFile(context: Context, filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = getMimeType(file.extension)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share file: $filePath", e)
        }
    }

    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            false
        }
    }

    fun getFileSizeBytes(filePath: String): Long {
        return try {
            File(filePath).length()
        } catch (e: Exception) {
            0L
        }
    }

    fun fileExists(filePath: String): Boolean {
        return File(filePath).exists()
    }

    fun scanFile(context: Context, filePath: String) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan file: $filePath", e)
        }
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            else -> "*/*"
        }
    }
}
