package com.example.trackpulse

import com.example.trackpulse.domain.model.AgpsPayload
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
import com.example.trackpulse.domain.protocol.Crc16Ccitt
import com.example.trackpulse.domain.protocol.FrameStreamParser
import com.example.trackpulse.domain.protocol.QrPairingParser
import com.example.trackpulse.domain.protocol.TrackPulseProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPulseProtocolTest {

    @Test
    fun crc16Ccitt_matchesStandardCheckValue() {
        // Vecteur standard CRC-16/CCITT-FALSE : "123456789" -> 0x29B1
        val crc = Crc16Ccitt.compute("123456789".toByteArray(Charsets.US_ASCII))
        assertEquals(0x29B1, crc)
    }

    @Test
    fun frameEncodeDecode_roundTripAndCrcRejection() {
        val payload = TrackPulseProtocol.encodeAgps(
            AgpsPayload(
                utcEpochSec = 1_760_000_000L,
                utcMillis = 450,
                latitude = 47.956012,
                longitude = 0.207845,
                altitudeM = 62.5f,
                accuracyM = 8
            )
        )
        assertEquals(19, payload.size)

        val encoded = TrackPulseProtocol.encodeFrame(
            opcode = TrackPulseProtocol.CMD_INJECT_AGPS,
            payload = payload,
            seq = 42,
            requestAck = true
        )
        assertEquals(9 + 19, encoded.size)

        val decoded = TrackPulseProtocol.decodeFrame(encoded)
        assertNotNull(decoded)
        assertEquals(TrackPulseProtocol.CMD_INJECT_AGPS, decoded!!.opcode)
        assertEquals(42, decoded.seq)
        assertTrue((decoded.flags and TrackPulseProtocol.FLAG_ACK_REQ) != 0)

        // Corruption d'un bit -> rejet CRC obligatoire
        val corrupted = encoded.copyOf()
        corrupted[10] = (corrupted[10].toInt() xor 0x01).toByte()
        assertNull(TrackPulseProtocol.decodeFrame(corrupted))
    }

    @Test
    fun statusPayload_roundTripMatchesExact25Bytes() {
        val original = DeviceStatus(
            mode = TrackerMode.TRACK_RECORDING,
            batteryMv = 4110,
            batteryPct = 90,
            isCharging = true,
            hasUsbFault = false,
            hasGpsFix = true,
            isRecording = true,
            isBleConnected = true,
            isLteAttached = true,
            isAgpsValid = true,
            flashUsedBytes = 2_097_152L,
            gpsSats = 12,
            latitude = 47.956123,
            longitude = 0.207987,
            speedKmh = 92.52f,
            uptimeSec = 86_400L,
            activeEPaperTemplateId = 0x01
        )
        val raw = TrackPulseProtocol.encodeStatusPayload(original)
        assertEquals(25, raw.size)

        val decoded = TrackPulseProtocol.decodeStatusPayload(raw)
        assertNotNull(decoded)
        assertEquals(TrackerMode.TRACK_RECORDING, decoded!!.mode)
        assertEquals(4110, decoded.batteryMv)
        assertEquals(90, decoded.batteryPct)
        assertTrue(decoded.isCharging)
        assertTrue(decoded.isAgpsValid)
        assertEquals(2_097_152L, decoded.flashUsedBytes)
        assertEquals(47.956123, decoded.latitude, 1e-6)
        assertEquals(0.207987, decoded.longitude, 1e-6)
        assertEquals(92.52f, decoded.speedKmh, 0.1f)
    }

    @Test
    fun telemetrySample_isExact16BytesAndFlashChunkDecodesCleanly() {
        val samples = listOf(
            TelemetrySample(100, 47.9560, 0.2078, 84.6f, 0.45f, -1.62f),
            TelemetrySample(100, 47.9561, 0.2079, 88.2f, -1.20f, 1.35f)
        )
        assertEquals(16, TrackPulseProtocol.encodeTelemetrySample(samples[0]).size)

        val chunkBytes = TrackPulseProtocol.encodeFlashChunk(
            TrackPulseProtocol.FlashChunkResponse(
                sessionId = 101,
                offsetBytes = 0,
                isLastChunk = true,
                samples = samples
            )
        )
        val decoded = TrackPulseProtocol.decodeFlashChunk(chunkBytes)
        assertNotNull(decoded)
        assertEquals(101, decoded!!.sessionId)
        assertTrue(decoded.isLastChunk)
        assertEquals(2, decoded.samples.size)
        assertEquals(-1.62f, decoded.samples[0].ayG, 0.005f)
        assertEquals(-1.20f, decoded.samples[1].axG, 0.005f)
    }

    @Test
    fun streamParser_reassemblesFragmentedBleMtu20PacketsWithLeadingNoise() {
        val alert = MotionAlert(
            alertId = 7,
            timestampSec = 1_760_000_123L,
            peakAccelG = 2.15f,
            axisMask = 0x0B,
            latitude = 47.9560,
            longitude = 0.2078,
            batteryPct = 78,
            speedKmh = 12.6f
        )
        val frame1 = TrackPulseProtocol.encodeFrame(
            TrackPulseProtocol.EVT_MOTION_ALERT,
            TrackPulseProtocol.encodeMotionAlertPayload(alert),
            seq = 1
        )
        val frame2 = TrackPulseProtocol.encodeFrame(
            TrackPulseProtocol.EVT_EASTER_EGG_SCORE,
            TrackPulseProtocol.encodeEasterEggScore(
                EasterEggScore(EasterEggGame.PULSE_RUNNER, 540, 1450, 12, 600)
            ),
            seq = 2
        )

        val combined = byteArrayOf(0x00, 0xFF.toByte(), 0x13) + frame1 + frame2
        val parser = FrameStreamParser()
        val parsed = mutableListOf<TrackPulseProtocol.Frame>()

        // Fragmentation en paquets BLE de 20 octets
        var i = 0
        while (i < combined.size) {
            val end = (i + 20).coerceAtMost(combined.size)
            parsed += parser.feed(combined.copyOfRange(i, end))
            i = end
        }

        assertEquals(2, parsed.size)
        val decodedAlert = TrackPulseProtocol.decodeMotionAlertPayload(parsed[0].payload)
        assertNotNull(decodedAlert)
        assertEquals(7, decodedAlert!!.alertId)
        assertEquals(2.15f, decodedAlert.peakAccelG, 0.01f)

        val decodedScore = TrackPulseProtocol.decodeEasterEggScore(parsed[1].payload)
        assertNotNull(decodedScore)
        assertEquals(1450, decodedScore!!.highScore)
    }

    @Test
    fun epaperConfigAndFlashHeader_encodeExpectedSizes() {
        val epdPayload = TrackPulseProtocol.encodeEPaperConfig(
            EPaperConfig(
                template = EPaperTemplate.CUSTOM_CARD,
                refreshMode = EPaperRefreshMode.FULL,
                invertColors = true,
                customTitle = "TRACKPULSE #01",
                customSubtitle = "LE MANS KARTING"
            )
        )
        assertEquals(60, epdPayload.size)

        val rle = TrackPulseProtocol.encodePackBitsRle(ByteArray(5_000) { 0xFF.toByte() })
        assertTrue("PackBits RLE should compress solid 5000B buffer dramatically", rle.size < 120)

        val headerEntry = TrackPulseProtocol.FlashSessionListEntry(
            entryIndex = 0,
            entryCount = 2,
            header = FlashSessionHeader(
                sessionId = 105,
                startUtcSec = 1_760_000_000L,
                durationMs = 180_000L,
                sampleCount = 1_800,
                maxSpeedKmh = 95.4f,
                peakG = 1.92f,
                sampleRateHz = 10,
                vehicleType = VehicleType.KART
            )
        )
        val decodedEntry = TrackPulseProtocol.decodeFlashSessionEntry(
            TrackPulseProtocol.encodeFlashSessionEntry(headerEntry)
        )
        assertNotNull(decodedEntry)
        assertEquals(105, decodedEntry!!.header.sessionId)
        assertEquals(VehicleType.KART, decodedEntry.header.vehicleType)
    }

    @Test
    fun qrPairingParser_parsesCompactEPaperCodesAndRejectsInvalid() {
        val hw = QrPairingParser.parse("TP1:8F3A:492015")
        assertNotNull(hw)
        assertEquals("8F3A", hw!!.deviceId)
        assertEquals("492015", hw.pairingKey)
        assertEquals("TrackPulse-8F3A", hw.bleNameFilter)
        assertEquals("tp/8f3a/u", hw.uplinkTopic)
        assertEquals("tp/8f3a/d", hw.downlinkTopic)
        assertEquals("tp/8f3a/a", hw.alertTopic)
        assertFalse(hw.isSimulatedDevice)

        val sim = QrPairingParser.parse(QrPairingParser.SIMULATOR_QR_CODE)
        assertNotNull(sim)
        assertTrue(sim!!.isSimulatedDevice)
        assertEquals("sim1", sim.mqttDeviceId)

        assertNull(QrPairingParser.parse("https://example.com/too-long-for-epaper-200x200"))
        assertNull(QrPairingParser.parse("TP2:8F3A:492015"))
        assertNull(QrPairingParser.parse("TP1:A:12"))
    }
}
