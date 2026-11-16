package com.datapack.app

import android.media.AudioAttributes
import android.media.MediaPlayer

/** Thin wrapper over MediaPlayer: play one batch from a given offset, stop, report position. */
class Player(private val onFinished: () -> Unit) {
    private var mp: MediaPlayer? = null
    var isPlaying: Boolean = false
        private set

    fun play(batch: Batch, startFraction: Float) {
        stop()
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        p.setDataSource(batch.file.absolutePath)
        p.prepare()
        val startMs = (p.duration * startFraction.coerceIn(0f, 1f)).toInt()
        p.seekTo(startMs)
        p.setOnCompletionListener { isPlaying = false; onFinished() }
        p.start()
        mp = p
        isPlaying = true
    }

    /** Current position as a fraction 0..1 of the file, or null if nothing is loaded. */
    fun fraction(): Float? {
        val p = mp ?: return null
        val d = p.duration
        if (d <= 0) return null
        return (p.currentPosition.toFloat() / d).coerceIn(0f, 1f)
    }

    fun stop() {
        mp?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        mp = null
        isPlaying = false
    }
}
