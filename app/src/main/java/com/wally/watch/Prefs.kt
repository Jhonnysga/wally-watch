package com.wally.watch

import android.content.Context
import android.content.SharedPreferences

/**
 * Ajustes persistentes. Los interruptores por app se guardan aquí y
 * el [NotifyListener] los respeta siempre (sin bugs de claves).
 */
object Prefs {

    private const val FILE = "wally_watch_prefs"
    private const val KEY_MAC = "watch_mac"
    private const val KEY_FORWARD_ONGOING = "forward_ongoing"
    private const val PREFIX_APP = "app_enabled_"

    /** Apps con interruptor visible en la UI. */
    val trackedApps = listOf(
        "com.whatsapp" to "WhatsApp",
        "org.telegram.messenger" to "Telegram",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "com.google.android.gm" to "Gmail",
        "com.android.mms" to "SMS",
        "com.meta.ai" to "Muse",
        "ai.muse.mobile" to "Muse",
    )

    /** Clave especial para apps no listadas. */
    const val KEY_OTHER_APPS = "app_enabled__other"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var watchMac: String
        get() = prefs.getString(KEY_MAC, "9A:22:33:04:80:E8") ?: "9A:22:33:04:80:E8"
        set(v) = prefs.edit().putString(KEY_MAC, v).apply()

    /** ¿Está habilitada una app? Las no listadas usan el interruptor "Otras". */
    fun isAppEnabled(context: Context, pkg: String): Boolean {
        init(context)
        val isTracked = trackedApps.any { it.first == pkg }
        return if (isTracked) {
            prefs.getBoolean(PREFIX_APP + pkg, true)
        } else {
            prefs.getBoolean(KEY_OTHER_APPS, true)
        }
    }

    fun setAppEnabled(context: Context, pkg: String, enabled: Boolean) {
        init(context)
        val key = if (pkg == "__other") KEY_OTHER_APPS else PREFIX_APP + pkg
        prefs.edit().putBoolean(key, enabled).apply()
    }

    fun forwardOngoing(context: Context): Boolean {
        init(context)
        return prefs.getBoolean(KEY_FORWARD_ONGOING, false)
    }

    fun setForwardOngoing(context: Context, value: Boolean) {
        init(context)
        prefs.edit().putBoolean(KEY_FORWARD_ONGOING, value).apply()
    }
}
