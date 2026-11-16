package com.datapack.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.datapack.app.Batch
import com.datapack.app.ListenerState
import com.datapack.app.MainViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")

fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_FMT)

fun formatDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

fun formatDate(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DATE_FMT)
}

@Composable
fun MainScreen(
    vm: MainViewModel,
    micGranted: Boolean,
    overlayGranted: Boolean,
    batteryExempt: Boolean,
    onRequestMic: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onFixMic: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Batch?>(null) }

    Scaffold(
        topBar = {
            StatusHeader(
                micGranted, overlayGranted, batteryExempt,
                onRequestMic, onRequestOverlay, onRequestBattery, onFixMic,
            )
        },
        bottomBar = {
            PlaybackControls(
                enabled = state.selectedId != null,
                isPlaying = state.isPlaying,
                onPlay = vm::play,
                onStop = vm::stop,
            )
        }
    ) { padding ->
        if (state.batches.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No recordings yet.\nDatapack saves a batch whenever it hears sound.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Scaffold
        }
        val grouped = remember(state.batches) { state.batches.groupBy { it.date } }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            grouped.forEach { (date, batches) ->
                item(key = "h$date") {
                    Text(
                        formatDate(date),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(batches, key = { it.id }) { batch ->
                    val selected = batch.id == state.selectedId
                    BatchBar(
                        batch = batch,
                        selected = selected,
                        fraction = if (selected) state.fraction else 0f,
                        isPlaying = selected && state.isPlaying,
                        onSelect = { vm.select(batch) },
                        onDragStart = vm::dragStart,
                        onDrag = vm::dragTo,
                        onLongPress = { pendingDelete = batch },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pendingDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("${formatTime(b.startMs)} – ${formatTime(b.endMs)} on ${formatDate(b.date)} will be removed.") },
            confirmButton = { TextButton(onClick = { vm.delete(b); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusHeader(
    micGranted: Boolean,
    overlayGranted: Boolean,
    batteryExempt: Boolean,
    onRequestMic: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    onFixMic: () -> Unit,
) {
    val running by ListenerState.serviceRunning.collectAsStateWithLifecycle()
    val recording by ListenerState.recordingNow.collectAsStateWithLifecycle()
    val startMs by ListenerState.currentStartMs.collectAsStateWithLifecycle()
    val level by ListenerState.level.collectAsStateWithLifecycle()
    val blocked by ListenerState.micBlocked.collectAsStateWithLifecycle()
    val error by ListenerState.error.collectAsStateWithLifecycle()

    Surface(color = MaterialTheme.colorScheme.primary) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Datapack", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).background(
                        when {
                            !micGranted || error != null || blocked -> Color(0xFFE53935)
                            recording -> Color(0xFFFFB300)
                            running -> Color(0xFF66BB6A)
                            else -> Color.Gray
                        }, CircleShape
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !micGranted -> "Microphone permission needed"
                        error != null -> error!!
                        blocked -> "Microphone muted by Android — tap Fix"
                        recording -> "Recording since ${formatTime(startMs)}"
                        running -> "Listening"
                        else -> "Starting…"
                    },
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                LevelMeter(level)
            }

            if (!micGranted) {
                HeaderAction("Enable phone.", "Allow", onRequestMic)
            } else if (blocked) {
                HeaderAction(
                    "setup restarted in the background.",
                    "Fix", onFixMic
                )
            }
            if (micGranted && !overlayGranted) {
                HeaderAction(
                    "Allow Display of apps.",
                    "Allow", onRequestOverlay
                )
            }
            if (micGranted && !batteryExempt) {
                HeaderAction(
                    "Battery optimization.",
                    "Allow", onRequestBattery
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(text: String, button: String, onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.12f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary)
            ) { Text(button) }
        }
    }
}

@Composable
private fun LevelMeter(level: Float) {
    Row(verticalAlignment = Alignment.Bottom) {
        repeat(6) { i ->
            val on = level > i / 6f
            Box(
                Modifier
                    .padding(horizontal = 1.dp)
                    .width(4.dp)
                    .height((6 + i * 3).dp)
                    .background(if (on) Color(0xFF66BB6A) else Color.White.copy(alpha = 0.25f), RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
private fun BatchBar(
    batch: Batch,
    selected: Boolean,
    fraction: Float,
    isPlaying: Boolean,
    onSelect: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onLongPress: () -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(12.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .then(
                if (selected) Modifier.border(2.dp, primary, shape).background(primary.copy(alpha = 0.06f), shape)
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${formatTime(batch.startMs)}  –  ${formatTime(batch.endMs)}", fontWeight = FontWeight.Medium)
            Text(formatDuration(batch.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onSelect() }, onLongPress = { onLongPress() })
                }
                .then(
                    if (selected) Modifier.pointerInput(batch.id) {
                        detectHorizontalDragGestures(
                            onDragStart = { pos ->
                                onDragStart()
                                onDrag(pos.x / widthPx)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                onDrag(change.position.x / widthPx)
                            },
                        )
                    } else Modifier
                )
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val barH = 16.dp.toPx()
                val top = (size.height - barH) / 2
                drawRoundRect(track, Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2))
                if (selected) {
                    val x = size.width * fraction
                    drawRoundRect(primary.copy(alpha = 0.55f), Offset(0f, top), Size(x, barH), CornerRadius(barH / 2))
                    drawCircle(Color.White, radius = 12.dp.toPx(), center = Offset(x, size.height / 2))
                    drawCircle(primary, radius = 10.dp.toPx(), center = Offset(x, size.height / 2))
                } else {
                    drawRoundRect(primary.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2))
                }
            }
        }
        if (selected) {
            val at = batch.startMs + (batch.durationMs * fraction).toLong()
            Text(
                if (isPlaying) "Playing · ${formatTime(at)}" else "Play from ${formatTime(at)}  ·  drag the handle to change",
                fontSize = 12.sp,
                color = primary,
            )
        }
    }
}

@Composable
private fun PlaybackControls(enabled: Boolean, isPlaying: Boolean, onPlay: () -> Unit, onStop: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPlay,
                enabled = enabled && !isPlaying,
                modifier = Modifier.height(56.dp).weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                Spacer(Modifier.width(8.dp))
                Text("Play", fontSize = 18.sp)
            }
            Spacer(Modifier.width(16.dp))
            FilledTonalButton(
                onClick = onStop,
                enabled = enabled && isPlaying,
                modifier = Modifier.height(56.dp).weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop")
                Spacer(Modifier.width(8.dp))
                Text("Stop", fontSize = 18.sp)
            }
        }
    }
}
