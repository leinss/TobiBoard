// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import helium314.keyboard.latin.R

/**
 * Modal popup for reading a full text-fix result that overflows the suggestion strip.
 *
 * The strip can only show two truncated lines. Tapping the proposed text opens this popup
 * which sizes itself to cover the keyboard area and lets the user read the whole result,
 * then commit (Replace) or back out (Discard / tap outside).
 */
object TextFixExpandedPopup {

    fun show(
        anchor: View,
        proposed: String,
        textColor: Int,
        onReplace: () -> Unit,
        onDiscard: () -> Unit,
    ): PopupWindow {
        val context = anchor.context
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 12).toFloat()
                // Match the keyboard's dark surface tone; the alpha keeps it slightly translucent
                // so the keyboard remains a visible backdrop.
                setColor(0xF02A2A2A.toInt())
            }
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            isVerticalScrollBarEnabled = true
        }
        val text = TextView(context).apply {
            this.text = proposed
            textSize = 16f
            setTextColor(textColor)
            setLineSpacing(0f, 1.2f)
        }
        scroll.addView(text)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 12) }
        }

        // Forward declaration: the buttons need to dismiss the window, but the window is built
        // after the views. Use a holder so the lambdas can call into the eventually-set window.
        val windowHolder = arrayOfNulls<PopupWindow>(1)
        val lastClickMs = longArrayOf(0L)
        fun debounced(action: () -> Unit) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastClickMs[0] < 300L) return
            lastClickMs[0] = now
            action()
        }

        val discardButton = makePillButton(context, R.string.text_fix_discard, textColor, isPrimary = false) {
            debounced {
                windowHolder[0]?.dismiss()
                onDiscard()
            }
        }
        val replaceButton = makePillButton(context, R.string.text_fix_replace, textColor, isPrimary = true) {
            debounced {
                windowHolder[0]?.dismiss()
                onReplace()
            }
        }
        buttonRow.addView(discardButton)
        buttonRow.addView(replaceButton)

        container.addView(scroll)
        container.addView(buttonRow)

        // The anchor's window only spans the keyboard area, so sizing relative to it would
        // produce a popup smaller than a single page of text. Use display metrics instead so
        // the popup can comfortably fit several lines plus the action row.
        val metrics = context.resources.displayMetrics
        val popupWidth = (metrics.widthPixels * 0.92).toInt()
        val popupHeight = (metrics.heightPixels * 0.45).toInt().coerceAtLeast(dp(context, 220))

        val popup = PopupWindow(container, popupWidth, popupHeight, true).apply {
            // Transparent background so the rounded container drawable shows through; without
            // this the system inserts its own opaque chrome that masks the corner radius.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
            elevation = dp(context, 8).toFloat()
        }
        windowHolder[0] = popup

        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        return popup
    }

    private fun makePillButton(
        context: Context,
        labelRes: Int,
        textColor: Int,
        isPrimary: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = context.getString(labelRes)
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        isAllCaps = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 22).toFloat()
            val fillAlpha = if (isPrimary) 0x55 else 0x18
            setColor((textColor and 0x00FFFFFF) or (fillAlpha shl 24))
        }
        setTextColor(
            if (isPrimary) textColor
            else (textColor and 0x00FFFFFF) or 0xB0000000.toInt()
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(context, 12) }
        setOnClickListener { onClick() }
    }

    private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()
}
