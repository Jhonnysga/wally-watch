package com.wally.watch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestiona la conexión BLE con el reloj vía Nordic UART.
 * - Escanea por MAC conocida o nombre.
 * - Escribe comandos fragmentando al MTU disponible.
 * - Reintenta conexión automáticamente.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    interface Listener {
        fun onStateChanged(connected: Boolean, deviceName: String?)
        fun onLog(msg: String)
    }

    var listener: Listener? = null
    var targetMac: String = "9A:22:33:04:80:E8" // LY735/P800 de Jhon

    private val btManager by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23 // SuperBand nunca pide MTU; usamos el defecto
    private val isConnected = AtomicBoolean(false)
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val writeInFlight = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var cccdTimeoutRunnable: Runnable? = null

    val connected: Boolean get() = isConnected.get()

    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post { listener?.onLog(msg) }
    }

    // ---------- Escaneo y conexión ----------

    fun startScan() {
        val adapter = adapter ?: run { log("Bluetooth no disponible"); return }
        if (!adapter.isEnabled) { log("Activa el Bluetooth"); return }
        log("Escaneando reloj ($targetMac)...")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            log("Error al escanear: ${e.message}")
        }
        // Detener escaneo a los 20s
        handler.postDelayed({ stopScan() }, 20_000)
    }

    fun stopScan() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: ""
            if (device.address.equals(targetMac, ignoreCase = true) ||
                name.contains("P800", ignoreCase = true) ||
                name.contains("LY735", ignoreCase = true)
            ) {
                log("Reloj encontrado: $name ${device.address}")
                stopScan()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Escaneo falló: $errorCode")
        }
    }

    fun connect(device: BluetoothDevice) {
        log("Conectando a ${device.address} (bond=${bondStateName(device.bondState)})...")
        // Si no hay vínculo BLE, crearlo primero (SuperBand lo hace siempre)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            log("Creando vínculo BLE...")
            try {
                device.createBond()
            } catch (e: Exception) {
                log("createBond falló: ${e.message}")
            }
            // Esperar el vínculo antes de GATT (reintento en 5s)
            handler.postDelayed({
                if (!isConnected.get()) {
                    log("Reintentando GATT tras vínculo...")
                    doConnectGatt(device)
                }
            }, 5000)
            return
        }
        doConnectGatt(device)
    }

    private fun doConnectGatt(device: BluetoothDevice) {
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        // Timeout de seguridad: si en 30s no conectó, avisar
        handler.postDelayed({
            if (!isConnected.get() && gatt != null) {
                log("Timeout de conexión GATT (30s). Revisa que el reloj tenga Bluetooth activo y esté cerca.")
            }
        }, 30_000)
    }

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "vinculado"
        BluetoothDevice.BOND_BONDING -> "vinculando"
        else -> "no vinculado"
    }

    fun connectToKnown() {
        val device = adapter?.getRemoteDevice(targetMac)
        if (device != null) connect(device) else startScan()
    }

    fun disconnect() {
        reconnectAttempts = Int.MAX_VALUE // no reconectar
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected.set(false)
        handler.post { listener?.onStateChanged(false, null) }
    }

    // ---------- GATT ----------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT conectado, limpiando caché y descubriendo servicios...")
                reconnectAttempts = 0
                // Limpiar caché BLE de Android (API oculta) para forzar
                // descubrimiento fresco; si no, puede devolver servicios viejos.
                try {
                    val refresh = gatt.javaClass.getMethod("refresh")
                    val ok = refresh.invoke(gatt) as Boolean
                    log("Caché BLE limpiada: $ok")
                } catch (e: Exception) {
                    log("No se pudo limpiar caché: ${e.message}")
                }
                handler.postDelayed({ gatt.discoverServices() }, 500)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected.set(false)
                writeChar = null
                handler.post { listener?.onStateChanged(false, null) }
                log("Desconectado (status=$status)")
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Descubrimiento falló: $status"); return
            }
            // Listar todos los servicios para diagnóstico
            log("Servicios encontrados: ${gatt.services.size}")
            for (svc in gatt.services) {
                val suuid = svc.uuid.toString().lowercase()
                log("  SVC: $suuid")
                for (ch in svc.characteristics) {
                    val props = mutableListOf<String>()
                    val p = ch.properties
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("R")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("W")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WN")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("N")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("I")
                    log("    CHR: ${ch.uuid.toString().lowercase()} [${props.joinToString(",")}]")
                }
            }
            val service = gatt.getService(UUID.fromString(WatchProtocol.UART_SERVICE))
                ?: findUartLikeService(gatt)
            if (service == null) {
                log("Servicio Nordic UART no encontrado")
                return
            }
            if (!service.uuid.toString().equals(WatchProtocol.UART_SERVICE, ignoreCase = true)) {
                log("Usando servicio alterno: ${service.uuid}")
            }
            // Buscar característica de escritura (preferir la UUID estándar)
            writeChar = service.getCharacteristic(UUID.fromString(WatchProtocol.UART_WRITE_CHAR))
            if (writeChar == null) {
                // Fallback: primera característica con propiedad WRITE
                writeChar = service.characteristics.firstOrNull { ch ->
                    ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                if (writeChar != null) {
                    log("Usando característica alterna: ${writeChar!!.uuid}")
                }
            }
            if (writeChar == null) {
                log("Característica de escritura no encontrada"); return
            }
            // Habilitar notificaciones en la característica de notify.
            // SuperBand lo hace siempre; el reloj lo espera en el handshake.
            var notifyChar = service.getCharacteristic(
                UUID.fromString(WatchProtocol.UART_NOTIFY_CHAR)
            )
            if (notifyChar == null) {
                // Fallback: primera con propiedad NOTIFY/INDICATE
                notifyChar = service.characteristics.firstOrNull { ch ->
                    ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                }
            }
            if (notifyChar != null) {
                gatt.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                if (cccd != null) {
                    cccd.value = byteArrayOf(0x01, 0x00)
                    log("Habilitando notificaciones...")
                    gatt.writeDescriptor(cccd)
                    // El resto continúa en onDescriptorWrite
                    return
                }
            }
            // Sin CCCD: ir directo a listo (sin pedir MTU, como SuperBand)
            onReady(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            status: Int
        ) {
            log("CCCD escrito: $status")
            onReady(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@BleManager.mtu = mtu
            log("MTU negociado: $mtu")
            onReady(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInFlight.set(false)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write falló: $status")
            }
            pumpQueue()
        }
    }

    private fun onReady(gatt: BluetoothGatt) {
        isConnected.set(true)
        val name = gatt.device.name ?: gatt.device.address
        log("Listo para enviar al reloj ($name)")
        handler.post { listener?.onStateChanged(true, name) }
        // Secuencia de inicialización como SuperBand:
        // 1. Pairing de app, 2. Sincronizar hora. Luego notificaciones.
        log("Enviando handshake de app...")
        sendFrame(WatchProtocol.buildAppPair())
        handler.postDelayed({
            log("Sincronizando hora...")
            sendFrame(WatchProtocol.buildTimeSyncNow())
        }, 500)
        pumpQueue()
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= 5) {
            log("Sin reconexión tras 5 intentos")
            return
        }
        reconnectAttempts++
        val delay = (reconnectAttempts * 5_000).toLong()
        log("Reintentando en ${delay / 1000}s (intento $reconnectAttempts/5)...")
        handler.postDelayed({ if (!isConnected.get()) connectToKnown() }, delay)
    }

    // ---------- Escritura ----------

    /**
     * Envía un frame completo, fragmentado al MTU disponible.
     */
    fun sendFrame(frame: ByteArray) {
        if (!isConnected.get()) {
            log("No conectado: frame descartado (${frame.size} bytes)")
            return
        }
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + chunkSize, frame.size)
            writeQueue.add(frame.copyOfRange(offset, end))
            offset = end
        }
        log("Encolado frame de ${frame.size} bytes (${writeQueue.size} fragmentos)")
        pumpQueue()
    }

    fun sendNotification(iconId: Int, title: String, text: String) {
        val frame = WatchProtocol.buildNotification(iconId, title, text)
        log("Push [icon=$iconId] \"$title\" (${frame.size} bytes)")
        sendFrame(frame)
    }

    private fun pumpQueue() {
        val gatt = gatt ?: return
        val char = writeChar ?: return
        if (!isConnected.get()) return
        if (!writeInFlight.compareAndSet(false, true)) return

        val chunk = writeQueue.poll()
        if (chunk == null) {
            writeInFlight.set(false)
            return
        }
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = chunk
        val ok = gatt.writeCharacteristic(char)
        if (!ok) {
            writeInFlight.set(false)
            log("writeCharacteristic rechazado")
            handler.postDelayed({ pumpQueue() }, 500)
        }
    }

    /**
     * Busca un servicio parecido a UART si el Nordic estándar no está:
     * cualquiera con característica de escritura y otra de notificación.
     */
    private fun findUartLikeService(gatt: BluetoothGatt): android.bluetooth.BluetoothGattService? {
        for (svc in gatt.services) {
            val uuid = svc.uuid.toString().lowercase()
            // Omitir servicios estándar que no son de datos
            if (uuid.startsWith("00001800") || uuid.startsWith("00001801") ||
                uuid.startsWith("0000180f") || uuid.startsWith("0000180a")
            ) continue
            var hasWrite = false
            var hasNotify = false
            for (ch in svc.characteristics) {
                val p = ch.properties
                if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                           BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) hasWrite = true
                if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                           BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) hasNotify = true
            }
            if (hasWrite && hasNotify) return svc
        }
        return null
    }

    companion object {
        private const val TAG = "BleManager"

        @Volatile
        private var instance: BleManager? = null

        fun get(context: Context): BleManager =
            instance ?: synchronized(this) {
                instance ?: BleManager(context.applicationContext).also { instance = it }
            }
    }
}
