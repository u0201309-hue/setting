package com.datapack.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One recorded stretch of sound. Start/end are real wall-clock epoch millis. */
data class Batch(val file: File, val startMs: Long, val endMs: Long) {
    val id: String get() = file.name
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1)
    val date: LocalDate get() = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * Batches live as WAV files named "<startMillis>_<endMillis>.wav" inside the
 * app's private storage. The filename is the only metadata we need, so there
 * is no database to get out of sync with the files.
 */
object BatchStore {
    private val NAME = Regex("""^(\d+)_(\d+)\.wav$""")
    const val IN_PROGRESS_SUFFIX = "_rec.wav"
    const val RETENTION_MS = (3.5 * 24 * 60 * 60 * 1000).toLong() // 3.5 days

    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun inProgressFile(context: Context, startMs: Long) = File(dir(context), "$startMs$IN_PROGRESS_SUFFIX")

    fun finalName(startMs: Long, endMs: Long) = "${startMs}_${endMs}.wav"

    /** Newest first. */
    fun list(context: Context): List<Batch> =
        dir(context).listFiles().orEmpty().mapNotNull { f ->
            val m = NAME.matchEntire(f.name) ?: return@mapNotNull null
            Batch(f, m.groupValues[1].toLong(), m.groupValues[2].toLong())
        }.sortedByDescending { it.startMs }

    fun delete(batch: Batch) {
        batch.file.delete()
        notifyChanged()
    }

    /** Removes finished batches older than the retention window and any stale in-progress files. */
    fun cleanup(context: Context) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        var changed = false
        dir(context).listFiles().orEmpty().forEach { f ->
            val m = NAME.matchEntire(f.name)
            if (m != null && m.groupValues[2].toLong() < cutoff) { f.delete(); changed = true }
        }
        if (changed) notifyChanged()
    }

    /** Recovers "<start>_rec.wav" files left behind if the process died mid-recording. */
    fun recoverOrphans(context: Context) {
        dir(context).listFiles().orEmpty().filter { it.name.endsWith(IN_PROGRESS_SUFFIX) }.forEach { f ->
            val start = f.name.removeSuffix(IN_PROGRESS_SUFFIX).toLongOrNull()
            val dataBytes = f.length() - WavWriter.HEADER_SIZE
            if (start == null || dataBytes < RecordingService.SAMPLE_RATE * 2 / 2) { // < 0.5 s
                f.delete(); return@forEach
            }
            val durationMs = dataBytes * 1000 / (RecordingService.SAMPLE_RATE * 2)
            // Re-write a correct header so the file is playable.
            java.io.RandomAccessFile(f, "rw").use { raf ->
                val fixed = File(f.parentFile, "tmp_fix.wav")
                val w = WavWriter(fixed, RecordingService.SAMPLE_RATE)
                val buf = ByteArray(8192)
                raf.seek(WavWriter.HEADER_SIZE.toLong())
                val shorts = ShortArray(4096)
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    val count = n / 2
                    for (i in 0 until count) {
                        shorts[i] = ((buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)).toShort()
                    }
                    w.write(shorts, count)
                }
                w.close()
                f.delete()
                fixed.renameTo(File(f.parentFile, finalName(start, start + durationMs)))
            }
        }
        notifyChanged()
    }

    // ---- change notifications (service and UI share a process) ----
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes
    fun notifyChanged() { _changes.value = System.currentTimeMillis() }
}

/** Live status the service publishes for the UI. */
object ListenerState {
    val serviceRunning = MutableStateFlow(false)
    val recordingNow = MutableStateFlow(false)
    val currentStartMs = MutableStateFlow(0L)
    val level = MutableStateFlow(0f)          // 0..1 for the little meter
    val micBlocked = MutableStateFlow(false)  // OS is feeding us silence (background-start rule)
    val error = MutableStateFlow<String?>(null)
}
