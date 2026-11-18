package chromahub.rhythm.app.features.visualizer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import chromahub.rhythm.app.features.visualizer.data.VisualizerConfig
import chromahub.rhythm.app.features.visualizer.data.VisualizerSettings
import chromahub.rhythm.app.features.visualizer.engine.VisualizerHub

/**
 * The visualizer for the full player screen. Place it as a child of a Box that fills the area it may
 * draw in; it positions itself inside that Box from the saved settings and draws nothing (and
 * captures no audio) when the visualizer is off or turned off for the player.
 */
@Composable
fun PlayerVisualizerOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        VisualizerSettings.init(context)
        VisualizerHub.init(context)
    }
    val state by VisualizerSettings.state.collectAsState()
    if (!state.masterEnabled || !state.showInPlayer) return
    val cfg = state.working
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = BiasAlignment(cfg.positionX * 2f - 1f, cfg.positionY * 2f - 1f)
    ) {
        VisualizerSurface(
            config = cfg,
            modifier = Modifier
                .fillMaxWidth(cfg.widthPercent)
                .height(cfg.heightDp.dp)
        )
    }
}

/** A [VisualizerView] wrapped for Compose. Size it with the modifier. */
@Composable
fun VisualizerSurface(
    config: VisualizerConfig,
    modifier: Modifier = Modifier,
    demoMode: Boolean = false
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> VisualizerView(ctx) },
        update = { view ->
            view.demoMode = demoMode
            view.config = config
        }
    )
}
