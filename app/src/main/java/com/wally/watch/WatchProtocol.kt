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

    const val UART_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val UART_WRITE_CHAR = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val UART_NOTIFY_CHAR = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    const val CMD_NOTIFY = 18
    const val KEY_NOTIFY_MSG = 18
    const val KEY_NOTIFY_CALL = 17

    const val MAX_TEXT_CHARS = 300

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
        "org.telegram.messenger" -> 18
        "org.telegram.plus" -> 18
        "com.facebook.orca" -> 5   // Messenger
        "com.instagram.android" -> 6
        "com.facebook.katana" -> 7 // Facebook
        "com.twitter.android" -> 9
        "com.snapchat.android" -> 10
        "com.google.android.gm" -> 11 // Gmail
        "com.android.mms" -> 12      // SMS
        "com.google.android.dialer" -> 13
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
        else -> pkg
    }
}
