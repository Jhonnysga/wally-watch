package com.wally.watch

import org.junit.Assert.*
import org.junit.Test

/**
 * Valida el constructor de tramas contra la captura BLE real
 * (push de WhatsApp del 06/10/2026):
 *   cd002c120112002708000057686174734170703a...
 */
class WatchProtocolTest {

    @Test
    fun notificationFrame_matchesLiveCapture() {
        val frame = WatchProtocol.buildNotification(8, "WhatsApp", "6 mensajes de 2 chats")

        // Magic
        assertEquals(0xCD.toByte(), frame[0])

        // len16 BE = total - 3
        val len = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        assertEquals(frame.size - 3, len)

        // CMD=18, versión=1, KEY=18
        assertEquals(18.toByte(), frame[3])
        assertEquals(0x01.toByte(), frame[4])
        assertEquals(18.toByte(), frame[5])

        // datalen16 BE
        val datalen = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
        assertEquals(frame.size - 8, datalen)

        // appIconID u32 LE = 8 (WhatsApp)
        assertEquals(8.toByte(), frame[8])
        assertEquals(0.toByte(), frame[9])
        assertEquals(0.toByte(), frame[10])
        assertEquals(0.toByte(), frame[11])

        // Texto UTF-8 "WhatsApp:6 mensajes de 2 chats"
        val text = String(frame, 12, frame.size - 12, Charsets.UTF_8)
        assertEquals("WhatsApp:6 mensajes de 2 chats", text)

        // Primeros bytes idénticos a la captura en vivo
        val expected = "cd002c120112002708000057686174734170703a"
        val actual = frame.take(20).joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun iconIdMapping() {
        assertEquals(8, WatchProtocol.iconIdForPackage("com.whatsapp"))
        assertEquals(18, WatchProtocol.iconIdForPackage("org.telegram.messenger"))
    }
}
