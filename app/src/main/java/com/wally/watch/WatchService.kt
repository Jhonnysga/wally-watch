package com.wally.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Servicio en primer plano que mantiene la conexión BLE con el reloj.
 * Evita que Android mate la app en segundo plano y desconecte el reloj.
 */
class WatchService : Service() {

    companion object {
        private const val TAG = "WatchService"
        private const val CHANNEL_ID = "wally_watch_channel"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.wally.watch.action.START"
        const val ACTION_STOP = "com.wally.watch.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, WatchService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WatchService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var ble: BleManager
    private val handler = Handler(Looper.getMainLooper())
    private var monitorRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ble = BleManager.get(this)
        Log.d(TAG, "Servicio creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification("Wally Watch activo"))
                Log.d(TAG, "Iniciando conexión")
                if (!ble.connected) {
                    ble.connectToKnown()
                }
                startMonitoring()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Deteniendo servicio")
                stopMonitoring()
                ble.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Si el sistema mata el servicio, reiniciarlo
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMonitoring()
        ble.disconnect()
        super.onDestroy()
        Log.d(TAG, "Servicio destruido")
    }

    private fun startMonitoring() {
        stopMonitoring()
        monitorRunnable = object : Runnable {
            override fun run() {
                checkNotificationListener()
                // Verificar cada 5 minutos
                handler.postDelayed(this, 5 * 60 * 1000)
            }
        }
        handler.postDelayed(monitorRunnable!!, 5 * 60 * 1000)
    }

    private fun stopMonitoring() {
        monitorRunnable?.let { handler.removeCallbacks(it) }
        monitorRunnable = null
    }

    private fun checkNotificationListener() {
        val enabled = isNotificationListenerEnabled()
        Log.d(TAG, "Listener activo: $enabled")
        if (!enabled) {
            Log.w(TAG, "Listener no vinculado, solicitando rebind...")
            try {
                NotifyListener.requestRebind(ComponentName(this, NotifyListener::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Rebind falló: ${e.message}")
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wally Watch",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la conexión con el reloj"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Wally Watch")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
