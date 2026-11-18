package chromahub.rhythm.app.features.visualizer.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Maps FFT magnitudes to [bars] log-spaced frequency bands (bass on the left, treble on the right)
 * and turns them into 0..1 levels. Low bands are boosted less than highs so music looks balanced.
 */
class BandMapper {

    /** @param out receives [bars] values in 0..1 */
    fun map(frame: AudioFrame, bars: Int, sensitivity: Float, out: FloatArray) {
        val mags = frame.magnitudes
        if (mags.isEmpty() || bars <= 0) return
        val binHz = frame.sampleRateHz.toFloat() / (mags.size * 2f)
        val fMin = 40f
        val fMax = minOf(16000f, frame.sampleRateHz / 2f * 0.95f)
        val ratio = fMax / fMin
        for (i in 0 until minOf(bars, out.size)) {
            val f0 = fMin * ratio.pow(i.toFloat() / bars)
            val f1 = fMin * ratio.pow((i + 1).toFloat() / bars)
            val lo = f0 / binHz
            val hi = f1 / binHz
            var peak = 0f
            val first = ceil(lo).toInt().coerceIn(1, mags.size - 1)
            val last = floor(hi).toInt().coerceIn(1, mags.size - 1)
            if (last >= first) {
                for (b in first..last) if (mags[b] > peak) peak = mags[b]
            }
            // Narrow bands (few bins): interpolate at the band centre so neighbours differ smoothly.
            val centre = ((lo + hi) / 2f).coerceIn(1f, (mags.size - 2).toFloat())
            val base = floor(centre).toInt()
            val frac = centre - base
            val interpolated = mags[base] * (1f - frac) + mags[base + 1] * frac
            if (interpolated > peak) peak = interpolated

            val centreHz = (f0 + f1) / 2f
            val tiltDb = 3f * (ln(maxOf(centreHz, 100f) / 100f) / ln(2f)) * 0.7f
            val db = 20f * log10(peak + 1e-6f) + tiltDb
            val level = ((db + 62f) / 58f) * sensitivity
            out[i] = level.coerceIn(0f, 1f)
        }
    }
}
