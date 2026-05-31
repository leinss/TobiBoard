// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R

/**
 * Overlay shown in the suggestion strip during a text-fix round-trip.
 *
 * States:
 *  - working: shows "Fixing…" status while the request is in flight.
 *  - result: shows the proposed text plus Replace/Discard buttons.
 */
class TextFixOverlayView(context: Context) : LinearLayout(context) {

    private val statusText: TextView
    private val resultText: TextView
    private val replaceButton: TextView
    private val discardButton: TextView

    var onReplaceClick: (() -> Unit)? = null
    var onDiscardClick: (() -> Unit)? = null
    private var lastClickMs = 0L
    private var baseTextColor: Int = 0xFF000000.toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setPadding(dp(12), dp(4), dp(12), dp(4))

        statusText = TextView(context).apply {
            textSize = 13f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
            visibility = View.GONE
        }
        resultText = TextView(context).apply {
            textSize = 13f
            // A little more room than the plain-result case so the inline diff (which carries the
            // struck-through removals alongside the additions) stays legible before truncating.
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        discardButton = makePillButton(R.string.text_fix_discard, isPrimary = false) { debounceClick { onDiscardClick?.invoke() } }
        replaceButton = makePillButton(R.string.text_fix_replace, isPrimary = true) { debounceClick { onReplaceClick?.invoke() } }

        addView(statusText)
        addView(resultText)
        addView(discardButton)
        addView(replaceButton)
    }

    private fun makePillButton(labelRes: Int, isPrimary: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = context.getString(labelRes)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isAllCaps = false
            minHeight = dp(36)
            minWidth = dp(64)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
            }
            background = bg
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                // Space between siblings; the left-most button gets gap from the text via its own
                // space, the primary button gets a small trailing margin from the strip edge.
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    fun setColors(textColor: Int) {
        baseTextColor = textColor
        statusText.setTextColor(textColor)
        resultText.setTextColor(textColor)
        // Primary (Replace): strong filled background with full-opacity text.
        replaceButton.setTextColor(textColor)
        (replaceButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x55000000)
        // Secondary (Discard): muted text, subtle fill to keep it clearly recessive.
        discardButton.setTextColor((textColor and 0x00FFFFFF) or 0xB0000000.toInt())
        (discardButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x18000000)
    }

    fun showWorking() {
        statusText.text = context.getString(R.string.text_fix_working)
        statusText.visibility = View.VISIBLE
        resultText.visibility = View.GONE
        replaceButton.visibility = View.GONE
        discardButton.visibility = View.VISIBLE
        announceForAccessibility(statusText.text)
    }

    fun showResult(original: String, proposed: String) {
        statusText.visibility = View.GONE
        // Inline word-diff: additions are bold + underlined, removals struck through and dimmed,
        // so the user sees what the fix changed rather than just the final text. Theme-safe — no
        // hardcoded hues, only emphasis derived from the current strip text color.
        resultText.text = buildDiffSpannable(original, proposed)
        resultText.visibility = View.VISIBLE
        replaceButton.visibility = View.VISIBLE
        discardButton.visibility = View.VISIBLE
        // Announce the final proposed text (not the diff markup) for screen readers.
        announceForAccessibility(context.getString(R.string.text_fix_result_a11y, proposed))
    }

    private fun buildDiffSpannable(original: String, proposed: String): CharSequence {
        val segments = WordDiff.diff(original, proposed)
        // Dim removals to ~50% alpha of the base text color so they recede behind the additions.
        val dimColor = (baseTextColor and 0x00FFFFFF) or (0x80 shl 24)
        val sb = SpannableStringBuilder()
        for (segment in segments) {
            val start = sb.length
            sb.append(segment.text)
            val end = sb.length
            when (segment.op) {
                WordDiff.Op.ADDED -> {
                    sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                WordDiff.Op.REMOVED -> {
                    sb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(dimColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                WordDiff.Op.COMMON -> Unit
            }
        }
        return sb
    }

    fun showError(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        resultText.visibility = View.GONE
        replaceButton.visibility = View.GONE
        discardButton.visibility = View.VISIBLE
        announceForAccessibility(message)
    }

    private inline fun debounceClick(action: () -> Unit) {
        // Replace and Discard both mutate persistent state (cancelling an in-flight request or
        // committing a text replacement). A double-tap should never fire the callback twice.
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < 300L) return
        lastClickMs = now
        action()
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()
}
