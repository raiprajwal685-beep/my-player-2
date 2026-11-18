package chromahub.rhythm.app.shared.presentation.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.visualizer.data.VisualizerColorMode
import chromahub.rhythm.app.features.visualizer.data.VisualizerConfig
import chromahub.rhythm.app.features.visualizer.data.VisualizerDirection
import chromahub.rhythm.app.features.visualizer.data.VisualizerSettings
import chromahub.rhythm.app.features.visualizer.data.VisualizerStyle
import chromahub.rhythm.app.features.visualizer.engine.VisualizerHub
import chromahub.rhythm.app.features.visualizer.ui.VisualizerSurface
import chromahub.rhythm.app.shared.presentation.components.common.CollapsibleHeaderScreen
import chromahub.rhythm.app.ui.LocalMiniPlayerPadding
import kotlin.math.roundToInt

/** The visualizer customization panel: master switch, live preview, presets, profiles and every setting. */
@Composable
fun VisualizerSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        VisualizerSettings.init(context)
        VisualizerHub.init(context)
    }
    val state by VisualizerSettings.state.collectAsState()
    val status by VisualizerHub.status.collectAsState()
    val cfg = state.working
    val hasPermission = remember(status) { VisualizerHub.hasPermission() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { VisualizerHub.refresh() }

    var nameDialog by remember { mutableStateOf<String?>(null) } // "save" or "rename"
    var confirmDelete by remember { mutableStateOf(false) }
    val selectedIsUser = state.userProfiles.any { it.id == state.selectedProfileId }

    fun edit(transform: (VisualizerConfig) -> VisualizerConfig) = VisualizerSettings.updateWorking(transform)

    CollapsibleHeaderScreen(
        title = "Visualizer",
        showBackButton = true,
        onBackClick = onBackClick
    ) { modifier ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()),
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // ---- master switches ----
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SwitchRow("Audio visualizer", "Live spectrum that reacts to the music", state.masterEnabled) { on ->
                            VisualizerSettings.setMasterEnabled(on)
                            if (on && !VisualizerHub.hasPermission()) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        SwitchRow("Show in the player", null, state.showInPlayer) { VisualizerSettings.setShowInPlayer(it) }
                        SwitchRow("Show in floating lyrics", null, state.showInOverlay) { VisualizerSettings.setShowInOverlay(it) }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = statusText(status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (state.masterEnabled && !hasPermission) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "The visualizer needs the Record audio permission. Android requires it to read the " +
                                    "music's own audio for analysis; nothing is recorded from the microphone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                                    Text("Allow")
                                }
                                TextButton(onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData(Uri.parse("package:" + context.packageName))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }) { Text("App settings") }
                            }
                        }
                    }
                }
            }

            // ---- live preview ----
            item {
                SectionTitle("Live preview")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cfg.heightDp.coerceIn(100, 220).dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF111116))
                ) {
                    VisualizerSurface(
                        config = cfg,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        demoMode = true
                    )
                }
                Text(
                    "Shows your music when it is playing, otherwise a demo animation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // ---- presets and saved styles ----
            item {
                SectionTitle("Presets and saved styles")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.allProfiles) { p ->
                        FilterChip(
                            selected = p.id == state.selectedProfileId,
                            onClick = { VisualizerSettings.selectProfile(p.id) },
                            label = { Text(p.name) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { AssistChip(onClick = { VisualizerSettings.saveCurrent() }, label = { Text("Save") }) }
                    item { AssistChip(onClick = { nameDialog = "save" }, label = { Text("Save as...") }) }
                    item {
                        AssistChip(
                            onClick = { nameDialog = "rename" },
                            enabled = selectedIsUser,
                            label = { Text("Rename") }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { state.selectedProfileId?.let { VisualizerSettings.duplicateProfile(it) } },
                            enabled = state.selectedProfileId != null,
                            label = { Text("Duplicate") }
                        )
                    }
                    item { AssistChip(onClick = { VisualizerSettings.resetWorking() }, label = { Text("Reset") }) }
                    item {
                        AssistChip(
                            onClick = { confirmDelete = true },
                            enabled = selectedIsUser,
                            label = { Text("Delete") }
                        )
                    }
                }
                Text(
                    "Changes apply right away. Tap Save to keep them; Reset goes back to the selected style.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // ---- style / direction / colour mode ----
            item {
                SectionTitle("Style")
                ChipChoice(
                    listOf(
                        VisualizerStyle.BARS to "Bars",
                        VisualizerStyle.LINES to "Lines",
                        VisualizerStyle.WAVEFORM to "Waveform",
                        VisualizerStyle.MIRRORED to "Mirrored"
                    ),
                    cfg.style
                ) { v -> edit { it.copy(style = v) } }
                Spacer(modifier = Modifier.height(10.dp))
                SectionTitle("Direction")
                ChipChoice(
                    listOf(
                        VisualizerDirection.BOTTOM_UP to "Bottom up",
                        VisualizerDirection.TOP_DOWN to "Top down",
                        VisualizerDirection.LEFT_TO_RIGHT to "Left to right",
                        VisualizerDirection.RIGHT_TO_LEFT to "Right to left"
                    ),
                    cfg.direction
                ) { v -> edit { it.copy(direction = v) } }
                Spacer(modifier = Modifier.height(10.dp))
                SectionTitle("Color")
                ChipChoice(
                    listOf(
                        VisualizerColorMode.SOLID to "Solid",
                        VisualizerColorMode.GRADIENT to "Gradient",
                        VisualizerColorMode.MULTICOLOR to "Multi-color"
                    ),
                    cfg.colorMode
                ) { v -> edit { it.copy(colorMode = v) } }
            }

            // ---- colours ----
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        when (cfg.colorMode) {
                            VisualizerColorMode.SOLID -> {
                                ColorPickerRow("Color", cfg.solidColor) { c -> edit { it.copy(solidColor = c) } }
                            }
                            VisualizerColorMode.GRADIENT -> {
                                ColorPickerRow("Start color", cfg.gradientStart) { c -> edit { it.copy(gradientStart = c) } }
                                SwitchRow("Middle color", null, cfg.useMidColor) { on -> edit { it.copy(useMidColor = on) } }
                                if (cfg.useMidColor) {
                                    ColorPickerRow("Middle color", cfg.gradientMid) { c -> edit { it.copy(gradientMid = c) } }
                                }
                                ColorPickerRow("End color", cfg.gradientEnd) { c -> edit { it.copy(gradientEnd = c) } }
                                LabeledSlider(
                                    "Gradient angle", cfg.gradientAngle, 0f..359f,
                                    cfg.gradientAngle.roundToInt().toString() + " deg"
                                ) { v -> edit { it.copy(gradientAngle = v) } }
                            }
                            VisualizerColorMode.MULTICOLOR -> {
                                cfg.multiColors.forEachIndexed { index, color ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            ColorPickerRow("Color " + (index + 1), color) { c ->
                                                edit { conf ->
                                                    conf.copy(multiColors = conf.multiColors.toMutableList().also { l -> l[index] = c })
                                                }
                                            }
                                        }
                                        if (cfg.multiColors.size > 2) {
                                            TextButton(onClick = {
                                                edit { conf ->
                                                    conf.copy(multiColors = conf.multiColors.filterIndexed { i, _ -> i != index })
                                                }
                                            }) { Text("Remove") }
                                        }
                                    }
                                }
                                if (cfg.multiColors.size < 8) {
                                    TextButton(onClick = {
                                        edit { conf -> conf.copy(multiColors = conf.multiColors + conf.multiColors.last()) }
                                    }) { Text("Add color") }
                                }
                            }
                        }
                    }
                }
            }

            // ---- shape and size ----
            item {
                SectionTitle("Shape and size")
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledSlider("Number of bars", cfg.barCount.toFloat(), 4f..128f, cfg.barCount.toString()) { v ->
                            edit { it.copy(barCount = v.roundToInt()) }
                        }
                        LabeledSlider("Bar width", cfg.barWidthDp, 1f..32f, fmt(cfg.barWidthDp) + " dp") { v ->
                            edit { it.copy(barWidthDp = v) }
                        }
                        LabeledSlider("Spacing", cfg.spacingDp, 0f..32f, fmt(cfg.spacingDp) + " dp") { v ->
                            edit { it.copy(spacingDp = v) }
                        }
                        LabeledSlider("Corner radius", cfg.cornerRadiusDp, 0f..16f, fmt(cfg.cornerRadiusDp) + " dp") { v ->
                            edit { it.copy(cornerRadiusDp = v) }
                        }
                        LabeledSlider("Height (player)", cfg.heightDp.toFloat(), 40f..480f, cfg.heightDp.toString() + " dp") { v ->
                            edit { it.copy(heightDp = v.roundToInt()) }
                        }
                        LabeledSlider("Width (player)", cfg.widthPercent, 0.2f..1f, (cfg.widthPercent * 100f).roundToInt().toString() + " %") { v ->
                            edit { it.copy(widthPercent = v) }
                        }
                        LabeledSlider("Horizontal position", cfg.positionX, 0f..1f, (cfg.positionX * 100f).roundToInt().toString() + " %") { v ->
                            edit { it.copy(positionX = v) }
                        }
                        LabeledSlider("Vertical position", cfg.positionY, 0f..1f, (cfg.positionY * 100f).roundToInt().toString() + " %") { v ->
                            edit { it.copy(positionY = v) }
                        }
                    }
                }
            }

            // ---- motion ----
            item {
                SectionTitle("Motion")
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledSlider("Sensitivity", cfg.sensitivity, 0.2f..4f, fmt(cfg.sensitivity) + "x") { v ->
                            edit { it.copy(sensitivity = v) }
                        }
                        LabeledSlider("Animation speed", cfg.animationSpeed, 0.2f..3f, fmt(cfg.animationSpeed) + "x") { v ->
                            edit { it.copy(animationSpeed = v) }
                        }
                        LabeledSlider("Smoothing", cfg.smoothing, 0f..0.95f, (cfg.smoothing * 100f).roundToInt().toString() + " %") { v ->
                            edit { it.copy(smoothing = v) }
                        }
                    }
                }
            }

            // ---- look ----
            item {
                SectionTitle("Look")
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LabeledSlider("Opacity", cfg.opacity, 0.05f..1f, (cfg.opacity * 100f).roundToInt().toString() + " %") { v ->
                            edit { it.copy(opacity = v) }
                        }
                        LabeledSlider("Glow", cfg.glow, 0f..1f, (cfg.glow * 100f).roundToInt().toString() + " %") { v ->
                            edit { it.copy(glow = v) }
                        }
                        LabeledSlider("Background", cfg.backgroundAlpha, 0f..1f, (cfg.backgroundAlpha * 100f).roundToInt().toString() + " %") { v ->
                            edit { it.copy(backgroundAlpha = v) }
                        }
                        if (cfg.backgroundAlpha > 0.01f) {
                            ColorPickerRow("Background color", cfg.backgroundColor) { c -> edit { it.copy(backgroundColor = c) } }
                        }
                    }
                }
            }
        }
    }

    // ---- dialogs ----
    nameDialog?.let { mode ->
        var text by remember(mode) {
            mutableStateOf(if (mode == "rename") state.selectedProfile?.name.orEmpty() else "")
        }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (mode == "rename") "Rename style" else "Save as new style") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (mode == "rename") {
                        state.selectedProfileId?.let { VisualizerSettings.renameProfile(it, text) }
                    } else {
                        VisualizerSettings.saveAsNew(text)
                    }
                    nameDialog = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this style?") },
            text = { Text(state.selectedProfile?.name.orEmpty() + " will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedProfileId?.let { VisualizerSettings.deleteProfile(it) }
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

private fun fmt(v: Float): String = "%.1f".format(v)

private fun statusText(status: VisualizerHub.Status): String = when (status) {
    VisualizerHub.Status.OFF -> "Off"
    VisualizerHub.Status.IDLE -> "On. It shows while the player or the floating lyrics are visible."
    VisualizerHub.Status.NO_PERMISSION -> "Needs the Record audio permission."
    VisualizerHub.Status.NO_SESSION -> "Waiting for playback to start."
    VisualizerHub.Status.ERROR -> "Could not start audio capture" + (VisualizerHub.lastError?.let { ": $it" } ?: "")
    VisualizerHub.Status.RUNNING -> "Capturing audio."
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range
        )
    }
}

@Composable
private fun <T> ChipChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { option ->
            FilterChip(
                selected = option.first == selected,
                onClick = { onSelect(option.first) },
                label = { Text(option.second) }
            )
        }
    }
}

/** A colour swatch that expands into hue / saturation / brightness sliders and a hex field. */
@Composable
private fun ColorPickerRow(label: String, color: Int, onColorChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val initial = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var sat by remember { mutableFloatStateOf(initial[1]) }
    var bright by remember { mutableFloatStateOf(initial[2]) }
    var lastEmitted by remember { mutableIntStateOf(color) }

    // A colour that changed from outside (preset selected, reset): re-read it.
    LaunchedEffect(color) {
        if (color != lastEmitted) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(color, hsv)
            hue = hsv[0]
            sat = hsv[1]
            bright = hsv[2]
            lastEmitted = color
        }
    }

    fun emit() {
        val c = android.graphics.Color.HSVToColor(255, floatArrayOf(hue, sat, bright))
        lastEmitted = c
        onColorChange(c)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
        if (expanded) {
            LabeledSlider("Hue", hue, 0f..360f, hue.roundToInt().toString()) { v ->
                hue = v
                emit()
            }
            LabeledSlider("Saturation", sat, 0f..1f, (sat * 100f).roundToInt().toString() + " %") { v ->
                sat = v
                emit()
            }
            LabeledSlider("Brightness", bright, 0f..1f, (bright * 100f).roundToInt().toString() + " %") { v ->
                bright = v
                emit()
            }
            var hex by remember(color) { mutableStateOf("#%06X".format(color and 0xFFFFFF)) }
            OutlinedTextField(
                value = hex,
                onValueChange = { text ->
                    hex = text
                    parseHex(text)?.let { c ->
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(c, hsv)
                        hue = hsv[0]
                        sat = hsv[1]
                        bright = hsv[2]
                        lastEmitted = c
                        onColorChange(c)
                    }
                },
                singleLine = true,
                label = { Text("Hex color") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }
    }
}

private fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6) return null
    return try {
        (0xFF000000L or t.toLong(16)).toInt()
    } catch (e: NumberFormatException) {
        null
    }
}
