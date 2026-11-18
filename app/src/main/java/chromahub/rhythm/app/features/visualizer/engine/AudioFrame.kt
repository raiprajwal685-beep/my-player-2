package chromahub.rhythm.app.features.visualizer.engine

/**
 * One captured slice of audio, already analysed.
 *
 * @param waveform time-domain samples, -1..1
 * @param magnitudes FFT magnitudes for bins 0 until size/2 (a full-scale sine is about 1.0)
 * @param sampleRateHz sample rate of the captured audio
 */
class AudioFrame(
    val waveform: FloatArray,
    val magnitudes: FloatArray,
    val sampleRateHz: Int
)
