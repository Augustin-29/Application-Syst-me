package com.example.trackpulse.domain.protocol

/**
 * CRC-16/CCITT-FALSE (polynôme 0x1021, init 0xFFFF, sans réflexion, xorout 0x0000).
 * Strictement identique à la fonction C `tp_crc16_ccitt()` spécifiée dans PROTOCOL.md.
 */
object Crc16Ccitt {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        val end = (offset + length).coerceAtMost(data.size)
        for (i in offset until end) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }
}
