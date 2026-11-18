/*
 * Personal addition: audio visualizer settings model. GPL-3.0-or-later like the rest of the project.
 */

package chromahub.rhythm.app.features.visualizer.data

import org.json.JSONArray
import org.json.JSONObject

enum class VisualizerDirection { BOTTOM_UP, TOP_DOWN, LEFT_TO_RIGHT, RIGHT_TO_LEFT }

enum class VisualizerStyle { BARS, LINES, WAVEFORM, MIRRORED }

enum class VisualizerColorMode { SOLID, GRADIENT, MULTICOLOR }

/** Everything that controls how the visualizer looks and moves. Immutable; use copy(). */
data class VisualizerConfig(
    // Shape and size
    val barCount: Int = 48,
    val barWidthDp: Float = 5f,
    val spacingDp: Float = 3f,
    val heightDp: Int = 140,
    val widthPercent: Float = 1f,
    val positionX: Float = 0.5f,
    val positionY: Float = 1f,
    val cornerRadiusDp: Float = 3f,
    // Motion
    val sensitivity: Float = 1f,
    val animationSpeed: Float = 1f,
    val smoothing: Float = 0.5f,
    // Look
    val opacity: Float = 0.9f,
    val glow: Float = 0.3f,
    val direction: VisualizerDirection = VisualizerDirection.BOTTOM_UP,
    val style: VisualizerStyle = VisualizerStyle.BARS,
    val colorMode: VisualizerColorMode = VisualizerColorMode.GRADIENT,
    val solidColor: Int = 0xFF7C4DFF.toInt(),
    val gradientStart: Int = 0xFF7C4DFF.toInt(),
    val gradientMid: Int = 0xFFE040FB.toInt(),
    val gradientEnd: Int = 0xFF18FFFF.toInt(),
    val useMidColor: Boolean = false,
    val gradientAngle: Float = 270f,
    val multiColors: List<Int> = DEFAULT_MULTI_COLORS,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val backgroundAlpha: Float = 0f
) {
    /** Clamps every value into the range the UI and renderer support. */
    fun coerced(): VisualizerConfig = copy(
        barCount = barCount.coerceIn(4, 128),
        barWidthDp = barWidthDp.coerceIn(1f, 32f),
        spacingDp = spacingDp.coerceIn(0f, 32f),
        heightDp = heightDp.coerceIn(40, 480),
        widthPercent = widthPercent.coerceIn(0.2f, 1f),
        positionX = positionX.coerceIn(0f, 1f),
        positionY = positionY.coerceIn(0f, 1f),
        cornerRadiusDp = cornerRadiusDp.coerceIn(0f, 16f),
        sensitivity = sensitivity.coerceIn(0.2f, 4f),
        animationSpeed = animationSpeed.coerceIn(0.2f, 3f),
        smoothing = smoothing.coerceIn(0f, 0.95f),
        opacity = opacity.coerceIn(0.05f, 1f),
        glow = glow.coerceIn(0f, 1f),
        gradientAngle = ((gradientAngle % 360f) + 360f) % 360f,
        multiColors = if (multiColors.size < 2) DEFAULT_MULTI_COLORS else multiColors.take(8),
        backgroundAlpha = backgroundAlpha.coerceIn(0f, 1f)
    )

    fun toJson(): JSONObject = JSONObject()
        .put("barCount", barCount)
        .put("barWidthDp", barWidthDp.toDouble())
        .put("spacingDp", spacingDp.toDouble())
        .put("heightDp", heightDp)
        .put("widthPercent", widthPercent.toDouble())
        .put("positionX", positionX.toDouble())
        .put("positionY", positionY.toDouble())
        .put("cornerRadiusDp", cornerRadiusDp.toDouble())
        .put("sensitivity", sensitivity.toDouble())
        .put("animationSpeed", animationSpeed.toDouble())
        .put("smoothing", smoothing.toDouble())
        .put("opacity", opacity.toDouble())
        .put("glow", glow.toDouble())
        .put("direction", direction.name)
        .put("style", style.name)
        .put("colorMode", colorMode.name)
        .put("solidColor", solidColor)
        .put("gradientStart", gradientStart)
        .put("gradientMid", gradientMid)
        .put("gradientEnd", gradientEnd)
        .put("useMidColor", useMidColor)
        .put("gradientAngle", gradientAngle.toDouble())
        .put("multiColors", JSONArray(multiColors))
        .put("backgroundColor", backgroundColor)
        .put("backgroundAlpha", backgroundAlpha.toDouble())

    companion object {
        val DEFAULT_MULTI_COLORS: List<Int> = listOf(
            0xFFFF5252.toInt(),
            0xFFFFB74D.toInt(),
            0xFFFFEE58.toInt(),
            0xFF69F0AE.toInt(),
            0xFF40C4FF.toInt(),
            0xFFB388FF.toInt()
        )

        fun fromJson(o: JSONObject): VisualizerConfig {
            val d = VisualizerConfig()
            val colors = ArrayList<Int>()
            val arr = o.optJSONArray("multiColors")
            if (arr != null) {
                for (i in 0 until arr.length()) colors.add(arr.optInt(i))
            }
            return VisualizerConfig(
                barCount = o.optInt("barCount", d.barCount),
                barWidthDp = o.optDouble("barWidthDp", d.barWidthDp.toDouble()).toFloat(),
                spacingDp = o.optDouble("spacingDp", d.spacingDp.toDouble()).toFloat(),
                heightDp = o.optInt("heightDp", d.heightDp),
                widthPercent = o.optDouble("widthPercent", d.widthPercent.toDouble()).toFloat(),
                positionX = o.optDouble("positionX", d.positionX.toDouble()).toFloat(),
                positionY = o.optDouble("positionY", d.positionY.toDouble()).toFloat(),
                cornerRadiusDp = o.optDouble("cornerRadiusDp", d.cornerRadiusDp.toDouble()).toFloat(),
                sensitivity = o.optDouble("sensitivity", d.sensitivity.toDouble()).toFloat(),
                animationSpeed = o.optDouble("animationSpeed", d.animationSpeed.toDouble()).toFloat(),
                smoothing = o.optDouble("smoothing", d.smoothing.toDouble()).toFloat(),
                opacity = o.optDouble("opacity", d.opacity.toDouble()).toFloat(),
                glow = o.optDouble("glow", d.glow.toDouble()).toFloat(),
                direction = enumOrDefault(o.optString("direction"), d.direction),
                style = enumOrDefault(o.optString("style"), d.style),
                colorMode = enumOrDefault(o.optString("colorMode"), d.colorMode),
                solidColor = o.optInt("solidColor", d.solidColor),
                gradientStart = o.optInt("gradientStart", d.gradientStart),
                gradientMid = o.optInt("gradientMid", d.gradientMid),
                gradientEnd = o.optInt("gradientEnd", d.gradientEnd),
                useMidColor = o.optBoolean("useMidColor", d.useMidColor),
                gradientAngle = o.optDouble("gradientAngle", d.gradientAngle.toDouble()).toFloat(),
                multiColors = if (colors.size >= 2) colors else d.multiColors,
                backgroundColor = o.optInt("backgroundColor", d.backgroundColor),
                backgroundAlpha = o.optDouble("backgroundAlpha", d.backgroundAlpha.toDouble()).toFloat()
            ).coerced()
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
            try {
                enumValueOf<T>(name)
            } catch (e: Exception) {
                default
            }
    }
}

/** A named, saveable configuration. Built-in presets can't be edited, renamed or deleted. */
data class VisualizerProfile(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val config: VisualizerConfig
)
