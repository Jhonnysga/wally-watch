package com.wally.watch

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Lee las notificaciones del sistema y las reenvía al reloj por BLE.
 * Respeta los interruptores por app de [Prefs] (a diferencia del bug de
 * SuperBand donde el toggle de Telegram no surtía efecto).
 */
class NotifyListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.d(TAG, "NotificationListener conectado")
        Prefs.init(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handleNotification(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando notificación", e)
        }
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return

        // Ignorar notificaciones propias y del sistema persistentes
        if (pkg == packageName) return
        if (sbn.isOngoing && !Prefs.forwardOngoing(this)) return

        // Interruptor por app: si está apagada, se descarta (esto SÍ funciona)
        if (!Prefs.isAppEnabled(this, pkg)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        var body = (if (bigText.isNotEmpty()) bigText else text).trim()

        // Telegram y otras usan MessagingStyle: extraer de EXTRA_MESSAGES
        if (body.isEmpty()) {
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (messages != null) {
                val lastMsg = messages.lastOrNull()
                val bundle = lastMsg as? android.os.Bundle
                val msgText = bundle?.getCharSequence("text")?.toString().orEmpty()
                if (msgText.isNotEmpty()) body = msgText.trim()
            }
        }
        // Título de conversación para MessagingStyle
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val finalTitle = when {
            title.isNotEmpty() -> title
            convTitle.isNotEmpty() -> convTitle
            else -> ""
        }
        if (body.isEmpty() && finalTitle.isEmpty()) return

        val iconId = WatchProtocol.iconIdForPackage(pkg)
        val label = WatchProtocol.appLabel(pkg)

        val ble = BleManager.get(this)
        if (ble.connected) {
            ble.sendNotification(iconId, finalTitle.ifEmpty { label }, body)
            Log.d(TAG, "→ reloj [$label] $finalTitle (pkg=$pkg)")
        } else {
            Log.d(TAG, "Reloj no conectado, notificación de $label en espera")
        }
    }

    companion object {
        private const val TAG = "NotifyListener"
    }
}
