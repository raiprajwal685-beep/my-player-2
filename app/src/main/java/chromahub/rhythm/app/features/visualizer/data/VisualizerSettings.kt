package chromahub.rhythm.app.features.visualizer.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.visualizerDataStore by preferencesDataStore(name = "visualizer_settings")

/** Everything that is persisted for the visualizer. */
data class VisualizerState(
    val masterEnabled: Boolean = false,
    val showInPlayer: Boolean = true,
    val showInOverlay: Boolean = true,
    /** The configuration being shown and edited (what the preview and the displays use). */
    val working: VisualizerConfig = VisualizerConfig(),
    /** Profile the working config was last loaded from or saved to (null = unsaved custom). */
    val selectedProfileId: String? = null,
    val userProfiles: List<VisualizerProfile> = emptyList()
) {
    val allProfiles: List<VisualizerProfile> get() = VisualizerPresets.all + userProfiles

    val selectedProfile: VisualizerProfile? get() = allProfiles.firstOrNull { it.id == selectedProfileId }
}

/**
 * Single source of truth for the visualizer settings. UI and renderers read [state]; every change is
 * applied to [state] immediately and written to DataStore shortly after (so dragging a slider does
 * not hammer the disk). The settings system knows nothing about audio capture or drawing.
 */
object VisualizerSettings {
    private const val TAG = "VisualizerSettings"

    private val KEY_MASTER = booleanPreferencesKey("master_enabled")
    private val KEY_IN_PLAYER = booleanPreferencesKey("show_in_player")
    private val KEY_IN_OVERLAY = booleanPreferencesKey("show_in_overlay")
    private val KEY_WORKING = stringPreferencesKey("working_config")
    private val KEY_SELECTED = stringPreferencesKey("selected_profile")
    private val KEY_PROFILES = stringPreferencesKey("user_profiles")

    private val _state = MutableStateFlow(VisualizerState())
    val state: StateFlow<VisualizerState> = _state.asStateFlow()

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var idCounter = 0

    /** Safe to call many times; loads the saved settings on the first call. */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        scope.launch {
            try {
                val prefs = app.visualizerDataStore.data.first()
                val loaded = VisualizerState(
                    masterEnabled = prefs[KEY_MASTER] ?: false,
                    showInPlayer = prefs[KEY_IN_PLAYER] ?: true,
                    showInOverlay = prefs[KEY_IN_OVERLAY] ?: true,
                    working = prefs[KEY_WORKING]?.let { parseConfig(it) } ?: VisualizerConfig(),
                    selectedProfileId = prefs[KEY_SELECTED],
                    userProfiles = prefs[KEY_PROFILES]?.let { parseProfiles(it) } ?: emptyList()
                )
                _state.value = loaded
            } catch (e: Exception) {
                Log.w(TAG, "Could not load visualizer settings", e)
            }
        }
    }

    // ---- master / placement switches ----
    fun setMasterEnabled(enabled: Boolean) = update { it.copy(masterEnabled = enabled) }

    fun setShowInPlayer(show: Boolean) = update { it.copy(showInPlayer = show) }

    fun setShowInOverlay(show: Boolean) = update { it.copy(showInOverlay = show) }

    // ---- the configuration being edited ----
    fun updateWorking(transform: (VisualizerConfig) -> VisualizerConfig) =
        update { it.copy(working = transform(it.working).coerced()) }

    // ---- profiles ----
    fun selectProfile(id: String) = update { s ->
        val profile = s.allProfiles.firstOrNull { it.id == id } ?: return@update s
        s.copy(working = profile.config, selectedProfileId = profile.id)
    }

    /** Saves the working config: overwrites the selected user profile, or creates a new one. */
    fun saveCurrent() = update { s ->
        val selected = s.userProfiles.firstOrNull { it.id == s.selectedProfileId }
        if (selected != null) {
            s.copy(userProfiles = s.userProfiles.map {
                if (it.id == selected.id) it.copy(config = s.working) else it
            })
        } else {
            val profile = newProfile(s, "My style " + (s.userProfiles.size + 1), s.working)
            s.copy(userProfiles = s.userProfiles + profile, selectedProfileId = profile.id)
        }
    }

    fun saveAsNew(name: String) = update { s ->
        val profile = newProfile(s, name.trim().ifEmpty { "My style " + (s.userProfiles.size + 1) }, s.working)
        s.copy(userProfiles = s.userProfiles + profile, selectedProfileId = profile.id)
    }

    fun renameProfile(id: String, name: String) = update { s ->
        val clean = name.trim()
        if (clean.isEmpty()) return@update s
        s.copy(userProfiles = s.userProfiles.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun duplicateProfile(id: String) = update { s ->
        val source = s.allProfiles.firstOrNull { it.id == id } ?: return@update s
        val profile = newProfile(s, source.name + " copy", source.config)
        s.copy(userProfiles = s.userProfiles + profile, selectedProfileId = profile.id, working = source.config)
    }

    fun deleteProfile(id: String) = update { s ->
        if (s.userProfiles.none { it.id == id }) return@update s
        s.copy(
            userProfiles = s.userProfiles.filterNot { it.id == id },
            selectedProfileId = if (s.selectedProfileId == id) null else s.selectedProfileId
        )
    }

    /** Throws away unsaved edits: back to the selected profile, or to the defaults. */
    fun resetWorking() = update { s ->
        s.copy(working = s.selectedProfile?.config ?: VisualizerConfig())
    }

    // ---- internals ----
    private fun newProfile(s: VisualizerState, name: String, config: VisualizerConfig): VisualizerProfile {
        idCounter++
        return VisualizerProfile(
            id = "user_" + System.currentTimeMillis() + "_" + idCounter,
            name = name,
            builtIn = false,
            config = config
        )
    }

    private fun update(transform: (VisualizerState) -> VisualizerState) {
        _state.value = transform(_state.value)
        scheduleSave()
    }

    private fun scheduleSave() {
        val app = appContext ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300L)
            val s = _state.value
            try {
                app.visualizerDataStore.edit { prefs ->
                    prefs[KEY_MASTER] = s.masterEnabled
                    prefs[KEY_IN_PLAYER] = s.showInPlayer
                    prefs[KEY_IN_OVERLAY] = s.showInOverlay
                    prefs[KEY_WORKING] = s.working.toJson().toString()
                    val selected = s.selectedProfileId
                    if (selected != null) prefs[KEY_SELECTED] = selected else prefs.remove(KEY_SELECTED)
                    prefs[KEY_PROFILES] = profilesToJson(s.userProfiles)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not save visualizer settings", e)
            }
        }
    }

    private fun profilesToJson(profiles: List<VisualizerProfile>): String {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("config", p.config.toJson())
            )
        }
        return arr.toString()
    }

    private fun parseProfiles(json: String): List<VisualizerProfile> {
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<VisualizerProfile>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cfg = o.optJSONObject("config") ?: continue
                out.add(
                    VisualizerProfile(
                        id = o.optString("id"),
                        name = o.optString("name", "My style"),
                        builtIn = false,
                        config = VisualizerConfig.fromJson(cfg)
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse saved profiles", e)
            emptyList()
        }
    }

    private fun parseConfig(json: String): VisualizerConfig? =
        try {
            VisualizerConfig.fromJson(JSONObject(json))
        } catch (e: Exception) {
            null
        }
}
