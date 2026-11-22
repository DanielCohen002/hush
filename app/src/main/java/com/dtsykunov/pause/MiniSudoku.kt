package com.dtsykunov.pause

import kotlin.random.Random

/**
 * A 6x6 sudoku (2x3 boxes) generator. Puzzles have exactly one solution, so there's never a
 * guess to make, and they're small enough to finish during a pause.
 */
object MiniSudoku {
    const val N = 6
    private const val BOX_R = 2 // box height
    private const val BOX_C = 3 // box width

    class Puzzle(val given: IntArray, val solution: IntArray)

    fun generate(rnd: Random = Random.Default, blanks: Int = 18): Puzzle {
        val solution = IntArray(N * N)
        fill(solution, rnd)
        val cells = (0 until N * N).shuffled(rnd)
        val given = solution.copyOf()
        var removed = 0
        for (cell in cells) {
            if (removed >= blanks) break
            val keep = given[cell]
            given[cell] = 0
            if (countSolutions(given.copyOf(), limit = 2) == 1) removed++ else given[cell] = keep
        }
        return Puzzle(given, solution)
    }

    private fun canPlace(g: IntArray, pos: Int, v: Int): Boolean {
        val r = pos / N
        val c = pos % N
        for (i in 0 until N) {
            if (g[r * N + i] == v || g[i * N + c] == v) return false
        }
        val br = r / BOX_R * BOX_R
        val bc = c / BOX_C * BOX_C
        for (dr in 0 until BOX_R) for (dc in 0 until BOX_C) {
            if (g[(br + dr) * N + bc + dc] == v) return false
        }
        return true
    }

    /** Fill [g] (zeros are blanks) with a random full solution. */
    private fun fill(g: IntArray, rnd: Random): Boolean {
        val pos = g.indexOf(0)
        if (pos < 0) return true
        for (v in (1..N).shuffled(rnd)) {
            if (canPlace(g, pos, v)) {
                g[pos] = v
                if (fill(g, rnd)) return true
                g[pos] = 0
            }
        }
        return false
    }

    /** Number of solutions of [g], stopping once [limit] is reached. */
    private fun countSolutions(g: IntArray, limit: Int): Int {
        val pos = g.indexOf(0)
        if (pos < 0) return 1
        var total = 0
        for (v in 1..N) {
            if (canPlace(g, pos, v)) {
                g[pos] = v
                total += countSolutions(g, limit - total)
                g[pos] = 0
                if (total >= limit) return total
            }
        }
        return total
    }
}
