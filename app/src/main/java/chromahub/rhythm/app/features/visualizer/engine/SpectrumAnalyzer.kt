package chromahub.rhythm.app.features.visualizer.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a block of 8-bit waveform samples into FFT magnitudes (Hann window, radix-2 FFT).
 * [size] must be a power of two.
 */
class SpectrumAnalyzer(val size: Int) {
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val window = FloatArray(size)
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val reversed = IntArray(size)

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "size must be a power of two" }
        for (i in 0 until size) {
            window[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))).toFloat()
        }
        for (k in 0 until size / 2) {
            cosTable[k] = cos(2.0 * PI * k / size).toFloat()
            sinTable[k] = sin(2.0 * PI * k / size).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            reversed[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /** [waveform] is Android Visualizer data: unsigned 8-bit, silence = 128. */
    fun analyze(waveform: ByteArray, sampleRateHz: Int): AudioFrame {
        val samples = FloatArray(size)
        val count = minOf(size, waveform.size)
        for (i in 0 until count) {
            samples[i] = (((waveform[i].toInt() and 0xFF) - 128) / 128f)
        }
        for (i in 0 until size) {
            val j = reversed[i]
            re[j] = samples[i] * window[i]
            im[j] = 0f
        }
        fft()
        val half = size / 2
        val magnitudes = FloatArray(half)
        // Hann window halves the amplitude, and a real signal splits it over two bins: x4 / N.
        val norm = 4f / size
        for (k in 0 until half) {
            magnitudes[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
        }
        return AudioFrame(samples, magnitudes, sampleRateHz)
    }

    private fun fft() {
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val a = i + j
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
