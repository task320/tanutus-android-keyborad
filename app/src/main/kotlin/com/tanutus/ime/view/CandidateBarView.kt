package com.tanutus.ime.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tanutus.ime.R

/**
 * The persistent candidate bar above row 1 (docs/keyboard-spec.md: "候補バーはキーボード
 * 上部...に常設の帯として確保する"). Candidates lay out horizontally with the platform's
 * own scrolling; there's no dedicated "next candidate" button here because that's driven by
 * the space key (see core/gesture/SpaceKeyGestureHandler) — tapping a candidate directly is
 * the only UI-level interaction this view offers.
 */
class CandidateBarView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : HorizontalScrollView(context, attrs) {
        var onCandidateSelected: ((Int) -> Unit)? = null

        private val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

        init {
            isHorizontalScrollBarEnabled = false
            addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            setBackgroundColor(ContextCompat.getColor(context, R.color.candidate_bar_background))
        }

        /** Empty [candidates] keeps the bar's height reserved but shows nothing, per spec. */
        fun setCandidates(candidates: List<String>, selectedIndex: Int) {
            container.removeAllViews()
            if (candidates.isEmpty()) {
                container.visibility = View.INVISIBLE
                return
            }
            container.visibility = View.VISIBLE
            candidates.forEachIndexed { index, candidate ->
                container.addView(buildCandidateView(candidate, index == selectedIndex, index))
            }
        }

        private fun buildCandidateView(candidate: String, isSelected: Boolean, index: Int): TextView =
            TextView(context).apply {
                text = candidate
                gravity = Gravity.CENTER
                setPadding(HORIZONTAL_PADDING_PX, 0, HORIZONTAL_PADDING_PX, 0)
                setTextColor(ContextCompat.getColor(context, R.color.candidate_text))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.candidate_text_size))
                setBackgroundColor(
                    if (isSelected) {
                        ContextCompat.getColor(context, R.color.candidate_selected_background)
                    } else {
                        Color.TRANSPARENT
                    },
                )
                setOnClickListener { onCandidateSelected?.invoke(index) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            }

        private companion object {
            const val HORIZONTAL_PADDING_PX = 32
        }
    }
