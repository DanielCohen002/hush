package com.djcohen.hush

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Optional online puzzle library. Only runs when the user taps "Download new puzzles": it
 * fetches the three packs from this repo, checks each parses, and saves them in the app's
 * private storage, where [PuzzleLibrary] prefers them over the packs built into the app.
 */
object PuzzleUpdater {
    private const val BASE = "https://raw.githubusercontent.com/DanielCohen002/hush/main/puzzle-packs/"
    private val PACKS = listOf("sudoku", "sudoku4", "wordsearch", "wordsearch6", "crossword")

    fun dir(c: Context) = File(c.filesDir, "puzzles")

    fun hasDownloaded(c: Context) = PACKS.all { File(dir(c), "$it.json").exists() }

    /** Downloads every pack; all-or-nothing, so a half-finished download never mixes packs.
     *  [onDone] runs on the main thread with the total puzzles fetched, or null on failure. */
    fun download(c: Context, onDone: (Int?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        thread {
            val total = try {
                val staged = PACKS.associateWith { name ->
                    val conn = (URL("$BASE$name.json").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 20000
                    }
                    try {
                        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        // Reject anything that isn't a non-empty JSON array of objects.
                        val arr = JSONArray(text)
                        if (arr.length() == 0) throw IllegalStateException("empty pack")
                        arr.getJSONObject(0)
                        text to arr.length()
                    } finally {
                        conn.disconnect()
                    }
                }
                dir(c).mkdirs()
                for ((name, pair) in staged) {
                    val tmp = File(dir(c), "$name.json.tmp")
                    tmp.writeText(pair.first)
                    tmp.renameTo(File(dir(c), "$name.json"))
                }
                PuzzleLibrary.clearCache()
                Prefs.resetPuzzleProgress(c) // new packs: start from a fresh random spot
                staged.values.sumOf { it.second }
            } catch (_: Exception) {
                null
            }
            main.post { onDone(total) }
        }
    }

    /** Delete downloaded packs and go back to the ones built into the app. */
    fun reset(c: Context) {
        PACKS.forEach { File(dir(c), "$it.json").delete() }
        PuzzleLibrary.clearCache()
        Prefs.resetPuzzleProgress(c)
    }
}
