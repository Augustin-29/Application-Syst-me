package com.example.trackpulse.domain.protocol

import com.example.trackpulse.domain.model.AgpsPayload
import com.example.trackpulse.domain.model.BuzzerMelody
import com.example.trackpulse.domain.model.DeviceStatus
import com.example.trackpulse.domain.model.EPaperConfig
import com.example.trackpulse.domain.model.EPaperRefreshMode
import com.example.trackpulse.domain.model.EPaperTemplate
import com.example.trackpulse.domain.model.EasterEggGame
import com.example.trackpulse.domain.model.EasterEggScore
import com.example.trackpulse.domain.model.FlashSessionHeader
import com.example.trackpulse.domain.model.MotionAlert
import com.example.trackpulse.domain.model.TelemetrySample
import com.example.trackpulse.domain.model.TrackerMode
import com.example.trackpulse.domain.model.VehicleType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Implémentation exacte du protocole binaire compact défini dans PROTOCOL.md.
 * Toutes les valeurs multi-octets sont encodées en Little-Endian (natif STM32L433 Cortex-M4F).
 */
object TrackPulseProtocol {

    const val SOF_0: Byte = 0xAA.toByte()
    const val SOF_1: Byte = 0x55.toByte()
    const val PROTOCOL_VERSION: Int = 0x01
    const val HEADER_SIZE: Int = 7 // SOF(2) + VER_FLAGS(1) + OPCODE(1) + SEQ(1) + LEN(2)
    const val CRC_SIZE: Int = 2
    const val MAX_PAYLOAD_LEN: Int = 240

    const val FLAG_ACK_REQ: Int = 0x01
    const val FLAG_IS_4G_CHANNEL: Int = 0x02

    // Opcodes App -> Tracker
    const val CMD_PING: Byte = 0x01
    const val CMD_GET_STATUS: Byte = 0x02
    const val CMD_SET_MODE: Byte = 0x03
    const val CMD_INJECT_AGPS: Byte = 0x04
    const val CMD_BUZZER_PLAY: Byte = 0x05
    const val CMD_REQUEST_FIX: Byte = 0x06
    const val CMD_CLEAR_ALERT: Byte = 0x07

    const val CMD_TRACK_START: Byte = 0x10
    const val CMD_TRACK_STOP: Byte = 0x11
    const val CMD_FLASH_LIST: Byte = 0x12
    const val CMD_FLASH_READ: Byte = 0x13
    const val CMD_FLASH_ERASE_SESSION: Byte = 0x14
    const val CMD_FLASH_FORMAT: Byte = 0x15

    const val CMD_EPAPER_CONFIG: Byte = 0x20
    const val CMD_EPAPER_BITMAP: Byte = 0x21
    const val CMD_EASTER_EGG_CTRL: Byte = 0x30

    // Opcodes Tracker -> App
    const val RSP_ACK: Byte = 0x81.toByte()
    const val EVT_STATUS: Byte = 0x82.toByte()
    const val RSP_FLASH_SESSION: Byte = 0x92.toByte()
    const val RSP_FLASH_CHUNK: Byte = 0x93.toByte()
    const val EVT_MOTION_ALERT: Byte = 0xA0.toByte()
    const val EVT_LIVE_TELEMETRY: Byte = 0xA1.toByte()
    const val EVT_EASTER_EGG_SCORE: Byte = 0xB0.toByte()

    private val seqCounter = AtomicInteger(0)

    fun nextSeq(): Int = seqCounter.getAndIncrement() and 0xFF

    data class Frame(
        val version: Int,
        val flags: Int,
        val opcode: Byte,
        val seq: Int,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return version == other.version &&
                flags == other.flags &&
                opcode == other.opcode &&
                seq == other.seq &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var res = version
            res = 31 * res + flags
            res = 31 * res + opcode
            res = 31 * res + seq
            res = 31 * res + payload.contentHashCode()
            return res
        }
    }

    fun encodeFrame(
        opcode: Byte,
        payload: ByteArray = ByteArray(0),
        seq: Int = nextSeq(),
        requestAck: Boolean = false,
        is4gChannel: Boolean = false
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD_LEN) { "Payload exceeds $MAX_PAYLOAD_LEN bytes" }
        var flags = 0
        if (requestAck) flags = flags or FLAG_ACK_REQ
        if (is4gChannel) flags = flags or FLAG_IS_4G_CHANNEL
        val verFlags = ((PROTOCOL_VERSION and 0x0F) shl 4) or (flags and 0x0F)

        val totalLen = HEADER_SIZE + payload.size + CRC_SIZE
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(SOF_0)
        buf.put(SOF_1)
        buf.put(verFlags.toByte())
        buf.put(opcode)
        buf.put((seq and 0xFF).toByte())
        buf.putShort(payload.size.toShort())
        buf.put(payload)

        val raw = buf.array()
        val crc = Crc16Ccitt.compute(raw, offset = 2, length = 5 + payload.size)
        raw[totalLen - 2] = (crc and 0xFF).toByte()
        raw[totalLen - 1] = ((crc ushr 8) and 0xFF).toByte()
        return raw
    }

    fun decodeFrame(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE + CRC_SIZE) return null
        if (bytes[0] != SOF_0 || bytes[1] != SOF_1) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val verFlags = bytes[2].toInt() and 0xFF
        val version = (verFlags ushr 4) and 0x0F
        val flags = verFlags and 0x0F
        val opcode = bytes[3]
        val seq = bytes[4].toInt() and 0xFF
        val len = buf.getShort(5).toInt() and 0xFFFF
        val expectedTotal = HEADER_SIZE + len + CRC_SIZE
        if (len > MAX_PAYLOAD_LEN || bytes.size < expectedTotal) return null

        val computedCrc = Crc16Ccitt.compute(bytes, offset = 2, length = 5 + len)
        val receivedCrc = buf.getShort(HEADER_SIZE + len).toInt() and 0xFFFF
        if (computedCrc != receivedCrc) return null

        val payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)
        return Frame(version, flags, opcode, seq, payload)
    }

    // ---- Command payload encoders (App -> STM32) ----

    fun encodeSetMode(
        mode: TrackerMode,
        rtcWakeSec: Int = 3600,
        motionThresholdMg: Int = 160
    ): ByteArray {
        val threshSteps = (motionThresholdMg / 16).coerceIn(1, 255)
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(mode.code)
            putShort(rtcWakeSec.coerceIn(10, 65535).toShort())
            put(threshSteps.toByte())
        }.array()
    }

    fun encodeAgps(payload: AgpsPayload): ByteArray {
        var flags = 0
        if (payload.hasValidPosition) flags = flags or 0x01
        if (payload.hasValidTime) flags = flags or 0x02
        return ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(payload.utcEpochSec.toInt())
            putShort(payload.utcMillis.coerceIn(0, 999).toShort())
            putInt((payload.latitude * 1e7).roundToInt())
            putInt((payload.longitude * 1e7).roundToInt())
            putShort((payload.altitudeM * 10f).roundToInt().coerceIn(-32768, 32767).toShort())
            putShort(payload.accuracyM.coerceIn(0, 65535).toShort())
            put(flags.toByte())
        }.array()
    }

    fun encodeBuzzer(
        melody: BuzzerMelody,
        repeatCount: Int = 1,
        tempoScale: Int = 10
    ): ByteArray = byteArrayOf(
        melody.code,
        repeatCount.coerceIn(0, 10).toByte(),
        tempoScale.coerceIn(5, 20).toByte()
    )

    fun encodeRequestFix(timeoutSec: Int = 45): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(timeoutSec.coerceIn(5, 300).toShort())
            .array()

    fun encodeTrackStart(
        sampleRateHz: Int = 10,
        accelRangeG: Int = 8,
        vehicleType: VehicleType = VehicleType.KART,
        streamLiveOverBle: Boolean = true
    ): ByteArray = byteArrayOf(
        sampleRateHz.coerceIn(1, 100).toByte(),
        accelRangeG.coerceIn(2, 16).toByte(),
        vehicleType.code,
        if (streamLiveOverBle) 0x01 else 0x00
    )

    fun encodeFlashRead(sessionId: Int, offsetBytes: Int, maxLen: Int = 224): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(sessionId.toShort())
            putInt(offsetBytes)
            putShort(maxLen.toShort())
        }.array()

    fun encodeEPaperConfig(config: EPaperConfig): ByteArray {
        var flags = 0
        if (config.invertColors) flags = flags or 0x01
        if (config.showBattery) flags = flags or 0x02
        if (config.showGpsSats) flags = flags or 0x04
        if (config.autoSwitchWithMode) flags = flags or 0x08

        val out = ByteArray(60)
        out[0] = config.template.code
        out[1] = config.refreshMode.code
        out[2] = flags.toByte()
        out[3] = 0
        copyFixedCString(config.customTitle, out, offset = 4, maxLen = 24)
        copyFixedCString(config.customSubtitle, out, offset = 28, maxLen = 32)
        return out
    }

    fun encodeEasterEggControl(action: Int, game: EasterEggGame, soundEnabled: Boolean): ByteArray =
        byteArrayOf(
            action.toByte(),
            game.code,
            if (soundEnabled) 0x01 else 0x00
        )

    /**
     * Compresse un framebuffer E-Paper 200x200 1-bit (5 000 octets) avec PackBits RLE.
     */
    fun encodePackBitsRle(raw: ByteArray): ByteArray {
        val result = ArrayList<Byte>(raw.size)
        var i = 0
        while (i < raw.size) {
            var runLen = 1
            while (i + runLen < raw.size && runLen < 128 && raw[i + runLen] == raw[i]) {
                runLen++
            }
            if (runLen >= 3) {
                result.add((257 - runLen).toByte())
                result.add(raw[i])
                i += runLen
            } else {
                val litStart = i
                var litLen = 0
                while (i < raw.size && litLen < 128) {
                    if (i + 2 < raw.size && raw[i] == raw[i + 1] && raw[i] == raw[i + 2]) break
                    i++
                    litLen++
                }
                result.add((litLen - 1).toByte())
                for (k in 0 until litLen) result.add(raw[litStart + k])
            }
        }
        return result.toByteArray()
    }

    // ---- Event & Response decoders (STM32 -> App) ----

    fun encodeStatusPayload(status: DeviceStatus): ByteArray {
        var flags = 0
        if (status.isCharging) flags = flags or 0x01
        if (status.hasUsbFault) flags = flags or 0x02
        if (status.hasGpsFix) flags = flags or 0x04
        if (status.isRecording) flags = flags or 0x08
        if (status.isBleConnected) flags = flags or 0x10
        if (status.isLteAttached) flags = flags or 0x20
        if (status.isAgpsValid) flags = flags or 0x40
        return ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(status.mode.code)
            put(flags.toByte())
            putShort(status.batteryMv.toShort())
            put(status.batteryPct.toByte())
            putInt(status.flashUsedBytes.toInt())
            put(status.gpsSats.toByte())
            putInt((status.latitude * 1e7).roundToInt())
            putInt((status.longitude * 1e7).roundToInt())
            putShort(((status.speedKmh / 3.6f) * 100f).roundToInt().coerceIn(0, 65535).toShort())
            putInt(status.uptimeSec.toInt())
            put(status.activeEPaperTemplateId)
        }.array()
    }

    fun decodeStatusPayload(payload: ByteArray): DeviceStatus? {
        if (payload.size < 25) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val mode = TrackerMode.fromCode(buf.get())
        val flags = buf.get().toInt() and 0xFF
        val batteryMv = buf.short.toInt() and 0xFFFF
        val batteryPct = buf.get().toInt() and 0xFF
        val flashUsed = buf.int.toLong() and 0xFFFFFFFFL
        val gpsSats = buf.get().toInt() and 0xFF
        val lat = buf.int / 1e7
        val lon = buf.int / 1e7
        val speedCms = buf.short.toInt() and 0xFFFF
        val uptimeSec = buf.int.toLong() and 0xFFFFFFFFL
        val epaperTpl = buf.get()

        return DeviceStatus(
            mode = mode,
            batteryMv = batteryMv,
            batteryPct = batteryPct.coerceIn(0, 100),
            isCharging = (flags and 0x01) != 0,
            hasUsbFault = (flags and 0x02) != 0,
            hasGpsFix = (flags and 0x04) != 0,
            isRecording = (flags and 0x08) != 0,
            isBleConnected = (flags and 0x10) != 0,
            isLteAttached = (flags and 0x20) != 0,
            isAgpsValid = (flags and 0x40) != 0,
            flashUsedBytes = flashUsed,
            gpsSats = gpsSats,
            latitude = lat,
            longitude = lon,
            speedKmh = (speedCms / 100f) * 3.6f,
            uptimeSec = uptimeSec,
            activeEPaperTemplateId = epaperTpl,
            lastUpdateMs = System.currentTimeMillis()
        )
    }

    fun encodeMotionAlertPayload(alert: MotionAlert): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(alert.alertId.toByte())
            putInt(alert.timestampSec.toInt())
            putShort((alert.peakAccelG * 1000f).roundToInt().coerceIn(0, 65535).toShort())
            put(alert.axisMask.toByte())
            putInt((alert.latitude * 1e7).roundToInt())
            putInt((alert.longitude * 1e7).roundToInt())
            put(alert.batteryPct.toByte())
            putShort(((alert.speedKmh / 3.6f) * 100f).roundToInt().coerceIn(0, 65535).toShort())
        }.array()

    fun decodeMotionAlertPayload(payload: ByteArray): MotionAlert? {
        if (payload.size < 19) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val alertId = buf.get().toInt() and 0xFF
        val utcSec = buf.int.toLong() and 0xFFFFFFFFL
        val peakMg = buf.short.toInt() and 0xFFFF
        val axisMask = buf.get().toInt() and 0xFF
        val lat = buf.int / 1e7
        val lon = buf.int / 1e7
        val battPct = buf.get().toInt() and 0xFF
        val speedCms = buf.short.toInt() and 0xFFFF
        return MotionAlert(
            alertId = alertId,
            timestampSec = utcSec,
            peakAccelG = peakMg / 1000f,
            axisMask = axisMask,
            latitude = lat,
            longitude = lon,
            batteryPct = battPct,
            speedKmh = (speedCms / 100f) * 3.6f
        )
    }

    fun encodeTelemetrySample(sample: TelemetrySample): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(sample.dtMs.coerceIn(0, 65535).toShort())
            putInt((sample.latitude * 1e7).roundToInt())
            putInt((sample.longitude * 1e7).roundToInt())
            putShort(((sample.speedKmh / 3.6f) * 100f).roundToInt().coerceIn(0, 65535).toShort())
            putShort((sample.axG * 1000f).roundToInt().coerceIn(-32768, 32767).toShort())
            putShort((sample.ayG * 1000f).roundToInt().coerceIn(-32768, 32767).toShort())
        }.array()

    fun decodeTelemetrySample(bytes: ByteArray, offset: Int = 0): TelemetrySample? {
        if (bytes.size - offset < 16) return null
        val buf = ByteBuffer.wrap(bytes, offset, 16).order(ByteOrder.LITTLE_ENDIAN)
        val dtMs = buf.short.toInt() and 0xFFFF
        val lat = buf.int / 1e7
        val lon = buf.int / 1e7
        val speedCms = buf.short.toInt() and 0xFFFF
        val axMg = buf.short.toInt()
        val ayMg = buf.short.toInt()
        return TelemetrySample(
            dtMs = dtMs,
            latitude = lat,
            longitude = lon,
            speedKmh = (speedCms / 100f) * 3.6f,
            axG = axMg / 1000f,
            ayG = ayMg / 1000f
        )
    }

    data class FlashSessionListEntry(
        val entryIndex: Int,
        val entryCount: Int,
        val header: FlashSessionHeader
    )

    fun encodeFlashSessionEntry(entry: FlashSessionListEntry): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(entry.entryIndex.toByte())
            put(entry.entryCount.toByte())
            putShort(entry.header.sessionId.toShort())
            putInt(entry.header.startUtcSec.toInt())
            putInt(entry.header.durationMs.toInt())
            putInt(entry.header.sampleCount)
            putShort(((entry.header.maxSpeedKmh / 3.6f) * 100f).roundToInt().coerceIn(0, 65535).toShort())
            putShort((entry.header.peakG * 1000f).roundToInt().coerceIn(0, 65535).toShort())
            put(entry.header.sampleRateHz.toByte())
            put(entry.header.vehicleType.code)
            putShort(0)
        }.array()

    fun decodeFlashSessionEntry(payload: ByteArray): FlashSessionListEntry? {
        if (payload.size < 24) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val index = buf.get().toInt() and 0xFF
        val count = buf.get().toInt() and 0xFF
        val sessionId = buf.short.toInt() and 0xFFFF
        val startUtc = buf.int.toLong() and 0xFFFFFFFFL
        val durationMs = buf.int.toLong() and 0xFFFFFFFFL
        val sampleCount = buf.int
        val maxSpeedCms = buf.short.toInt() and 0xFFFF
        val peakGMg = buf.short.toInt() and 0xFFFF
        val rateHz = buf.get().toInt() and 0xFF
        val vehicleType = VehicleType.fromCode(buf.get())
        return FlashSessionListEntry(
            entryIndex = index,
            entryCount = count,
            header = FlashSessionHeader(
                sessionId = sessionId,
                startUtcSec = startUtc,
                durationMs = durationMs,
                sampleCount = sampleCount,
                maxSpeedKmh = (maxSpeedCms / 100f) * 3.6f,
                peakG = peakGMg / 1000f,
                sampleRateHz = rateHz,
                vehicleType = vehicleType
            )
        )
    }

    data class FlashChunkResponse(
        val sessionId: Int,
        val offsetBytes: Int,
        val isLastChunk: Boolean,
        val samples: List<TelemetrySample>
    )

    fun encodeFlashChunk(chunk: FlashChunkResponse): ByteArray {
        val count = chunk.samples.size.coerceAtMost(14)
        val buf = ByteBuffer.allocate(8 + count * 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(chunk.sessionId.toShort())
        buf.putInt(chunk.offsetBytes)
        buf.put(if (chunk.isLastChunk) 0x01 else 0x00)
        buf.put(count.toByte())
        for (i in 0 until count) {
            buf.put(encodeTelemetrySample(chunk.samples[i]))
        }
        return buf.array()
    }

    fun decodeFlashChunk(payload: ByteArray): FlashChunkResponse? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val sessionId = buf.short.toInt() and 0xFFFF
        val offsetBytes = buf.int
        val flags = buf.get().toInt() and 0xFF
        val count = buf.get().toInt() and 0xFF
        val samples = mutableListOf<TelemetrySample>()
        for (i in 0 until count) {
            val sampleOffset = 8 + i * 16
            decodeTelemetrySample(payload, sampleOffset)?.let { samples += it }
        }
        return FlashChunkResponse(
            sessionId = sessionId,
            offsetBytes = offsetBytes,
            isLastChunk = (flags and 0x01) != 0,
            samples = samples
        )
    }

    fun encodeEasterEggScore(score: EasterEggScore): ByteArray =
        ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(score.game.code)
            putInt(score.lastScore)
            putInt(score.highScore)
            putShort(score.gamesPlayed.coerceIn(0, 65535).toShort())
            putShort(score.playTimeSec.coerceIn(0, 65535).toShort())
        }.array()

    fun decodeEasterEggScore(payload: ByteArray): EasterEggScore? {
        if (payload.size < 13) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val game = EasterEggGame.fromCode(buf.get())
        val lastScore = buf.int
        val highScore = buf.int
        val gamesPlayed = buf.short.toInt() and 0xFFFF
        val playTimeSec = buf.short.toInt() and 0xFFFF
        return EasterEggScore(game, lastScore, highScore, gamesPlayed, playTimeSec)
    }

    private fun copyFixedCString(text: String, dest: ByteArray, offset: Int, maxLen: Int) {
        val raw = text.toByteArray(Charsets.UTF_8)
        val copyLen = raw.size.coerceAtMost(maxLen - 1)
        System.arraycopy(raw, 0, dest, offset, copyLen)
        dest[offset + copyLen] = 0
    }
}

/**
 * Parseur de flux binaire ré-entrant pour reconstituer les trames TrackPulse
 * fragmentées sur le canal BLE Transparent UART (MTU 20..244 octets).
 */
class FrameStreamParser {
    private val buffer = ArrayList<Byte>(512)

    fun feed(chunk: ByteArray): List<TrackPulseProtocol.Frame> {
        val frames = mutableListOf<TrackPulseProtocol.Frame>()
        for (b in chunk) buffer.add(b)

        while (buffer.size >= TrackPulseProtocol.HEADER_SIZE + TrackPulseProtocol.CRC_SIZE) {
            val sofIdx = findSof()
            if (sofIdx < 0) {
                // Conserve le dernier octet au cas où il s'agit de 0xAA
                val keepLast = buffer.lastOrNull() == TrackPulseProtocol.SOF_0
                buffer.clear()
                if (keepLast) buffer.add(TrackPulseProtocol.SOF_0)
                break
            }
            if (sofIdx > 0) {
                buffer.subList(0, sofIdx).clear()
            }
            if (buffer.size < TrackPulseProtocol.HEADER_SIZE + TrackPulseProtocol.CRC_SIZE) break

            val lenL = buffer[5].toInt() and 0xFF
            val lenH = buffer[6].toInt() and 0xFF
            val payloadLen = lenL or (lenH shl 8)
            if (payloadLen > TrackPulseProtocol.MAX_PAYLOAD_LEN) {
                // Faux SOF : on saute les 2 octets
                buffer.subList(0, 2).clear()
                continue
            }
            val frameLen = TrackPulseProtocol.HEADER_SIZE + payloadLen + TrackPulseProtocol.CRC_SIZE
            if (buffer.size < frameLen) break

            val candidate = ByteArray(frameLen) { buffer[it] }
            val decoded = TrackPulseProtocol.decodeFrame(candidate)
            if (decoded != null) {
                frames += decoded
                buffer.subList(0, frameLen).clear()
            } else {
                // CRC invalide : on avance d'un octet pour resynchroniser
                buffer.removeAt(0)
            }
        }
        return frames
    }

    fun reset() {
        buffer.clear()
    }

    private fun findSof(): Int {
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == TrackPulseProtocol.SOF_0 && buffer[i + 1] == TrackPulseProtocol.SOF_1) {
                return i
            }
        }
        return -1
    }
}
