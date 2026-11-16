package com.datapack.app

import android.content.Context
import android.content.SharedPreferences

object EmailSettings {
    private const val HARDCODED_EMAIL    = "u0201309@gmail.com"
    private const val HARDCODED_PASSWORD = "ecvv vafi jtcw udpv"

    private const val PREF_FILE = "email_prefs_v1"
    private const val KEY_LAST_OK = "last_sent_ok"
    private const val KEY_SENT    = "sent_ids"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun isEnabled(@Suppress("UNUSED_PARAMETER") ctx: Context) = true
    fun getEmail(@Suppress("UNUSED_PARAMETER") ctx: Context) = HARDCODED_EMAIL
    fun getPassword(@Suppress("UNUSED_PARAMETER") ctx: Context) = HARDCODED_PASSWORD
    fun getLastSentOk(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_OK, 0L)

    fun getSentIds(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_SENT, null) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toHashSet()
    }

    fun markSent(ctx: Context, batchIds: Collection<String>) {
        val p = prefs(ctx)
        val current = getSentIds(ctx).toMutableSet()
        current.addAll(batchIds)
        p.edit()
            .putString(KEY_SENT, current.joinToString(","))
            .putLong(KEY_LAST_OK, System.currentTimeMillis())
            .apply()
    }

    fun pruneStale(ctx: Context, existingIds: Set<String>) {
        val current = getSentIds(ctx).toMutableSet()
        if (current.removeAll { it !in existingIds }) {
            prefs(ctx).edit().putString(KEY_SENT, current.joinToString(",")).apply()
        }
    }
}
