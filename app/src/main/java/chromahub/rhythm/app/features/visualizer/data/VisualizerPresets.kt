package chromahub.rhythm.app.features.visualizer.data

/** Built-in preset themes. */
object VisualizerPresets {

    private fun c(hex: Long): Int = hex.toInt()

    val all: List<VisualizerProfile> = listOf(
        VisualizerProfile(
            "preset_neon", "Neon bars", true,
            VisualizerConfig(
                barCount = 48, barWidthDp = 5f, spacingDp = 3f, glow = 0.6f, cornerRadiusDp = 3f,
                colorMode = VisualizerColorMode.GRADIENT,
                gradientStart = c(0xFF7C4DFF), gradientEnd = c(0xFF18FFFF), useMidColor = false,
                gradientAngle = 270f
            )
        ),
        VisualizerProfile(
            "preset_sunset", "Sunset", true,
            VisualizerConfig(
                barCount = 64, barWidthDp = 4f, spacingDp = 2f, glow = 0.35f, cornerRadiusDp = 2f,
                colorMode = VisualizerColorMode.GRADIENT,
                gradientStart = c(0xFFFF6F00), gradientMid = c(0xFFFF4081), gradientEnd = c(0xFF7C4DFF),
                useMidColor = true, gradientAngle = 270f
            )
        ),
        VisualizerProfile(
            "preset_rainbow", "Rainbow", true,
            VisualizerConfig(
                barCount = 40, barWidthDp = 6f, spacingDp = 3f, glow = 0.4f, cornerRadiusDp = 6f,
                colorMode = VisualizerColorMode.MULTICOLOR
            )
        ),
        VisualizerProfile(
            "preset_mono_lines", "Mono line", true,
            VisualizerConfig(
                barCount = 56, barWidthDp = 4f, spacingDp = 2f, glow = 0.15f,
                style = VisualizerStyle.LINES, colorMode = VisualizerColorMode.SOLID,
                solidColor = c(0xFFFFFFFF), opacity = 0.85f
            )
        ),
        VisualizerProfile(
            "preset_mirror", "Mirror", true,
            VisualizerConfig(
                barCount = 36, barWidthDp = 6f, spacingDp = 4f, glow = 0.5f, cornerRadiusDp = 8f,
                style = VisualizerStyle.MIRRORED, colorMode = VisualizerColorMode.GRADIENT,
                gradientStart = c(0xFF00E5FF), gradientEnd = c(0xFF2979FF), gradientAngle = 270f,
                heightDp = 180
            )
        ),
        VisualizerProfile(
            "preset_wave", "Pulse wave", true,
            VisualizerConfig(
                barCount = 64, barWidthDp = 5f, glow = 0.5f,
                style = VisualizerStyle.WAVEFORM, colorMode = VisualizerColorMode.SOLID,
                solidColor = c(0xFF69F0AE), heightDp = 120
            )
        ),
        VisualizerProfile(
            "preset_minimal", "Minimal", true,
            VisualizerConfig(
                barCount = 24, barWidthDp = 8f, spacingDp = 6f, glow = 0f, cornerRadiusDp = 4f,
                colorMode = VisualizerColorMode.SOLID, solidColor = c(0xFFFFFFFF), opacity = 0.5f,
                heightDp = 100
            )
        )
    )

    fun find(id: String?): VisualizerProfile? = all.firstOrNull { it.id == id }
}
