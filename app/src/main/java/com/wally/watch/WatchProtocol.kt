package com.wally.watch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo BLE del smartwatch LY735/P800 (ingeniería inversa de SuperBand 1.5.3).
 *
 * Transporte: Nordic UART (servicio 6E400001-..., escritura 6E400002-...).
 * Frame: CD | len16 BE | CMD | 01 | KEY | datalen16 BE | DATA
 *   - len = longitud total del frame menos 3 (excluye CD + len).
 * Sin cifrado.
 */
object WatchProtocol {

    const val UART_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9d"
    const val UART_WRITE_CHAR = "6e400002-b5a3-f393-e0a9-e50e24dcca9d"
    const val UART_NOTIFY_CHAR = "6e400003-b5a3-f393-e0a9-e50e24dcca9d"

    const val CMD_NOTIFY = 18
    const val KEY_NOTIFY_MSG = 18
    const val KEY_NOTIFY_CALL = 17
    const val KEY_TIME_SYNC = 1
    const val KEY_USER_INFO = 4

    const val MAX_TEXT_CHARS = 300

    /**
     * Handshake de pairing de app: (18,10) sin payload.
     * Formato estándar: CD 00 05 12 01 0A 00 00 (8 bytes).
     */
    fun buildAppPair(): ByteArray =
        buildFrame(CMD_NOTIFY, 10, ByteArray(0))

    /**
     * ACK de respuesta (0xDC) para frames del reloj.
     * Formato: DC | len | CMD | 01 | KEY | 00 01 | 00
     */
    fun buildAck(cmd: Int, key: Int): ByteArray {
        val totalLen = 1 + 2 + 1 + 1 + 1 + 2 + 1
        val lenField = totalLen - 3
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(0xDC.toByte())
        buf.putShort(lenField.toShort())
        buf.put(cmd.toByte())
        buf.put(0x01.toByte())
        buf.put(key.toByte())
        buf.putShort(1.toShort())
        buf.put(0x00.toByte())
        return buf.array()
    }

    /**
     * Sincronizar hora (18,1): u32 BE con bits
     * [(año-2000):6][mes:4][día:5][hora:5][min:6][seg:6]
     */
    fun buildTimeSync(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int
    ): ByteArray {
        val v = ((year - 2000) shl 26) or
                (month shl 22) or
                (day shl 17) or
                (hour shl 12) or
                (minute shl 6) or
                second
        val data = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        data.putInt(v)
        return buildFrame(CMD_NOTIFY, KEY_TIME_SYNC, data.array())
    }

    /** Hora actual del teléfono como comando (18,1). */
    fun buildTimeSyncNow(): ByteArray {
        val c = java.util.Calendar.getInstance()
        return buildTimeSync(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND)
        )
    }

    /**
     * Datos de usuario (18,4): u32 big-endian con género, edad, altura, peso.
     * bit31=género(1=m), bits30-24=edad, bits23-15=altura cm,
     * bits14-5=peso kg, bits4-0=unidad distancia(0=métrico).
     */
    fun buildUserInfo(): ByteArray {
        val gender = 1 // masculino
        val age = 30
        val height = 175 // cm
        val weight = 70 // kg
        val unit = 0 // métrico
        val packed = (gender shl 31) or (age shl 24) or (height shl 15) or (weight shl 5) or unit
        val data = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(packed).array()
        return buildFrame(CMD_NOTIFY, 4, data)
    }

    /**
     * Construye un frame completo: CD | len16BE | CMD | 01 | KEY | datalen16BE | DATA
     */
    fun buildFrame(cmd: Int, key: Int, data: ByteArray): ByteArray {
        val totalLen = 1 + 2 + 1 + 1 + 1 + 2 + data.size
        val lenField = totalLen - 3
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(0xCD.toByte())
        buf.putShort(lenField.toShort())
        buf.put(cmd.toByte())
        buf.put(0x01.toByte()) // versión
        buf.put(key.toByte())
        buf.putShort(data.size.toShort())
        buf.put(data)
        return buf.array()
    }

    /**
     * Push de notificación (18,18): DATA = [appIconID u32 LE][texto UTF-8].
     * Texto = "titulo:texto", truncado a 300 caracteres.
     */
    fun buildNotification(iconId: Int, title: String, text: String): ByteArray {
        val cleanTitle = title.trim().take(100)
        val cleanText = text.trim().take(200)
        val full = if (cleanTitle.isEmpty()) cleanText else "$cleanTitle:$cleanText"
        val truncated = full.take(MAX_TEXT_CHARS)
        val textBytes = truncated.toByteArray(Charsets.UTF_8)

        val data = ByteBuffer.allocate(4 + textBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(iconId)
        data.put(textBytes)

        // Reordenar a BIG_ENDIAN para el frame no afecta DATA (ya está en LE)
        val dataBytes = data.array()
        return buildFrame(CMD_NOTIFY, KEY_NOTIFY_MSG, dataBytes)
    }

    /**
     * Mapeo paquete Android -> appIconID (extraído del código de SuperBand).
     */
    fun iconIdForPackage(pkg: String): Int = when (pkg) {
        "com.whatsapp" -> 8
        "com.whatsapp.w4b" -> 8
        "org.telegram.messenger" -> 1 // Telegram: icono genérico
        "org.telegram.plus" -> 1
        "com.facebook.orca" -> 5   // Messenger
        "com.instagram.android" -> 6
        "com.facebook.katana" -> 7 // Facebook
        "com.twitter.android" -> 9
        "com.snapchat.android" -> 10
        "com.google.android.gm" -> 11 // Gmail
        "com.android.mms" -> 12      // SMS
        "com.google.android.dialer" -> 13
        "com.meta.ai", "ai.muse.mobile" -> 1 // Muse: icono genérico
        else -> 1 // genérico
    }

    /** Nombre mostrable para el log. */
    fun appLabel(pkg: String): String = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
        "org.telegram.messenger", "org.telegram.plus" -> "Telegram"
        "com.facebook.orca" -> "Messenger"
        "com.instagram.android" -> "Instagram"
        "com.facebook.katana" -> "Facebook"
        "com.twitter.android" -> "X"
        "com.google.android.gm" -> "Gmail"
        "com.meta.ai", "ai.muse.mobile" -> "Muse"
        else -> pkg
    }
}
