package chromahub.rhythm.app.features.visualizer.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import chromahub.rhythm.app.features.visualizer.data.VisualizerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Connects audio capture to whoever wants to draw it. The playback service only tells it which
 * audio session is playing ([setAudioSessionId]); views register as listeners. Capture runs only
 * while the visualizer is enabled AND at least one view is showing, so it costs nothing otherwise.
 *
 * Everything here runs on the main thread (calls from other threads are posted there).
 */
object VisualizerHub {

    enum class Status { OFF, IDLE, NO_PERMISSION, NO_SESSION, ERROR, RUNNING }

    fun interface Listener {
        fun onFrame(frame: AudioFrame)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    private var appContext: Context? = null
    private var sessionId = 0
    private var consumers = 0
    private var masterEnabled = false
    private var capture: SystemVisualizerCapture? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        VisualizerSettings.init(context)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            VisualizerSettings.state
                .map { it.masterEnabled }
                .distinctUntilChanged()
                .collect {
                    masterEnabled = it
                    evaluate()
                }
        }
    }

    fun setAudioSessionId(id: Int) = onMain {
        if (id != sessionId) {
            sessionId = id
            restart()
        }
    }

    fun acquire() = onMain {
        consumers++
        evaluate()
    }

    fun release() = onMain {
        if (consumers > 0) consumers--
        evaluate()
    }

    fun addListener(listener: Listener) = onMain {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) = onMain {
        listeners.remove(listener)
    }

    fun hasPermission(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Call after the permission dialog or when returning to the app. */
    fun refresh() = onMain { restart() }

    private fun restart() {
        capture?.stop()
        evaluate()
    }

    private fun evaluate() {
        if (!masterEnabled) {
            capture?.stop()
            _status.value = Status.OFF
            return
        }
        if (consumers <= 0) {
            capture?.stop()
            _status.value = Status.IDLE
            return
        }
        if (!hasPermission()) {
            capture?.stop()
            _status.value = Status.NO_PERMISSION
            return
        }
        if (sessionId == 0) {
            capture?.stop()
            _status.value = Status.NO_SESSION
            return
        }
        val c = capture ?: SystemVisualizerCapture { frame -> dispatch(frame) }.also { capture = it }
        if (c.isRunning) {
            _status.value = Status.RUNNING
            return
        }
        val error = c.start(sessionId)
        lastError = error
        _status.value = if (error == null) Status.RUNNING else Status.ERROR
    }

    private fun dispatch(frame: AudioFrame) {
        for (i in listeners.indices.reversed()) {
            if (i < listeners.size) listeners[i].onFrame(frame)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}
