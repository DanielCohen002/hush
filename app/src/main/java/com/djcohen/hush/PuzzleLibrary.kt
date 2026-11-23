package com.djcohen.hush

import android.content.Context
import org.json.JSONArray
import kotlin.random.Random

/** Which puzzle the pause screen offers. [MIX] picks one of the others each time. */
enum class PuzzleType(val id: String, val labelRes: Int) {
    SUDOKU("sudoku", R.string.puzzle_sudoku),
    WORDSEARCH("wordsearch", R.string.puzzle_wordsearch),
    CROSSWORD("crossword", R.string.puzzle_crossword),
    MIX("mix", R.string.puzzle_mix);

    companion object {
        fun fromId(id: String?): PuzzleType = values().firstOrNull { it.id == id } ?: SUDOKU

        /** The types shown as toggles ([MIX] is only the old combined setting). */
        val selectable = listOf(SUDOKU, WORDSEARCH, CROSSWORD)
    }
}

/** How hard the puzzles are. EASY (the default) uses a 4x4 sudoku and a 6x6 word search with
 *  four short words; STANDARD is the full 6x6 sudoku and 8x8 word search. */
enum class PuzzleLevel(val id: String, val labelRes: Int) {
    EASY("easy", R.string.level_easy),
    STANDARD("standard", R.string.level_standard);

    companion object {
        fun fromId(id: String?): PuzzleLevel = if (id == "standard") STANDARD else EASY
    }
}

/**
 * Puzzles come from packs bundled in assets/puzzles/ (built by tools/gen_puzzles.py), not
 * generated on the phone. Each pack is read once, and the next puzzle is just the next index in
 * it, so nothing repeats until the whole pack has been used.
 */
object PuzzleLibrary {
    class Sudoku(val given: IntArray, val solution: IntArray)
    class WordSearch(val grid: String, val words: List<String>)
    class Crossword(val grid: String, val across: List<String>, val down: List<String>)

    private val cache = HashMap<String, JSONArray>()

    /** A downloaded pack (see [PuzzleUpdater]) wins over the one built into the app. */
    private fun pack(c: Context, name: String): JSONArray = synchronized(cache) {
        cache.getOrPut(name) {
            val downloaded = java.io.File(PuzzleUpdater.dir(c), "$name.json")
            val fromDisk = try {
                if (downloaded.exists()) JSONArray(downloaded.readText()) else null
            } catch (_: Exception) {
                null // corrupt download: fall back to the built-in pack
            }
            fromDisk ?: c.assets.open("puzzles/$name.json").bufferedReader().use { JSONArray(it.readText()) }
        }
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    /** Resolve [MIX] to a concrete type. */
    fun concrete(type: PuzzleType): PuzzleType =
        if (type != PuzzleType.MIX) type else
            listOf(PuzzleType.SUDOKU, PuzzleType.WORDSEARCH, PuzzleType.CROSSWORD).random(Random.Default)

    /** The next unseen puzzle of a concrete [type], as one of the data classes above. */
    fun next(c: Context, type: PuzzleType): Any {
        val easy = Prefs.puzzleLevel(c) == PuzzleLevel.EASY
        val name = when (type) {
            PuzzleType.SUDOKU -> if (easy) "sudoku4" else "sudoku"
            PuzzleType.WORDSEARCH -> if (easy) "wordsearch6" else "wordsearch"
            else -> "crossword"
        }
        val arr = pack(c, name)
        val o = arr.getJSONObject(Prefs.nextPuzzleIndex(c, name, arr.length()))
        return when (type) {
            PuzzleType.SUDOKU -> Sudoku(
                o.getString("p").map { it - '0' }.toIntArray(),
                o.getString("s").map { it - '0' }.toIntArray(),
            )
            PuzzleType.WORDSEARCH -> WordSearch(
                o.getString("g"),
                o.getJSONArray("w").let { w -> (0 until w.length()).map { w.getString(it) } },
            )
            else -> Crossword(
                o.getString("g"),
                o.getJSONArray("a").let { w -> (0 until w.length()).map { w.getString(it) } },
                o.getJSONArray("d").let { w -> (0 until w.length()).map { w.getString(it) } },
            )
        }
    }
}
