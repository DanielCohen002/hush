package com.dtsykunov.pause

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

    private fun pack(c: Context, name: String): JSONArray = synchronized(cache) {
        cache.getOrPut(name) {
            c.assets.open("puzzles/$name.json").bufferedReader().use { JSONArray(it.readText()) }
        }
    }

    /** Resolve [MIX] to a concrete type. */
    fun concrete(type: PuzzleType): PuzzleType =
        if (type != PuzzleType.MIX) type else
            listOf(PuzzleType.SUDOKU, PuzzleType.WORDSEARCH, PuzzleType.CROSSWORD).random(Random.Default)

    /** The next unseen puzzle of a concrete [type], as one of the data classes above. */
    fun next(c: Context, type: PuzzleType): Any {
        val name = type.id
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
