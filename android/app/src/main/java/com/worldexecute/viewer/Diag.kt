package com.worldexecute.viewer

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-memory log.
 *
 * The film talks to its host through `console.log` and `console.error`, which
 * on a phone go somewhere nobody can read. Forwarding them here gives the app a
 * "开发者日志" screen, which is the difference between "it didn't work" and a
 * line number.
 *
 * A ring buffer, not a file: this is for reading on the screen you are already
 * holding, and it must never grow.
 */
object Diag {

    private const val TAG = "worldexecute"
    private const val MAX = 400

    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, message: String) {
        synchronized(lines) {
            val line = stamp.format(Date()) + "  [" + tag + "] " + message
            Log.i(TAG, line)
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    fun dump(): String = synchronized(lines) {
        if (lines.isEmpty()) "（还没有日志）" else lines.joinToString("\n")
    }

    fun count(): Int = synchronized(lines) { lines.size }

    fun clear() = synchronized(lines) { lines.clear() }

    /** Everything, for the system log, without the on-screen length cap. */
    fun toSystemLog() {
        for (line in synchronized(lines) { lines.toList() }) Log.i(TAG, line)
    }
}
