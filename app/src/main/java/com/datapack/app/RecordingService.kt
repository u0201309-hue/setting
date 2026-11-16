package com.datapack.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Always-on foreground service. Reads the microphone continuously, measures
 * loudness in 20 ms frames, and writes a WAV batch only while there is sound
 * (speech-level energy). A batch closes after ~2 s of quiet.
 */
class RecordingService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Datapack:svc").apply { acquire() }
        BatchStore.recoverOrphans(this)
        ListenerState.serviceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART_CAPTURE) {
            // Restart the capture loop (used after the UI comes to the foreground
            // when the OS had been feeding us silence).
            stopLoop()
        }
        if (worker?.isAlive != true) startLoop()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away: keep listening.
        scheduleRestart()
    }

    override fun onDestroy() {
        stopLoop()
        ListenerState.serviceRunning.value = false
        ListenerState.recordingNow.value = false
        wakeLock?.let { if (it.isHeld) it.release() }
        scheduleRestart() // if the system killed us, come back
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loop

    private fun startLoop() {
        running = true
        worker = Thread(::captureLoop, "cap").apply { start() }
    }

    private fun stopLoop() {
        running = false
        worker?.join(2000)
        worker = null
    }

    private fun captureLoop() {
        val frame = SAMPLE_RATE / 50 // 20 ms
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, frame * 2 * 16)
            )
        } catch (e: SecurityException) {
            ListenerState.error.value = "Microphone permission is missing"
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            ListenerState.error.value = "Could not open the microphone"
            recorder.release()
            return
        }
        ListenerState.error.value = null
        recorder.startRecording()

        val buf = ShortArray(frame)
        val preRoll = ArrayDeque<ShortArray>()          // ~500 ms kept so the first word isn't clipped
        var noiseFloor = 300.0
        var loudFrames = 0
        var quietFrames = 0
        var zeroFrames = 0
        var writer: WavWriter? = null
        var batchStart = 0L
        var samplesAtLastSound = 0L
        var lastCleanup = 0L
        var levelTick = 0

        fun closeBatch(w: WavWriter, endMs: Long) {
            w.close()
            val target = File(w.file.parentFile, BatchStore.finalName(batchStart, endMs))
            if (w.samplesWritten < SAMPLE_RATE / 2) w.file.delete() else w.file.renameTo(target)
            ListenerState.recordingNow.value = false
            BatchStore.notifyChanged()
        }

        while (running) {
            val n = recorder.read(buf, 0, frame)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            val rms = rms(buf, n)

            // Android 11+ silently feeds zeros to a mic service that was started from the
            // background without an exemption. Detect it so the UI can offer a fix.
            if (rms == 0.0) { if (++zeroFrames == 250) ListenerState.micBlocked.value = true }
            else { zeroFrames = 0; if (ListenerState.micBlocked.value) ListenerState.micBlocked.value = false }

            // Adaptive noise floor: follows quiet quickly, loud very slowly.
            noiseFloor = if (rms < noiseFloor) noiseFloor * 0.95 + rms * 0.05 else noiseFloor * 0.999 + rms * 0.001
            noiseFloor = noiseFloor.coerceAtLeast(40.0)
            val threshold = max(noiseFloor * 3.0, MIN_THRESHOLD)
            val loud = rms > threshold

            if (++levelTick % 4 == 0) ListenerState.level.value = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()

            val w = writer
            if (w == null) {
                preRoll.addLast(buf.copyOf(n))
                while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                loudFrames = if (loud) loudFrames + 1 else 0
                if (loudFrames >= START_FRAMES) {
                    batchStart = System.currentTimeMillis() - preRoll.size * 20L
                    val nw = WavWriter(BatchStore.inProgressFile(this, batchStart), SAMPLE_RATE)
                    preRoll.forEach { nw.write(it) }
                    preRoll.clear()
                    writer = nw
                    samplesAtLastSound = nw.samplesWritten
                    quietFrames = 0
                    loudFrames = 0
                    ListenerState.currentStartMs.value = batchStart
                    ListenerState.recordingNow.value = true
                }
            } else {
                w.write(buf, n)
                if (loud) { quietFrames = 0; samplesAtLastSound = w.samplesWritten } else quietFrames++
                val elapsed = w.samplesWritten * 1000 / SAMPLE_RATE
                if (quietFrames >= END_QUIET_FRAMES) {
                    // Keep ~0.5 s after the last sound, drop the rest of the tail.
                    val keep = samplesAtLastSound + SAMPLE_RATE / 2
                    w.truncateToSamples(keep)
                    closeBatch(w, batchStart + w.samplesWritten * 1000 / SAMPLE_RATE)
                    writer = null
                } else if (elapsed >= MAX_BATCH_MS) {
                    // Very long conversation: close this batch and keep going in a new one.
                    closeBatch(w, batchStart + elapsed)
                    writer = null
                    loudFrames = START_FRAMES // immediately reopen on the next frame
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastCleanup > 60 * 60 * 1000) { lastCleanup = now; BatchStore.cleanup(this) }
        }

        writer?.let { closeBatch(it, batchStart + it.samplesWritten * 1000 / SAMPLE_RATE) }
        try { recorder.stop() } catch (_: Exception) {}
        recorder.release()
        ListenerState.level.value = 0f
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    // ------------------------------------------------------- notifications

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_MIN: no sound, no peek/banner, collapsed to a single line at the
        // bottom of the shade, and no status-bar icon. The quietest a foreground-service
        // notification can be while still satisfying Android's disclosure requirement.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Datapack is recording"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Datapack")
            .setContentText("Recording")
            .setOngoing(false)                       // dismissible on Android 13+
            .setSilent(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Re-launch ourselves shortly if the process is torn down. */
    private fun scheduleRestart() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this, 1, Intent(this, BootReceiver::class.java).setAction(ACTION_KEEPALIVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 5000, pi)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_ID = "listening"
        const val NOTIF_ID = 1
        const val ACTION_RESTART_CAPTURE = "com.datapack.app.RESTART_CAPTURE"
        const val ACTION_KEEPALIVE = "com.datapack.app.KEEPALIVE"

        private const val MIN_THRESHOLD = 350.0        // RMS on a 16-bit scale; speech is ~1000+
        private const val PRE_ROLL_FRAMES = 25          // 500 ms
        private const val START_FRAMES = 4              // 80 ms of sound starts a batch
        private const val END_QUIET_FRAMES = 100        // 2 s of quiet ends a batch
        private const val MAX_BATCH_MS = 15 * 60 * 1000L

        fun start(context: Context, restartCapture: Boolean = false) {
            val i = Intent(context, RecordingService::class.java)
            if (restartCapture) i.action = ACTION_RESTART_CAPTURE
            ContextCompat.startForegroundService(context, i)
        }
    }
}
