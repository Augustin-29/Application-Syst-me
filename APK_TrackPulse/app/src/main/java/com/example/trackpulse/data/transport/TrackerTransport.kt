package com.example.trackpulse.data.transport

import com.example.trackpulse.domain.model.ConnectionChannel
import com.example.trackpulse.domain.protocol.TrackPulseProtocol
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Couche d'abstraction transport commune pour BLE (RN4871), 4G/MQTT (SIM7080G) et Simulateur STM32.
 */
interface TrackerTransport {
    val channel: ConnectionChannel
    val state: StateFlow<TransportState>
    val rssiDbm: StateFlow<Int?>
    val statusMessage: StateFlow<String>
    val incomingFrames: SharedFlow<TrackPulseProtocol.Frame>

    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun sendFrame(
        opcode: Byte,
        payload: ByteArray = ByteArray(0),
        requestAck: Boolean = false
    ): Boolean
}
