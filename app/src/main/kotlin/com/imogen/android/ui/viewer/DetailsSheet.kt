package com.imogen.android.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.imogen.sdk.Asset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Everything the server knows about one photograph.
 *
 * The description is editable here rather than on a screen of its own, because it is the
 * one piece of this that is a person's rather than the camera's — and because search
 * reads it, so a sentence typed here is how the photograph gets found again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(
    asset: Asset,
    onDismiss: () -> Unit,
    onDescriptionChanged: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var description by remember(asset.id) { mutableStateOf(asset.description.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(asset.originalFilename, style = MaterialTheme.typography.titleMedium)
            Text(
                formatTaken(asset.capturedAt) +
                    if (!asset.capturedAtIsExact) " (estimated)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                minLines = 2,
            )
            if (description != asset.description.orEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { description = asset.description.orEmpty() }) {
                        Text("Discard")
                    }
                    TextButton(onClick = { onDescriptionChanged(description) }) { Text("Save") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Fact("Size", formatBytes(asset.sizeBytes))
            asset.width?.let { width ->
                asset.height?.let { height -> Fact("Dimensions", "$width × $height") }
            }
            asset.duration?.let { Fact("Length", formatDuration(it)) }
            Fact("Type", asset.mimeType)

            asset.exif?.let { exif ->
                val camera = listOfNotNull(exif.make, exif.model).joinToString(" ").trim()
                if (camera.isNotEmpty()) Fact("Camera", camera)
                exif.lens?.let { Fact("Lens", it) }
                val settings = listOfNotNull(
                    exif.fNumber?.let { "ƒ/$it" },
                    exif.exposureTime?.let(::formatShutter),
                    exif.iso?.let { "ISO $it" },
                    exif.focalLength?.let { "${it.roundToInt()} mm" },
                ).joinToString("  ")
                if (settings.isNotEmpty()) Fact("Exposure", settings)
            }

            asset.location?.let { place ->
                Fact(
                    "Where",
                    place.place ?: "%.5f, %.5f".format(place.latitude, place.longitude),
                )
            }

            // The checksum is what makes an upload idempotent, so it is worth showing:
            // it is the answer to "did this actually arrive, and is it the same file".
            Fact("Checksum", asset.checksum.take(16) + "…")

            Text(
                "",
                modifier = Modifier.padding(bottom = 24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

private val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
/** Built per call: a formatter cached in a `val` freezes the language the app started in. */
fun formatTaken(iso: String): String = runCatching {
    SimpleDateFormat("d MMMM yyyy 'at' HH:mm", Locale.getDefault())
        .format(isoParser.parse(iso.take(19))!!)
}.getOrDefault(iso)

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    return "%.1f %s".format(value, units[unit])
}

fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt()
    val minutes = total / 60
    return "%d:%02d".format(minutes, total % 60)
}

/** Shutter speeds are read as fractions, not as decimals of a second. */
private fun formatShutter(exposure: Double): String =
    if (exposure >= 1) "%.1f s".format(exposure) else "1/${(1 / exposure).roundToInt()} s"
