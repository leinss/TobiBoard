// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R

/**
 * Transient bar shown in the suggestion strip right after an AI insertion (voice transcription or
 * text-fix), offering a one-tap Undo. Styled to match [TextFixOverlayView]'s pill button.
 */
class UndoBarView(context: Context) : LinearLayout(context) {

    private val label: TextView
    private val undoButton: TextView
    var onUndoClick: (() -> Unit)? = null
    private var lastClickMs = 0L

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setPadding(dp(12), dp(4), dp(12), dp(4))

        label = TextView(context).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
        }
        undoButton = TextView(context).apply {
            text = context.getString(R.string.voice_undo)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isAllCaps = false
            minHeight = dp(36)
            minWidth = dp(64)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { debounceClick { onUndoClick?.invoke() } }
        }

        addView(label)
        addView(undoButton)
    }

    fun setLabel(text: String) {
        label.text = text
    }

    fun setColors(textColor: Int) {
        label.setTextColor(textColor)
        undoButton.setTextColor(textColor)
        (undoButton.background as? GradientDrawable)?.setColor((textColor and 0x00FFFFFF) or (0x55 shl 24))
    }

    private inline fun debounceClick(action: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < 300L) return
        lastClickMs = now
        action()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}
