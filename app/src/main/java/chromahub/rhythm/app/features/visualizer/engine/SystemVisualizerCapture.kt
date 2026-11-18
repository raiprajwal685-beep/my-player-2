package chromahub.rhythm.app.features.visualizer.engine

import android.media.audiofx.Visualizer
import android.util.Log

/**
 * Audio capture using Android's [Visualizer] API on one audio session. Needs the RECORD_AUDIO
 * permission. Create and call it on the main thread (the callbacks arrive there).
 */
class SystemVisualizerCapture(private val onFrame: (AudioFrame) -> Unit) {
    private var visualizer: Visualizer? = null
    private var analyzer: SpectrumAnalyzer? = null

    val isRunning: Boolean get() = visualizer != null

    /** @return null on success, otherwise a short error description. */
    fun start(audioSessionId: Int): String? {
        stop()
        if (audioSessionId == 0) return "No audio session"
        return try {
            val v = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = minOf(1024, range[1])
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform != null) handle(waveform, samplingRate / 1000)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        // We run our own FFT on the waveform.
                    }
                },
                Visualizer.getMaxCaptureRate(),
                true,
                false
            )
            v.enabled = true
            visualizer = v
            null
        } catch (e: Exception) {
            Log.w(TAG, "Could not start Visualizer on session $audioSessionId", e)
            stop()
            e.message ?: e.javaClass.simpleName
        }
    }

    fun stop() {
        val v = visualizer ?: return
        visualizer = null
        try {
            v.enabled = false
            v.setDataCaptureListener(null, 0, false, false)
            v.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Visualizer", e)
        }
    }

    private fun handle(waveform: ByteArray, sampleRateHz: Int) {
        val size = waveform.size
        if (size < 64 || (size and (size - 1)) != 0) return
        val a = analyzer?.takeIf { it.size == size } ?: SpectrumAnalyzer(size).also { analyzer = it }
        onFrame(a.analyze(waveform, if (sampleRateHz > 0) sampleRateHz else 44100))
    }

    private companion object {
        const val TAG = "VisualizerCapture"
    }
}
