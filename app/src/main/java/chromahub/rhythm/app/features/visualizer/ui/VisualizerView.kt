package chromahub.rhythm.app.features.visualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import chromahub.rhythm.app.features.visualizer.data.VisualizerColorMode
import chromahub.rhythm.app.features.visualizer.data.VisualizerConfig
import chromahub.rhythm.app.features.visualizer.data.VisualizerDirection
import chromahub.rhythm.app.features.visualizer.data.VisualizerStyle
import chromahub.rhythm.app.features.visualizer.engine.AudioFrame
import chromahub.rhythm.app.features.visualizer.engine.BandMapper
import chromahub.rhythm.app.features.visualizer.engine.VisualizerHub
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the visualizer into its own bounds. It only draws: audio comes from [VisualizerHub] (or from
 * a synthetic signal when [demoMode] is on and nothing is playing), the look comes from [config].
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), VisualizerHub.Listener {

    var config: VisualizerConfig = VisualizerConfig()
        set(value) {
            val old = field
            field = value
            if (old.barCount != value.barCount || old.style != value.style) resizeBuffers()
            shaderDirty = true
            invalidate()
        }

    /** Show a synthetic animation when no real audio is arriving (used by the settings preview). */
    var demoMode: Boolean = false

    private val density = context.resources.displayMetrics.density
    private val mapper = BandMapper()

    private var targets = FloatArray(0)
    private var display = FloatArray(0)
    private var waveTargets = FloatArray(0)
    private var waveDisplay = FloatArray(0)

    private var pendingFrame: AudioFrame? = null
    private var frameDirty = false
    private var lastFrameAt = 0L
    private var lastDrawNanos = 0L
    private var shaderDirty = true
    private var attached = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val path = Path()

    init {
        resizeBuffers()
    }

    // ---- lifecycle ----
    private var active = false

    /** Listen to the hub (and so keep audio capture running) only while this view is visible. */
    private fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) {
            VisualizerHub.addListener(this)
            VisualizerHub.acquire()
            lastDrawNanos = 0L
            postInvalidateOnAnimation()
        } else {
            VisualizerHub.removeListener(this)
            VisualizerHub.release()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        setActive(visibility == VISIBLE)
        postInvalidateOnAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (attached) setActive(isVisible)
    }

    override fun onDetachedFromWindow() {
        attached = false
        setActive(false)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderDirty = true
    }

    override fun onFrame(frame: AudioFrame) {
        pendingFrame = frame
        frameDirty = true
        lastFrameAt = System.nanoTime()
        if (attached) postInvalidateOnAnimation()
    }

    private fun resizeBuffers() {
        val n = config.barCount.coerceIn(4, 128)
        if (targets.size != n) {
            targets = FloatArray(n)
            display = FloatArray(n)
        }
        val w = (n * 2).coerceIn(32, 256)
        if (waveTargets.size != w) {
            waveTargets = FloatArray(w)
            waveDisplay = FloatArray(w)
        }
    }

    // ---- drawing ----
    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastDrawNanos == 0L) 0.016f else ((now - lastDrawNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastDrawNanos = now

        updateTargets(now)
        val moving = stepSmoothing(dt)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0f && h > 0f) {
            drawBackground(canvas, w, h)
            if (shaderDirty) rebuildShader(w, h)
            when (config.style) {
                VisualizerStyle.BARS -> drawBars(canvas, w, h, false)
                VisualizerStyle.MIRRORED -> drawBars(canvas, w, h, true)
                VisualizerStyle.LINES -> drawLine(canvas, w, h)
                VisualizerStyle.WAVEFORM -> drawWave(canvas, w, h)
            }
        }

        if (attached && (moving || demoMode)) {
            postInvalidateOnAnimation()
        } else {
            lastDrawNanos = 0L
        }
    }

    private fun updateTargets(nowNanos: Long) {
        val fresh = (nowNanos - lastFrameAt) < 450_000_000L
        val frame = pendingFrame
        if (frame != null && fresh) {
            if (frameDirty) {
                frameDirty = false
                mapper.map(frame, targets.size, config.sensitivity, targets)
                // downsample the time-domain waveform for the waveform style
                val src = frame.waveform
                val step = max(1, src.size / waveTargets.size)
                for (i in waveTargets.indices) {
                    var best = 0f
                    val from = i * step
                    val to = min(src.size, from + step)
                    for (k in from until to) if (abs(src[k]) > abs(best)) best = src[k]
                    waveTargets[i] = (best * config.sensitivity * 1.6f).coerceIn(-1f, 1f)
                }
            }
        } else if (demoMode) {
            val t = nowNanos / 1e9f
            for (i in targets.indices) {
                val p = i.toFloat() / targets.size
                val bass = 0.55f + 0.45f * sin(t * 3.1f)
                val v = (0.45f + 0.35f * sin(t * 2.0f + i * 0.45f)) *
                    (0.6f + 0.4f * sin(t * 0.8f + i * 0.13f)) * (1f - 0.45f * p) * (0.7f + 0.3f * bass)
                targets[i] = (v * config.sensitivity).coerceIn(0.03f, 1f)
            }
            for (i in waveTargets.indices) {
                val x = i.toFloat() / waveTargets.size
                waveTargets[i] = (0.55f * sin(x * 2f * PI.toFloat() * 3f + t * 4f) +
                    0.25f * sin(x * 2f * PI.toFloat() * 9f - t * 6f)) * config.sensitivity.coerceAtMost(1.5f)
            }
        } else {
            // no signal: let everything fall back to the baseline
            java.util.Arrays.fill(targets, 0f)
            java.util.Arrays.fill(waveTargets, 0f)
        }
    }

    /** Moves the displayed values toward the targets; fast rise, slower fall. @return still moving */
    private fun stepSmoothing(dt: Float): Boolean {
        val speed = config.animationSpeed
        val smooth = config.smoothing
        val attack = 1f - exp(-(18f * speed) * (1f - smooth * 0.85f) * dt)
        val release = 1f - exp(-(7f * speed) * (1f - smooth * 0.8f) * dt)
        var moving = false
        for (i in display.indices) {
            val d = targets[i] - display[i]
            display[i] += d * (if (d > 0f) attack else release)
            if (abs(d) > 0.002f) moving = true
        }
        val waveRate = 1f - exp(-(22f * speed) * (1f - smooth * 0.8f) * dt)
        for (i in waveDisplay.indices) {
            val d = waveTargets[i] - waveDisplay[i]
            waveDisplay[i] += d * waveRate
            if (abs(d) > 0.004f) moving = true
        }
        return moving
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val a = config.backgroundAlpha
        if (a <= 0.001f) return
        bgPaint.color = config.backgroundColor
        bgPaint.alpha = (a * 255f).toInt().coerceIn(0, 255)
        val r = config.cornerRadiusDp * density * 1.5f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bgPaint)
    }

    // ---- colours ----
    private fun colorAt(t: Float): Int {
        val colors = config.multiColors
        if (colors.size == 1) return colors[0]
        val pos = t.coerceIn(0f, 1f) * (colors.size - 1)
        val i = min(pos.toInt(), colors.size - 2)
        val f = pos - i
        return blend(colors[i], colors[i + 1], f)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        return Color.argb(255, r, g, bl)
    }

    private fun rebuildShader(w: Float, h: Float) {
        shaderDirty = false
        val c = config
        val shader: Shader? = when (c.colorMode) {
            VisualizerColorMode.SOLID -> null
            VisualizerColorMode.GRADIENT -> {
                val rad = Math.toRadians(c.gradientAngle.toDouble())
                val dx = cos(rad).toFloat()
                val dy = sin(rad).toFloat()
                val len = abs(w * dx) + abs(h * dy)
                val cx = w / 2f
                val cy = h / 2f
                val colors = if (c.useMidColor) {
                    intArrayOf(c.gradientStart, c.gradientMid, c.gradientEnd)
                } else {
                    intArrayOf(c.gradientStart, c.gradientEnd)
                }
                LinearGradient(
                    cx - dx * len / 2f, cy - dy * len / 2f,
                    cx + dx * len / 2f, cy + dy * len / 2f,
                    colors, null, Shader.TileMode.CLAMP
                )
            }
            VisualizerColorMode.MULTICOLOR -> {
                val horizontal = isVerticalBars()
                val colors = IntArray(c.multiColors.size) { c.multiColors[it] }
                if (horizontal) {
                    LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(0f, 0f, 0f, h, colors, null, Shader.TileMode.CLAMP)
                }
            }
        }
        fillPaint.shader = shader
        strokePaint.shader = shader
        if (shader == null) {
            fillPaint.color = c.solidColor
            strokePaint.color = c.solidColor
        }
    }

    /** True when bars stand side by side along the X axis (bottom-up / top-down). */
    private fun isVerticalBars(): Boolean =
        config.direction == VisualizerDirection.BOTTOM_UP || config.direction == VisualizerDirection.TOP_DOWN

    private fun applyAlpha(paint: Paint, factor: Float) {
        paint.alpha = (config.opacity * factor * 255f).toInt().coerceIn(0, 255)
    }

    // ---- BARS / MIRRORED ----
    private fun drawBars(canvas: Canvas, w: Float, h: Float, mirrored: Boolean) {
        val c = config
        val n = display.size
        val vertical = isVerticalBars()
        val along = if (vertical) w else h
        val cross = if (vertical) h else w
        var bw = c.barWidthDp * density
        var sp = c.spacingDp * density
        val total = n * bw + (n - 1) * sp
        if (total > along) {
            val k = along / total
            bw *= k
            sp *= k
        }
        val groupLen = n * bw + (n - 1) * sp
        val start = (along - groupLen) / 2f
        val minLen = 2f * density
        val radius = c.cornerRadiusDp * density
        val glow = c.glow

        for (pass in 0..3) {
            // pass 3 = the bars themselves; passes 0..2 = soft glow layers underneath
            val grow = if (pass < 3) glow * 7f * density * (3 - pass) / 3f else 0f
            if (pass < 3 && glow < 0.02f) continue
            applyAlpha(fillPaint, if (pass < 3) glow * 0.16f else 1f)
            for (i in 0 until n) {
                val len = max(display[i] * cross, minLen)
                val a = start + i * (bw + sp)
                when (c.direction) {
                    VisualizerDirection.BOTTOM_UP ->
                        if (mirrored) rect.set(a, (h - len) / 2f, a + bw, (h + len) / 2f)
                        else rect.set(a, h - len, a + bw, h)
                    VisualizerDirection.TOP_DOWN ->
                        if (mirrored) rect.set(a, (h - len) / 2f, a + bw, (h + len) / 2f)
                        else rect.set(a, 0f, a + bw, len)
                    VisualizerDirection.LEFT_TO_RIGHT ->
                        if (mirrored) rect.set((w - len) / 2f, a, (w + len) / 2f, a + bw)
                        else rect.set(0f, a, len, a + bw)
                    VisualizerDirection.RIGHT_TO_LEFT ->
                        if (mirrored) rect.set((w - len) / 2f, a, (w + len) / 2f, a + bw)
                        else rect.set(w - len, a, w, a + bw)
                }
                if (grow > 0f) rect.inset(-grow, -grow)
                val r = min(radius + grow, min(rect.width(), rect.height()) / 2f)
                canvas.drawRoundRect(rect, r, r, fillPaint)
            }
        }
    }

    // ---- LINES: a smooth curve through the band tops ----
    private fun drawLine(canvas: Canvas, w: Float, h: Float) {
        val n = display.size
        if (n < 2) return
        val vertical = isVerticalBars()
        val along = if (vertical) w else h
        val cross = if (vertical) h else w
        val pad = 3f * density
        buildCurve(path, display, n, along, cross, pad, vertical, signed = false)
        strokeWidth(max(2f * density, config.barWidthDp * density * 0.5f))
        drawStroked(canvas)
    }

    // ---- WAVEFORM: the actual time-domain signal around the centre line ----
    private fun drawWave(canvas: Canvas, w: Float, h: Float) {
        val n = waveDisplay.size
        if (n < 2) return
        val vertical = isVerticalBars()
        val along = if (vertical) w else h
        val cross = if (vertical) h else w
        buildCurve(path, waveDisplay, n, along, cross, 3f * density, vertical, signed = true)
        strokeWidth(max(2f * density, config.barWidthDp * density * 0.4f))
        drawStroked(canvas)
    }

    private fun strokeWidth(px: Float) {
        strokePaint.strokeWidth = px
    }

    private fun drawStroked(canvas: Canvas) {
        val glow = config.glow
        if (glow > 0.02f) {
            val base = strokePaint.strokeWidth
            for (pass in 0..2) {
                strokePaint.strokeWidth = base + glow * 8f * density * (3 - pass) / 3f
                applyAlpha(strokePaint, glow * 0.16f)
                canvas.drawPath(path, strokePaint)
            }
            strokePaint.strokeWidth = base
        }
        applyAlpha(strokePaint, 1f)
        canvas.drawPath(path, strokePaint)
    }

    /**
     * Builds a smooth curve through [values]. Unsigned values (0..1) rise from the baseline the
     * direction points away from; signed values (-1..1) swing around the centre line.
     */
    private fun buildCurve(
        out: Path,
        values: FloatArray,
        n: Int,
        along: Float,
        cross: Float,
        pad: Float,
        vertical: Boolean,
        signed: Boolean
    ) {
        out.reset()
        val usable = along - 2f * pad
        var px = 0f
        var py = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            val a = pad + t * usable
            val v = values[i]
            val offset = (cross / 2f) - v * (cross / 2f - pad)
            val x: Float
            val y: Float
            if (vertical) {
                x = if (config.direction == VisualizerDirection.TOP_DOWN && signed) along - a else a
                y = when {
                    signed -> offset
                    config.direction == VisualizerDirection.BOTTOM_UP -> cross - pad - v * (cross - 2f * pad)
                    else -> pad + v * (cross - 2f * pad)
                }
            } else {
                y = if (config.direction == VisualizerDirection.RIGHT_TO_LEFT && signed) along - a else a
                x = when {
                    signed -> offset
                    config.direction == VisualizerDirection.LEFT_TO_RIGHT -> pad + v * (cross - 2f * pad)
                    else -> cross - pad - v * (cross - 2f * pad)
                }
            }
            if (i == 0) {
                out.moveTo(x, y)
            } else {
                val mx = (px + x) / 2f
                val my = (py + y) / 2f
                out.quadTo(px, py, mx, my)
            }
            px = x
            py = y
        }
        out.lineTo(px, py)
    }
}
