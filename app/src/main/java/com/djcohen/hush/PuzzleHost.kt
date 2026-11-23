package com.djcohen.hush

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Holds whichever puzzle view matches the puzzle data it is given. */
class PuzzleHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Show [puzzle] (one of the [PuzzleLibrary] data classes); [onSolved] fires when the player
     *  taps the finished board, to ask for the next one. */
    fun show(puzzle: Any, onSolved: () -> Unit) {
        removeAllViews()
        val view: View = when (puzzle) {
            is PuzzleLibrary.Sudoku -> MiniSudokuView(context).also { it.load(puzzle, onSolved) }
            is PuzzleLibrary.WordSearch -> WordSearchView(context).also { it.load(puzzle, onSolved) }
            is PuzzleLibrary.Crossword -> CrosswordView(context).also { it.load(puzzle, onSolved) }
            else -> return
        }
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
