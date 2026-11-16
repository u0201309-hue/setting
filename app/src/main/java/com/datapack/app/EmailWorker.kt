package com.datapack.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that fires every [INTERVAL_MIN] minutes and emails any
 * new audio batches to the configured Gmail address.
 *
 * Design choices that keep battery impact low:
 *  - Uses WorkManager (not AlarmManager) so Android can batch wakeups.
 *  - Requires CONNECTED network before it even starts (no polling loop).
 *  - Tracks sent batch IDs so we never re-upload already-forwarded audio.
 *  - Re-schedules itself as a one-time request instead of a PeriodicWorkRequest
 *    so we get a 10-minute interval (WorkManager's periodic minimum is 15 min).
 *  - On failure, schedules a fast retry in [RETRY_MIN] min; the main schedule
 *    also keeps running so a retry doesn't push the next normal window.
 */
class EmailWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG         = "EmailWorker"
        const  val WORK_NAME          = "dp_email"        // main 10-min cadence
        const  val RETRY_WORK_NAME    = "dp_email_retry"  // 1-min retry on failure
        private const val INTERVAL_MIN = 10L
        private const val RETRY_MIN    = 1L

        /** Called once when email is first enabled, and after each successful run. */
        fun scheduleNext(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<EmailWorker>()
                .setInitialDelay(INTERVAL_MIN, TimeUnit.MINUTES)
                // WorkManager will only start the worker while the network is up;
                // it holds the request and triggers as soon as connectivity returns.
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }

        private fun scheduleRetry(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<EmailWorker>()
                .setInitialDelay(RETRY_MIN, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(ctx).cancelUniqueWork(RETRY_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // ── Guard: email not configured ─────────────────────────────────
            if (!EmailSettings.isEnabled(applicationContext)) {
                Log.d(TAG, "Not enabled — skip")
                // Don't re-schedule; it will be re-armed when the user saves credentials.
                return@withContext Result.success()
            }

            val email = EmailSettings.getEmail(applicationContext)
            val pass  = EmailSettings.getPassword(applicationContext)
            if (email.isNullOrBlank() || pass.isNullOrBlank()) {
                Log.d(TAG, "Credentials missing — skip")
                scheduleNext(applicationContext)
                return@withContext Result.success()
            }

            // ── Guard: no internet ──────────────────────────────────────────
            if (!hasInternet()) {
                Log.d(TAG, "No internet — will retry when connected")
                // WorkManager's NetworkType.CONNECTED constraint already handles
                // this, but the guard is kept as a belt-and-suspenders check
                // for edge cases (captive portal without real internet, etc.).
                scheduleRetry(applicationContext)
                scheduleNext(applicationContext)
                return@withContext Result.success()
            }

            // ── Determine which batches need to be sent ─────────────────────
            val allBatches = BatchStore.list(applicationContext)
            val sentIds    = EmailSettings.getSentIds(applicationContext)

            // Prune IDs for files that no longer exist (auto-deleted after 3.5 d)
            EmailSettings.pruneStale(applicationContext, allBatches.map { it.id }.toSet())

            val pending = allBatches.filter { it.id !in sentIds }
            if (pending.isEmpty()) {
                Log.d(TAG, "No new batches — skip")
                scheduleNext(applicationContext)
                return@withContext Result.success()
            }

            Log.d(TAG, "${pending.size} new batch(es) to send")

            // ── Build ZIPs (split if > 18 MB raw) ──────────────────────────
            val zips = EmailSender.buildZipChunks(applicationContext, pending)

            // ── Send ────────────────────────────────────────────────────────
            for ((i, zip) in zips.withIndex()) {
                EmailSender.sendEmail(email, pass, zip, i, zips.size)
                zip.delete()
            }

            // ── Mark as sent and delete from device ─────────────────────────
            EmailSettings.markSent(applicationContext, pending.map { it.id })
            pending.forEach { batch ->
                batch.file.delete()
                Log.d(TAG, "Deleted ${batch.id} from device after sending")
            }
            BatchStore.notifyChanged()
            Log.d(TAG, "Done — sent ${pending.size} batch(es) in ${zips.size} email(s)")

            scheduleNext(applicationContext)
            Result.success()

        } catch (ex: Exception) {
            Log.e(TAG, "Failed: ${ex.javaClass.simpleName}: ${ex.message}")
            // Don't use WorkManager's built-in retry (exponential back-off
            // would push it out too far). Instead we self-schedule a 1-minute
            // retry AND keep the normal 10-minute cadence.
            scheduleRetry(applicationContext)
            scheduleNext(applicationContext)
            Result.success()   // returning RETRY would double-schedule
        }
    }

    private fun hasInternet(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
        val net  = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
