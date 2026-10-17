package com.otter.app.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Tests Android downloading, real decoding, native engine and FFmpeg without external sites. */
@RunWith(AndroidJUnit4::class)
class MediaPipelineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun withServer(block: (String) -> Unit) {
        val socket = ServerSocket(0)
        val executor = Executors.newCachedThreadPool()
        executor.submit {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    executor.submit {
                        client.use { c ->
                            val input = c.getInputStream().bufferedReader()
                            val request = input.readLine().orEmpty()
                            var line = input.readLine()
                            while (!line.isNullOrEmpty()) line = input.readLine()
                            val name = request.split(' ').getOrNull(1).orEmpty().substringAfterLast('/').substringBefore('?')
                            val bytes = instrumentation.context.assets.open("media/$name").use { it.readBytes() }
                            val mime = if (name.endsWith("png")) "image/png" else if (name.endsWith("m4a")) "audio/mp4" else "video/mp4"
                            c.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                if (!request.startsWith("HEAD")) write(bytes)
                                flush()
                            }
                        }
                    }
                } catch (_: java.net.SocketException) { break }
            }
        }
        try { block("http://127.0.0.1:${socket.localPort}") } finally { socket.close(); executor.shutdownNow() }
    }

    private fun assertPlayable(file: File, expectAudio: Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            assertTrue(mimes.any { it.startsWith("video/") })
            if (expectAudio) assertTrue(mimes.any { it.startsWith("audio/") })
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(0)
            assertNotNull("Android must decode an actual frame", frame)
            assertEquals(64, frame!!.width)
            frame.recycle()
        } finally { retriever.release() }
    }

    @Test fun mixedBatchProducesThreePicturesAndTwoPlayableVideos() = withServer { base ->
        val output = File(context.cacheDir, "batch-${System.nanoTime()}").apply { mkdirs() }
        try {
            val slides = (0..4).map { i -> MediaSlide("$base/$i/${if (i % 2 == 0) "picture.png" else "clip.mp4"}", i % 2 != 0) }
            val summary = runBlocking { Downloader(context).downloadMedia(slides, "mixed", output.path, {}) }
            assertEquals(0, summary.failedCount)
            assertEquals(5, summary.savedPaths.size)
            summary.savedPaths.forEachIndexed { i, path ->
                val file = File(path)
                assertEquals(if (i % 2 == 0) "png" else "mp4", file.extension)
                if (i % 2 == 0) {
                    val image = android.graphics.BitmapFactory.decodeFile(path)
                    assertNotNull(image)
                    image.recycle()
                } else assertPlayable(file, true)
            }
        } finally { output.deleteRecursively() }
    }

    @Test fun aPictureReturnedForAVideoIsAFailureNotSuccess() = withServer { base ->
        val output = File(context.cacheDir, "wrong-${System.nanoTime()}").apply { mkdirs() }
        try {
            val summary = runBlocking { Downloader(context).downloadMedia(listOf(MediaSlide("$base/picture.png", true)), "wrong", output.path, {}) }
            assertTrue(summary.savedPaths.isEmpty())
            assertEquals(1, summary.failedCount)
            assertTrue(output.listFiles().orEmpty().isEmpty())
        } finally { output.deleteRecursively() }
    }

    @Test fun bundledEngineAndFfmpegMergeSeparateAudioAndVideoIntoPlayableMp4() = withServer { base ->
        EngineRuntime.initialize(context)
        val output = File(context.cacheDir, "merge-${System.nanoTime()}").apply { mkdirs() }
        try {
            val info = File(output, "input.json")
            info.writeText("""{"id":"local-merge","title":"Synthetic merge","extractor":"generic",
                "webpage_url":"$base/clip.mp4","formats":[
                {"format_id":"v","url":"$base/video-only.mp4","ext":"mp4","protocol":"http","vcodec":"h264","acodec":"none","height":64,"width":64},
                {"format_id":"a","url":"$base/audio-only.m4a","ext":"m4a","protocol":"http","vcodec":"none","acodec":"aac"}]}""")
            val request = YoutubeDLRequest(emptyList<String>()).apply {
                addOption("--load-info-json", info.path)
                addOption("-f", "v+a")
                addOption("--merge-output-format", "mp4")
                addOption("-o", File(output, "merged.%(ext)s").path)
            }
            EngineRuntime.execute(request, EngineArgs.processId(base))
            assertPlayable(File(output, "merged.mp4"), true)
            val version = EngineRuntime.execute(YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }).out.trim()
            assertTrue("Unexpected engine $version", version >= "2026.08.19")
        } finally { output.deleteRecursively() }
    }
}
