/*
 * Personal addition: a draggable, resizable floating lyrics window drawn over other apps.
 * Licensed GPL-3.0-or-later, like the rest of the project.
 */

package chromahub.rhythm.app.infrastructure.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Shows previous / current / next lyric lines in a small window above other apps.
 *
 * - Drag anywhere on the window to move it; a tap (no drag) calls [onTap].
 * - Drag the grip in the bottom-right corner to resize. Shrinking the window hides the
 *   previous and next lines, down to a single-line strip.
 * - The X in the top-left corner calls [onClose].
 *
 * Requires the "Display over other apps" permission; the caller must check it first.
 * All methods must be called on the main thread.
 */
class FloatingLyricsOverlay(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onClose: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var root: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var prevView: TextView? = null
    private var currentView: TextView? = null
    private var nextView: TextView? = null
    private var headerView: TextView? = null
    private var headerText: String = ""

    private var prevText: String = ""
    private var currentText: String = ""
    private var nextText: String = ""

    // Word-by-word karaoke fill for the current line (null = plain line).
    private var currentWords: List<OverlayWord>? = null
    private var wordCharStarts = IntArray(0)
    private var wordCharEnds = IntArray(0)
    private var lastWordPos = -1L

    // Corner buttons (close / resize) fade out a few seconds after the last touch.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var closeView: IconView? = null
    private var gripView: IconView? = null
    private var controlsVisible = true
    private val fadeRunnable = Runnable {
        controlsVisible = false
        closeView?.animate()?.alpha(0f)?.setDuration(350)?.start()
        gripView?.animate()?.alpha(0f)?.setDuration(350)?.start()
    }

    val isShowing: Boolean get() = root != null

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val minW = dp(MIN_WIDTH_DP)
        val minH = dp(MIN_HEIGHT_DP)
        val maxW = maxOf(minW, screenW)
        val maxH = maxOf(minH, minOf(screenH, dp(MAX_HEIGHT_DP)))

        val defaultW = minOf(dp(DEFAULT_WIDTH_DP), screenW - dp(24))
        val width = prefs.getInt(KEY_W, defaultW).coerceIn(minW, maxW)
        val height = prefs.getInt(KEY_H, dp(DEFAULT_HEIGHT_DP)).coerceIn(minH, maxH)
        val startX = prefs.getInt(KEY_X, (screenW - width) / 2).coerceIn(0, maxOf(0, screenW - width))
        val statusBarId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId > 0) context.resources.getDimensionPixelSize(statusBarId) else dp(24)
        val startY = prefs.getInt(KEY_Y, statusBar + dp(8)).coerceIn(0, maxOf(0, screenH - height))

        val lp = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = startX
        lp.y = startY

        val container = FrameLayout(context)
        // Fully transparent normally; a faint panel shows only while dragging/resizing so you
        // can see the window's edges.
        val bg = GradientDrawable().apply {
            setColor(IDLE_BG)
            cornerRadius = dp(16).toFloat()
        }
        container.background = bg
        container.clipChildren = false
        container.clipToPadding = false

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(SIDE_PADDING_DP), dp(4), dp(SIDE_PADDING_DP), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val header = makeHeader()
        val prev = makeLine(13f, false)
        val cur = makeLine(18f, true)
        val next = makeLine(13f, false)
        val wrap = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        column.addView(header, LinearLayout.LayoutParams(wrap))
        column.addView(prev, LinearLayout.LayoutParams(wrap))
        column.addView(cur, LinearLayout.LayoutParams(wrap))
        column.addView(next, LinearLayout.LayoutParams(wrap))
        container.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val close = IconView(context, IconView.CLOSE, density)
        close.setOnTouchListener { _, event ->
            if (!controlsVisible) {
                // Hidden: reveal the buttons and let the window treat this as a normal touch.
                showControls()
                false
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        onClose()
                        true
                    }
                    else -> true
                }
            }
        }
        val closeParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.START)
        closeParams.setMargins(dp(3), dp(3), 0, 0)
        container.addView(close, closeParams)

        val grip = IconView(context, IconView.GRIP, density)
        val gripParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM or Gravity.END)
        gripParams.setMargins(0, 0, dp(3), dp(3))
        container.addView(grip, gripParams)

        // Drag the whole window; a tap without movement opens the app.
        var downRawX = 0f
        var downRawY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    originX = lp.x
                    originY = lp.y
                    dragging = false
                    bg.setColor(EDIT_BG)
                    showControls()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        lp.x = (originX + dx).toInt()
                        lp.y = (originY + dy).toInt()
                        clampPosition(lp)
                        updateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    bg.setColor(IDLE_BG)
                    scheduleControlsFade()
                    if (dragging) savePosition() else onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    bg.setColor(IDLE_BG)
                    scheduleControlsFade()
                    true
                }
                else -> false
            }
        }

        // Resize from the bottom-right grip.
        var gripDownX = 0f
        var gripDownY = 0f
        var originW = 0
        var originH = 0
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!controlsVisible) {
                        showControls()
                        false
                    } else {
                        gripDownX = event.rawX
                        gripDownY = event.rawY
                        originW = lp.width
                        originH = lp.height
                        bg.setColor(EDIT_BG)
                        showControls()
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (originW + (event.rawX - gripDownX)).toInt().coerceIn(minW, maxW)
                    lp.height = (originH + (event.rawY - gripDownY)).toInt().coerceIn(minH, maxH)
                    clampPosition(lp)
                    updateLayout()
                    applyLineVisibility()
                    renderTexts()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bg.setColor(IDLE_BG)
                    saveSize()
                    scheduleControlsFade()
                    true
                }
                else -> true
            }
        }

        try {
            windowManager.addView(container, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Could not add floating lyrics window (overlay permission missing?)", e)
            return
        }

        root = container
        layoutParams = lp
        closeView = close
        gripView = grip
        headerView = header
        prevView = prev
        currentView = cur
        nextView = next
        applyLineVisibility()
        renderTexts()
        showControls()
        scheduleControlsFade()
    }

    fun hide() {
        val r = root ?: return
        try {
            windowManager.removeView(r)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
        mainHandler.removeCallbacks(fadeRunnable)
        root = null
        layoutParams = null
        closeView = null
        gripView = null
        headerView = null
        prevView = null
        currentView = null
        nextView = null
    }

    /** Update the three visible lines. Pass null/blank for "nothing". */
    fun setLines(prev: String?, current: String?, next: String?, words: List<OverlayWord>? = null) {
        val newPrev = prev.orEmpty()
        val newCurrent = current.orEmpty()
        val newNext = next.orEmpty()

        // Work out whether the lyrics just stepped forward/backward by one line (scroll animation)
        // or jumped somewhere else (simple fade).
        val direction = when {
            currentText.isNotBlank() && nextText.isNotBlank() &&
                newPrev == currentText && newCurrent == nextText -> 1
            currentText.isNotBlank() && prevText.isNotBlank() &&
                newNext == currentText && newCurrent == prevText -> -1
            else -> 0
        }

        prevText = newPrev
        currentText = newCurrent
        nextText = newNext
        currentWords = words?.takeIf { it.isNotEmpty() }
        computeWordOffsets()
        lastWordPos = -1L
        if (root == null) return
        renderTexts()
        if (currentWords == null) currentView?.paint?.shader = null

        if (direction == 0) {
            resetLineAnimations()
            currentView?.let {
                it.alpha = 0.35f
                it.animate().alpha(1f).setDuration(160).start()
            }
        } else {
            slideLines(direction)
        }
    }

    private fun computeWordOffsets() {
        val words = currentWords
        if (words == null) {
            wordCharStarts = IntArray(0)
            wordCharEnds = IntArray(0)
            return
        }
        val starts = IntArray(words.size)
        val ends = IntArray(words.size)
        var len = 0
        words.forEachIndexed { i, w ->
            if (i > 0 && !(w.isPart && w.text.isNotEmpty())) len += 1
            starts[i] = len
            len += w.text.length
            ends[i] = len
        }
        wordCharStarts = starts
        wordCharEnds = ends
    }

    /**
     * Word-by-word fill: words already sung are bright, upcoming words dim, with a soft edge that
     * moves smoothly through the active word. Call often (e.g. every ~50 ms) while playing.
     */
    fun updateWordProgress(positionMs: Long) {
        val words = currentWords ?: return
        val tv = currentView ?: return
        if (positionMs == lastWordPos) return
        val text = tv.text?.toString() ?: return
        if (text.isEmpty() || tv.width <= 0 || wordCharEnds.size != words.size) return
        lastWordPos = positionMs

        // How far through the line are we, in characters (fractional)?
        var chars = 0f
        for (i in words.indices) {
            val w = words[i]
            if (positionMs >= w.end) {
                chars = wordCharEnds[i].toFloat()
            } else if (positionMs > w.start) {
                val span = (w.end - w.start).coerceAtLeast(1L)
                val frac = (positionMs - w.start).toFloat() / span
                chars = wordCharStarts[i] + frac * (wordCharEnds[i] - wordCharStarts[i])
                break
            } else {
                break
            }
        }

        val paint = tv.paint
        val total = paint.measureText(text)
        if (total <= 0f) return
        val whole = chars.toInt().coerceIn(0, text.length)
        val frac = chars - whole
        val x0 = paint.measureText(text, 0, whole)
        val x1 = paint.measureText(text, 0, (whole + 1).coerceAtMost(text.length))
        val x = x0 + frac * (x1 - x0)
        val available = (tv.width - tv.paddingLeft - tv.paddingRight).toFloat()
        val left = if (total < available) (available - total) / 2f else 0f
        val p = (x / total).coerceIn(0f, 1f)
        val soft = 0.05f
        paint.shader = LinearGradient(
            left, 0f, left + total, 0f,
            intArrayOf(SUNG_COLOR, SUNG_COLOR, UNSUNG_COLOR, UNSUNG_COLOR),
            floatArrayOf(0f, p, (p + soft).coerceAtMost(1f), 1f),
            Shader.TileMode.CLAMP
        )
        tv.invalidate()
    }

    private fun resetLineAnimations() {
        listOfNotNull(prevView, currentView, nextView).forEach {
            it.animate().cancel()
            it.translationY = 0f
            it.alpha = 1f
        }
    }

    /**
     * Scroll effect: every visible line starts one step below (or above, going backwards) its
     * final place and glides into it, so the lyrics look like they scroll up.
     */
    private fun slideLines(direction: Int) {
        val h = layoutParams?.height ?: return
        val distance = (if (h < dp(NEXT_MIN_HEIGHT_DP)) dp(10) else dp(SLIDE_DISTANCE_DP)).toFloat()
        val interpolator = DecelerateInterpolator()
        val incoming = if (direction > 0) nextView else prevView
        listOfNotNull(prevView, currentView, nextView).forEach { v ->
            v.animate().cancel()
            if (v.visibility != View.VISIBLE) {
                v.translationY = 0f
                v.alpha = 1f
                return@forEach
            }
            v.translationY = distance * direction
            v.alpha = when {
                v === incoming -> 0f
                v === currentView -> 0.4f
                else -> 0.6f
            }
            v.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(SLIDE_DURATION_MS)
                .setInterpolator(interpolator)
                .start()
        }
    }

    private fun showControls() {
        mainHandler.removeCallbacks(fadeRunnable)
        controlsVisible = true
        closeView?.animate()?.cancel()
        closeView?.alpha = 1f
        gripView?.animate()?.cancel()
        gripView?.alpha = 1f
    }

    private fun scheduleControlsFade() {
        mainHandler.removeCallbacks(fadeRunnable)
        mainHandler.postDelayed(fadeRunnable, CONTROLS_FADE_DELAY_MS)
    }

    /** Small "Title – Artist" line, only visible when the window is tall enough. */
    fun setHeader(text: String?) {
        headerText = text.orEmpty()
        if (root == null) return
        applyLineVisibility()
        renderTexts()
    }

    private fun makeHeader(): TextView {
        val tv = TextView(context)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.setTextColor(HEADER_COLOR)
        tv.setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), DIM_SHADOW_COLOR)
        tv.typeface = Typeface.DEFAULT
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.includeFontPadding = false
        tv.setPadding(0, dp(1), 0, dp(2))
        return tv
    }

    private fun makeLine(sizeSp: Float, isCurrent: Boolean): TextView {
        val tv = TextView(context)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (isCurrent) {
            // Bright white with a soft coloured glow around the letters.
            tv.setTextColor(Color.WHITE)
            tv.setShadowLayer(dp(GLOW_RADIUS_DP).toFloat(), 0f, 0f, GLOW_COLOR)
        } else {
            // Dimmer lines get a small dark shadow so they stay readable with no background panel.
            tv.setTextColor(DIM_TEXT_COLOR)
            tv.setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), DIM_SHADOW_COLOR)
        }
        tv.typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.includeFontPadding = false
        tv.setPadding(0, dp(1), 0, dp(1))
        return tv
    }

    /** Hide the previous/next lines when the window is too short to fit them. */
    private fun applyLineVisibility() {
        val h = layoutParams?.height ?: return
        headerView?.visibility =
            if (h >= dp(HEADER_MIN_HEIGHT_DP) && headerText.isNotEmpty()) View.VISIBLE else View.GONE
        prevView?.visibility = if (h >= dp(PREV_MIN_HEIGHT_DP)) View.VISIBLE else View.GONE
        nextView?.visibility = if (h >= dp(NEXT_MIN_HEIGHT_DP)) View.VISIBLE else View.GONE
    }

    private fun renderTexts() {
        val lp = layoutParams ?: return
        val available = lp.width - dp(SIDE_PADDING_DP) * 2
        headerView?.let { setFitted(it, headerText, 11f, 9f, available) }
        prevView?.let { setFitted(it, prevText, 13f, 10f, available) }
        currentView?.let { setFitted(it, if (currentText.isBlank()) "\u266A" else currentText, 18f, 11f, available) }
        nextView?.let { setFitted(it, nextText, 13f, 10f, available) }
    }

    /** Shrink the text size until it fits one line (down to minSp), then ellipsize. */
    private fun setFitted(tv: TextView, text: String, maxSp: Float, minSp: Float, availablePx: Int) {
        tv.text = text
        var sp = maxSp
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (text.isEmpty() || availablePx <= 0) return
        while (sp > minSp && tv.paint.measureText(text) > availablePx) {
            sp -= 1f
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
    }

    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        lp.x = lp.x.coerceIn(0, maxOf(0, metrics.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, metrics.heightPixels - lp.height))
    }

    private fun updateLayout() {
        val r = root ?: return
        val lp = layoutParams ?: return
        try {
            windowManager.updateViewLayout(r, lp)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun savePosition() {
        val lp = layoutParams ?: return
        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
    }

    private fun saveSize() {
        val lp = layoutParams ?: return
        prefs.edit()
            .putInt(KEY_W, lp.width)
            .putInt(KEY_H, lp.height)
            .putInt(KEY_X, lp.x)
            .putInt(KEY_Y, lp.y)
            .apply()
    }

    /** Tiny line-drawn icons (close X and resize grip) so no icon fonts or resources are needed. */
    private class IconView(context: Context, private val kind: Int, density: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val underlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x88000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4.5f * density
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (kind == CLOSE) {
                val m = w * 0.3f
                canvas.drawLine(m, m, w - m, h - m, underlay)
                canvas.drawLine(w - m, m, m, h - m, underlay)
                canvas.drawLine(m, m, w - m, h - m, paint)
                canvas.drawLine(w - m, m, m, h - m, paint)
            } else {
                canvas.drawLine(w * 0.35f, h * 0.85f, w * 0.85f, h * 0.35f, underlay)
                canvas.drawLine(w * 0.6f, h * 0.85f, w * 0.85f, h * 0.6f, underlay)
                canvas.drawLine(w * 0.35f, h * 0.85f, w * 0.85f, h * 0.35f, paint)
                canvas.drawLine(w * 0.6f, h * 0.85f, w * 0.85f, h * 0.6f, paint)
            }
        }

        companion object {
            const val CLOSE = 0
            const val GRIP = 1
        }
    }

    private companion object {
        const val TAG = "FloatingLyrics"
        const val PREFS = "floating_lyrics"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_W = "w"
        const val KEY_H = "h"

        const val MIN_WIDTH_DP = 150
        const val MIN_HEIGHT_DP = 38      // one line of text fits
        const val NEXT_MIN_HEIGHT_DP = 58 // current + next fit
        const val PREV_MIN_HEIGHT_DP = 76 // previous + current + next fit
        const val HEADER_MIN_HEIGHT_DP = 104 // title/artist line fits above the three lyric lines
        const val DEFAULT_WIDTH_DP = 340
        const val DEFAULT_HEIGHT_DP = 96
        const val MAX_HEIGHT_DP = 220
        const val SIDE_PADDING_DP = 28
        const val GLOW_RADIUS_DP = 10
        const val CONTROLS_FADE_DELAY_MS = 3000L
        const val SLIDE_DISTANCE_DP = 18
        const val SLIDE_DURATION_MS = 240L
        const val IDLE_BG = 0x00000000
        const val EDIT_BG = 0x55000000
        val GLOW_COLOR: Int = Color.parseColor("#7C4DFF")
        val DIM_TEXT_COLOR: Int = Color.parseColor("#CCFFFFFF")
        val DIM_SHADOW_COLOR: Int = Color.parseColor("#CC000000")
        val HEADER_COLOR: Int = Color.parseColor("#99FFFFFF")
        val SUNG_COLOR: Int = Color.WHITE
        val UNSUNG_COLOR: Int = Color.parseColor("#66FFFFFF")
    }
}

/**
 * Counts this app's started (visible) activities so the floating lyrics window can hide itself
 * while My Player is open. Updated from RhythmApplication's activity lifecycle callbacks.
 */
object AppVisibility {
    @Volatile
    private var startedActivities = 0

    fun onActivityStarted() {
        startedActivities++
    }

    fun onActivityStopped() {
        if (startedActivities > 0) startedActivities--
    }

    fun isOpen(): Boolean = startedActivities > 0
}

/** One word of the current lyric line with its timing (milliseconds). */
class OverlayWord(val text: String, val isPart: Boolean, val start: Long, val end: Long)

/** Joins words the same way the app does: syllable parts attach with no space. */
internal fun joinOverlayWords(words: List<OverlayWord>): String = buildString {
    words.forEachIndexed { i, w ->
        if (i > 0 && !(w.isPart && w.text.isNotEmpty())) append(' ')
        append(w.text)
    }
}
