package com.wally.watch

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Pantalla principal de Wally Watch:
 * - Estado de conexión BLE con el reloj.
 * - Interruptores por app (funcionan de verdad).
 * - Botón de prueba de notificación.
 * - Registro de actividad.
 */
class MainActivity : AppCompatActivity(), BleManager.Listener {

    private lateinit var ble: BleManager
    private lateinit var statusText: TextView
    private lateinit var connectBtn: Button
    private lateinit var logView: TextView
    private lateinit var macInput: EditText
    private val logLines = ArrayDeque<String>(200)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        ble = BleManager.get(this)
        ble.listener = this
        ble.targetMac = Prefs.watchMac

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Título con versión
        root.addView(TextView(this).apply {
            text = "Wally Watch v22"
            textSize = 24f
        })

        // Estado
        statusText = TextView(this).apply {
            text = "Desconectado"
            textSize = 16f
            setPadding(0, 16, 0, 16)
        }
        root.addView(statusText)

        // MAC del reloj
        root.addView(TextView(this).apply { text = "MAC del reloj:" })
        macInput = EditText(this).apply {
            setText(Prefs.watchMac)
            hint = "9A:22:33:04:80:E8"
        }
        root.addView(macInput)

        // Botones conectar / escanear
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        connectBtn = Button(this).apply {
            text = "Conectar"
            setOnClickListener { onConnectClicked() }
        }
        val scanBtn = Button(this).apply {
            text = "Escanear"
            setOnClickListener {
                if (ensureBtPermissions()) ble.startScan()
            }
        }
        val notifBtn = Button(this).apply {
            text = "Permiso notificaciones"
            setOnClickListener { openNotificationSettings() }
        }
        btnRow.addView(connectBtn)
        btnRow.addView(scanBtn)
        root.addView(btnRow)
        root.addView(notifBtn)

        // Interruptores por app
        root.addView(TextView(this).apply {
            text = "Notificaciones por app:"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })
        for ((pkg, label) in Prefs.trackedApps) {
            val sw = Switch(this).apply {
                text = label
                isChecked = Prefs.isAppEnabled(this@MainActivity, pkg)
                setOnCheckedChangeListener { _, checked ->
                    Prefs.setAppEnabled(this@MainActivity, pkg, checked)
                    addLog("$label ${if (checked) "activado" else "desactivado"}")
                }
            }
            root.addView(sw)
        }

        // Botón de prueba
        val testBtn = Button(this).apply {
            text = "Enviar notificación de prueba"
            setOnClickListener { sendTest() }
        }
        root.addView(testBtn.apply { setPadding(0, 16, 0, 0) })

        // Log
        root.addView(TextView(this).apply {
            text = "Registro:"
            setPadding(0, 24, 0, 8)
        })
        logView = TextView(this).apply {
            textSize = 12f
            setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        setContentView(root)

        if (!isNotificationListenerEnabled()) {
            Toast.makeText(
                this,
                "Activa el acceso a notificaciones para Wally Watch",
                Toast.LENGTH_LONG
            ).show()
            openNotificationSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        ble.listener = this
        updateStatus(ble.connected, null)
    }

    private fun onConnectClicked() {
        if (!ensureBtPermissions()) return
        if (ble.connected) {
            ble.disconnect()
        } else {
            val mac = macInput.text.toString().trim()
            if (mac.isNotEmpty()) {
                Prefs.watchMac = mac
                ble.targetMac = mac
            }
            addLog("Conectando a ${ble.targetMac}...")
            ble.connectToKnown()
        }
    }

    private fun sendTest() {
        if (!ble.connected) {
            Toast.makeText(this, "Conecta el reloj primero", Toast.LENGTH_SHORT).show()
            return
        }
        ble.sendNotification(
            WatchProtocol.iconIdForPackage("org.telegram.messenger"),
            "WhatsApp", // Diagnóstico: probar con nombre WhatsApp
            "Prueba de notificación OK"
        )
        addLog("Prueba enviada (diagnóstico WhatsApp/icon 8)")
    }

    // ---------- BleManager.Listener ----------

    override fun onStateChanged(connected: Boolean, deviceName: String?) {
        runOnUiThread { updateStatus(connected, deviceName) }
    }

    override fun onLog(msg: String) {
        runOnUiThread { addLog(msg) }
    }

    private fun updateStatus(connected: Boolean, deviceName: String?) {
        statusText.text = if (connected) {
            "Conectado: ${deviceName ?: "reloj"}"
        } else {
            "Desconectado"
        }
        connectBtn.text = if (connected) "Desconectar" else "Conectar"
    }

    private fun addLog(msg: String) {
        if (logLines.size >= 200) logLines.removeFirst()
        logLines.addLast(msg)
        logView.text = logLines.joinToString("\n")
    }

    // ---------- Permisos ----------

    private fun ensureBtPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
                return false
            }
        } else {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            if (btManager.adapter?.isEnabled == false) {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return false
            }
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1002
                )
                return false
            }
        }
        return true
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        if (flat.isEmpty()) return false
        val names = flat.split(":")
        val my = ComponentName(this, NotifyListener::class.java).flattenToString()
        return names.any { it.equals(my, ignoreCase = true) }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        // Fallback: quitar warning de TextUtils no usado
        TextUtils.isEmpty("")
    }
}
