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

    /** Height the puzzle may use so the pause screen never needs to scroll; 0 = not known yet. */
    var budgetPx = 0
        set(v) {
            if (field == v) return
            field = v
            (getChildAt(0) as? CrosswordView)?.maxHeightPx = v
            requestLayout()
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        // Sudoku and word search scale down as a whole if they would be taller than the budget.
        if (budgetPx > 0 && getChildAt(0) !is CrosswordView && measuredHeight > budgetPx) {
            val w = (measuredWidth * budgetPx / measuredHeight.toFloat()).toInt()
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightSpec)
        }
    }

    /** Show [puzzle] (one of the [PuzzleLibrary] data classes); [onSolved] fires when the player
     *  taps the finished board, to ask for the next one. */
    fun show(puzzle: Any, onSolved: () -> Unit) {
        removeAllViews()
        val view: View = when (puzzle) {
            is PuzzleLibrary.Sudoku -> MiniSudokuView(context).also { it.load(puzzle, onSolved) }
            is PuzzleLibrary.WordSearch -> WordSearchView(context).also { it.load(puzzle, onSolved) }
            is PuzzleLibrary.Crossword -> CrosswordView(context).also { it.maxHeightPx = budgetPx; it.load(puzzle, onSolved) }
            else -> return
        }
        addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
