package com.otter.app.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records whether the yt-dlp engine has been used during this process.
 *
 * The nightly yt-dlp update pulls tens of megabytes. It runs from the application
 * startup coroutine, so without this it happily shares the connection with whatever
 * the user pasted a moment after opening the app, and the first download of the
 * session crawls for no visible reason. Background work asks [wasUsed] and waits for
 * the next launch instead.
 */
object EngineLock {

    private val used = AtomicBoolean(false)

    /** True once a fetch or a download has run in this process. */
    val wasUsed: Boolean get() = used.get()

    /** Called at the start of every fetch and every download. */
    fun markUsed() {
        used.set(true)
    }
}
