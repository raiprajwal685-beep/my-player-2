/*
 * Personal addition: remembers playback speed and pitch per song in a small JSON file
 * (files/song_playback_settings.json). Licensed GPL-3.0-or-later, like the rest of the project.
 */

package chromahub.rhythm.app.infrastructure.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * File format:
 * { "version": 1, "songs": { "<mediaId>": { "speed": 1.25, "pitch": 0.95 } } }
 *
 * Songs that have no entry use the app default speed. An entry is removed again when its values
 * equal the defaults, so the file only lists songs you actually changed.
 */
object SongPlaybackStore {
    private const val TAG = "SongPlaybackStore"
    private const val FILE_NAME = "song_playback_settings.json"

    class Entry(val speed: Float, val pitch: Float)

    private val lock = Any()
    private var cache: MutableMap<String, Entry>? = null
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { writeNow() }

    fun get(context: Context, songId: String): Entry? = synchronized(lock) {
        load(context)[songId]
    }

    fun save(
        context: Context,
        songId: String,
        speed: Float,
        pitch: Float,
        defaultSpeed: Float,
        defaultPitch: Float
    ) {
        synchronized(lock) {
            val map = load(context)
            if (nearly(speed, defaultSpeed) && nearly(pitch, defaultPitch)) {
                map.remove(songId)
            } else {
                map[songId] = Entry(speed, pitch)
            }
        }
        // Coalesce rapid changes (e.g. dragging a slider) into one write.
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 600L)
    }

    private fun nearly(a: Float, b: Float): Boolean = abs(a - b) < 0.001f

    private fun load(context: Context): MutableMap<String, Entry> {
        cache?.let { return it }
        val app = context.applicationContext
        appContext = app
        val map = LinkedHashMap<String, Entry>()
        try {
            val file = File(app.filesDir, FILE_NAME)
            if (file.exists()) {
                val songs = JSONObject(file.readText()).optJSONObject("songs")
                if (songs != null) {
                    val keys = songs.keys()
                    while (keys.hasNext()) {
                        val id = keys.next()
                        val o = songs.optJSONObject(id) ?: continue
                        map[id] = Entry(
                            o.optDouble("speed", 1.0).toFloat(),
                            o.optDouble("pitch", 1.0).toFloat()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $FILE_NAME", e)
        }
        cache = map
        return map
    }

    private fun writeNow() {
        val app = appContext ?: return
        val snapshot: Map<String, Entry> = synchronized(lock) {
            LinkedHashMap(cache ?: return)
        }
        try {
            val songs = JSONObject()
            for ((id, e) in snapshot) {
                songs.put(
                    id,
                    JSONObject().put("speed", e.speed.toDouble()).put("pitch", e.pitch.toDouble())
                )
            }
            val json = JSONObject().put("version", 1).put("songs", songs)
            val tmp = File(app.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(json.toString(2))
            val target = File(app.filesDir, FILE_NAME)
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write $FILE_NAME", e)
        }
    }
}
