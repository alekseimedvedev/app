package app.dimwall

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel, onPickPhoto: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (viewModel.hasImage) {
                Editor(viewModel, onPickPhoto)
            } else {
                EmptyState(viewModel, onPickPhoto)
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyState(
    viewModel: EditorViewModel,
    onPickPhoto: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DimWall", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Pick a photo to turn it into a muted wallpaper.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onPickPhoto) { Text("Choose photo") }
        viewModel.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.Editor(
    viewModel: EditorViewModel,
    onPickPhoto: () -> Unit,
) {
    // Preview with pinch-to-zoom and drag. Always has the screen's aspect ratio.
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        val shown = if (viewModel.showOriginal) viewModel.original else viewModel.preview
        Box(
            modifier = Modifier
                .aspectRatio(viewModel.outputAspect, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (size.width > 0 && size.height > 0) {
                            viewModel.transformCrop(
                                u = centroid.x / size.width,
                                v = centroid.y / size.height,
                                panX = pan.x / size.width,
                                panY = pan.y / size.height,
                                zoomChange = zoom,
                            )
                        }
                    }
                },
        ) {
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = "Wallpaper preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CompareButton(viewModel, Modifier.weight(1f))
        OutlinedButton(onClick = viewModel::resetAll, modifier = Modifier.weight(1f)) { Text("Reset") }
        OutlinedButton(onClick = onPickPhoto, modifier = Modifier.weight(1f)) { Text("Photo") }
    }

    val fx = viewModel.effects
    EffectSlider("Blur", fx.blur, 0f..1f) { viewModel.updateEffects(fx.copy(blur = it)) }
    EffectSlider("Saturation", fx.saturation, 0f..1f) { viewModel.updateEffects(fx.copy(saturation = it)) }
    EffectSlider("Contrast", fx.contrast, Effects.MIN_CONTRAST..1f) { viewModel.updateEffects(fx.copy(contrast = it)) }
    EffectSlider("Brightness", fx.brightness, Effects.MIN_BRIGHTNESS..1f) { viewModel.updateEffects(fx.copy(brightness = it)) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Set on", style = MaterialTheme.typography.labelLarge)
        EditorViewModel.Target.entries.forEach { t ->
            FilterChip(
                selected = viewModel.target == t,
                onClick = { viewModel.target = t },
                label = { Text(t.label) },
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = viewModel::setWallpaper,
            enabled = !viewModel.busy,
            modifier = Modifier.weight(1f),
        ) { Text("Set wallpaper") }
        OutlinedButton(
            onClick = viewModel::saveToGallery,
            enabled = !viewModel.busy,
            modifier = Modifier.weight(1f),
        ) { Text("Save to gallery") }
    }

    if (viewModel.busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    viewModel.message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
    }
}

/** While this button is held, the preview shows the original (cropped, without effects). */
@Composable
private fun CompareButton(viewModel: EditorViewModel, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { viewModel.showOriginal = pressed }
    DisposableEffect(Unit) { onDispose { viewModel.showOriginal = false } }
    FilledTonalButton(onClick = {}, interactionSource = interaction, modifier = modifier) {
        Text("Hold: before")
    }
}

@Composable
private fun EffectSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
