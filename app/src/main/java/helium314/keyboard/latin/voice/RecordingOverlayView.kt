// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import kotlin.reflect.KMutableProperty0

/**
 * Recording indicator with live amplitude meter, elapsed time, cancel, and stop button.
 * Shown in the suggestion strip area during voice recording/transcription.
 */
class RecordingOverlayView(context: Context) : LinearLayout(context) {

    companion object {
        /** How long before the ceiling the timer switches to its warning tint. */
        private const val NEAR_LIMIT_WARNING_MS = 10_000L
        /** Fixed amber: the theme text colour it would otherwise derive from is what we contrast against. */
        private const val NEAR_LIMIT_COLOR = 0xFFFFA000.toInt()
    }

    private val meterView: AmplitudeMeterView
    private val spinner: ProgressBar
    private val timerText: TextView
    private val statusText: TextView
    private val cancelButton: ImageView
    private val stopButton: ImageView
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    // Per-button debounce: Stop and Cancel must not share a window, or tapping Stop then Cancel in
    // quick succession would silently swallow the Cancel.
    private var lastStopClickMs = 0L
    private var lastCancelClickMs = 0L

    var onStopClick: (() -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null

    /** Supplier for live amplitude (0..32767) and elapsed ms. Set by the controller. */
    var telemetryProvider: (() -> Pair<Double, Long>)? = null

    /**
     * Supplier for the recording ceiling in ms. When set, the timer reads "0:30 / 1:30" instead of a
     * bare elapsed count, so the limit is visible while dictating rather than a surprise at the end.
     */
    var maxDurationMsProvider: (() -> Long)? = null

    /** Timer colour at rest, kept so the near-limit warning tint can be reverted. */
    private var timerNormalColor = 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setPadding(dp(12), dp(4), dp(12), dp(4))

        meterView = AmplitudeMeterView(context).apply {
            layoutParams = LayoutParams(dp(44), dp(20)).apply { marginEnd = dp(12) }
        }
        spinner = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) }
            visibility = View.GONE
        }
        timerText = TextView(context).apply {
            textSize = 12f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(12)
            }
        }
        statusText = TextView(context).apply {
            textSize = 13f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        cancelButton = makeRoundButton(isCancel = true, descRes = R.string.voice_cancel) {
            debounceClick(::lastCancelClickMs) { onCancelClick?.invoke() }
        }
        stopButton = makeRoundButton(isCancel = false, descRes = R.string.voice_stop_recording) {
            debounceClick(::lastStopClickMs) { onStopClick?.invoke() }
        }

        addView(meterView)
        addView(spinner)
        addView(timerText)
        addView(statusText)
        addView(cancelButton)
        addView(stopButton)
    }

    // 32dp keeps the buttons inside the ~44dp suggestion strip with comfortable vertical
    // breathing room; the 6dp sibling gap preserves separation without crowding the timer.
    //
    // Iconography: cancel uses an X (universal "discard"), stop uses a checkmark ("submit").
    // The earlier square-vs-dot pair was ambiguous — users couldn't tell which one threw the
    // recording away vs which one sent it for transcription.
    private fun makeRoundButton(isCancel: Boolean, descRes: Int, onClick: () -> Unit): ImageView {
        val size = dp(32)
        val iconRes = if (isCancel) R.drawable.ic_close else R.drawable.ic_setup_check
        return ImageView(context).apply {
            layoutParams = LayoutParams(size, size).apply { marginStart = dp(6) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(ContextCompat.getDrawable(context, iconRes)?.mutate())
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(descRes)
            setOnClickListener { onClick() }
        }
    }

    fun setColors(textColor: Int) {
        spinner.indeterminateDrawable?.mutate()?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        statusText.setTextColor(textColor)
        timerNormalColor = (textColor and 0x00FFFFFF) or 0xAA000000.toInt()
        timerText.setTextColor(timerNormalColor)
        meterView.meterColor = textColor
        // Stop button (✓ submit) is the primary action: stronger ring + full-opacity glyph.
        (stopButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x22000000)
        stopButton.drawable?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
        // Cancel button (✕ discard) is secondary: subtler ring + muted glyph.
        (cancelButton.background as? GradientDrawable)
            ?.setColor((textColor and 0x00FFFFFF) or 0x11000000)
        cancelButton.drawable?.setColorFilter(
            (textColor and 0x00FFFFFF) or 0x99000000.toInt(),
            PorterDuff.Mode.SRC_IN,
        )
    }

    /**
     * On-device model load, distinct from [showTranscribing]. The sherpa recognizer takes seconds to
     * build on a cold start and the decode that follows is fast, so one label covering both made a
     * first run look identical to a hung one.
     */
    fun showPreparing() = showBusy(context.getString(R.string.voice_preparing))

    fun showRecording() {
        statusText.text = context.getString(R.string.voice_recording)
        spinner.visibility = View.GONE
        meterView.visibility = View.VISIBLE
        meterView.startAnimation()
        timerText.visibility = View.VISIBLE
        stopButton.visibility = View.VISIBLE
        cancelButton.visibility = View.VISIBLE
        startTicking()
        announceForAccessibility(statusText.text)
    }

    fun showTranscribing() = showBusy(context.getString(R.string.voice_transcribing))

    /** Meter and timer off, spinner on. Cancel stays, so the user can still abort. */
    private fun showBusy(text: CharSequence) {
        statusText.text = text
        meterView.stopAnimation()
        meterView.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        timerText.visibility = View.GONE
        stopButton.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        stopTicking()
        announceForAccessibility(statusText.text)
    }

    fun stopAnimation() {
        meterView.stopAnimation()
        stopTicking()
    }

    private fun startTicking() {
        stopTicking()
        val r = object : Runnable {
            override fun run() {
                val telemetry = telemetryProvider?.invoke()
                if (telemetry != null) {
                    meterView.setAmplitude(telemetry.first)
                    val elapsed = telemetry.second
                    val limit = maxDurationMsProvider?.invoke() ?: 0L
                    if (limit > 0L) {
                        timerText.text = context.getString(
                            R.string.voice_timer_elapsed_of_limit,
                            formatElapsed(elapsed),
                            formatElapsed(limit),
                        )
                        // Warn while there is still time to wrap up a sentence, rather than cutting
                        // the user off mid-word. Recording auto-submits at the limit either way.
                        val nearLimit = limit - elapsed <= NEAR_LIMIT_WARNING_MS
                        timerText.setTextColor(if (nearLimit) NEAR_LIMIT_COLOR else timerNormalColor)
                    } else {
                        timerText.text = formatElapsed(elapsed)
                        timerText.setTextColor(timerNormalColor)
                    }
                }
                tickHandler.postDelayed(this, 80L)
            }
        }
        tickRunnable = r
        tickHandler.post(r)
    }

    private fun stopTicking() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun debounceClick(lastClickMs: KMutableProperty0<Long>, action: () -> Unit) {
        // Stop/Cancel both have heavy side effects (stop the recorder, cancel an in-flight upload).
        // A spammed double-tap of the *same* button can race the state machine — 300ms is plenty of
        // breathing room. The window is per-button so the two never block each other.
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs.get() < 300L) return
        lastClickMs.set(now)
        action()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Draws three horizontal bars whose height follows the live amplitude. Falls back to a
     * gentle pulse while amplitude stays at zero (e.g., right at startup) so the UI never
     * looks frozen.
     */
    private class AmplitudeMeterView(context: Context) : View(context) {
        var meterColor: Int = Color.LTGRAY
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var level: Float = 0f // 0..1
        private var animator: ValueAnimator? = null
        private var pulsePhase: Float = 0f

        fun startAnimation() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulsePhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
        }

        fun setAmplitude(meanAbs: Double) {
            // Map 0..~6000 to 0..1 with a gentle curve so quiet speech still moves the needle.
            val normalized = (meanAbs / 6000.0).coerceIn(0.0, 1.0)
            val curved = Math.sqrt(normalized).toFloat()
            // Smooth toward target to avoid jitter.
            level = level + (curved - level) * 0.35f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val barCount = 3
            val gap = width / 14f
            val barWidth = (width - gap * (barCount + 1)) / barCount
            val maxBarHeight = height.toFloat() * 0.85f
            val centerY = height / 2f
            paint.color = meterColor
            for (i in 0 until barCount) {
                val phase = (pulsePhase + i * 0.2f) % 1f
                val pulse = (kotlin.math.sin(phase * Math.PI * 2).toFloat() * 0.5f + 0.5f)
                val mix = (level * 0.85f + pulse * 0.15f).coerceIn(0.15f, 1f)
                val h = maxBarHeight * mix
                val left = gap + i * (barWidth + gap)
                val top = centerY - h / 2f
                val bottom = centerY + h / 2f
                paint.alpha = (120 + 135 * mix).toInt().coerceAtMost(255)
                canvas.drawRoundRect(left, top, left + barWidth, bottom, barWidth / 2f, barWidth / 2f, paint)
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopAnimation()
        }
    }
}
