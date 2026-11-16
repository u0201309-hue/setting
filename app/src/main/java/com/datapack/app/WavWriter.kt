package com.datapack.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit mono PCM into a WAV file. The header is patched on close so
 * the file is always playable by MediaPlayer, even if the batch was long.
 */
class WavWriter(val file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var scratch = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    val samplesWritten: Long get() = dataBytes / 2

    init {
        raf.setLength(0)
        raf.write(ByteArray(HEADER_SIZE)) // placeholder header
    }

    fun write(samples: ShortArray, count: Int = samples.size) {
        val bytes = count * 2
        if (scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        for (i in 0 until count) scratch.putShort(samples[i])
        raf.write(scratch.array(), 0, bytes)
        dataBytes += bytes
    }

    /** Drops samples from the end so a batch does not finish with seconds of silence. */
    fun truncateToSamples(samples: Long) {
        val keepBytes = (samples * 2).coerceIn(0, dataBytes)
        dataBytes = keepBytes
        raf.setLength(HEADER_SIZE + keepBytes)
    }

    fun close() {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * 2
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)              // PCM chunk size
        header.putShort(1)             // format = PCM
        header.putShort(1)             // channels
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)             // block align
        header.putShort(16)            // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
        raf.close()
    }

    companion object {
        const val HEADER_SIZE = 44
    }
}
