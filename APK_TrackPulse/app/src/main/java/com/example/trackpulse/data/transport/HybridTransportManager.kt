package com.example.trackpulse.data.transport

import android.content.Context
import com.example.trackpulse.data.location.PhoneAgpsProvider
import com.example.trackpulse.domain.model.AgpsInjectionLog
import com.example.trackpulse.domain.model.ConnectionChannel
import com.example.trackpulse.domain.model.MqttSettings
import com.example.trackpulse.domain.model.TransportPreference
import com.example.trackpulse.domain.protocol.TrackPulseProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrateur hybride BLE (prioritaire) / 4G MQTT (repli automatique) + Simulateur STM32.
 *
 * Règles métier clés :
 * 1. Priorité absolue au BLE (RN4871) dès que le boîtier est à portée pour économiser la batterie et les 500 Mo 4G.
 * 2. Bascule automatique vers MQTT 4G (SIM7080G) si le BLE échoue ou se déconnecte.
 * 3. Injection A-GPS automatique dès qu'une session BLE (ou simulateur local) s'établit.
 * 4. Blocage des téléchargements Flash NOR (8 Mo) sur le canal 4G MQTT.
 */
class HybridTransportManager(
    context: Context,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> MqttSettings
) {
    private val agpsProvider = PhoneAgpsProvider(context)
    val bleTransport = BleTrackerTransport(
        context = context,
        scope = scope,
        deviceNameFilterProvider = { settingsProvider().bleDeviceNameFilter }
    )
    val mqttTransport = MqttTrackerTransport(
        scope = scope,
        settingsProvider = settingsProvider
    )
    val simulatedTransport = SimulatedTrackerTransport(scope = scope)

    private var activeTransport: TrackerTransport = simulatedTransport

    private val _preference = MutableStateFlow(TransportPreference.DEMO_SIMULATOR)
    val preference: StateFlow<TransportPreference> = _preference.asStateFlow()

    private val _activeChannel = MutableStateFlow(ConnectionChannel.DISCONNECTED)
    val activeChannel: StateFlow<ConnectionChannel> = _activeChannel.asStateFlow()

    private val _transportState = MutableStateFlow(TransportState.DISCONNECTED)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Prêt à connecter TrackPulse")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _rssiDbm = MutableStateFlow<Int?>(null)
    val rssiDbm: StateFlow<Int?> = _rssiDbm.asStateFlow()

    private val _lastAgpsLog = MutableStateFlow<AgpsInjectionLog?>(null)
    val lastAgpsLog: StateFlow<AgpsInjectionLog?> = _lastAgpsLog.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<TrackPulseProtocol.Frame>(extraBufferCapacity = 128)
    val incomingFrames: SharedFlow<TrackPulseProtocol.Frame> = _incomingFrames.asSharedFlow()

    private var collectorsJob: Job? = null
    private var bleFallbackWatcherJob: Job? = null

    val isBulkFlashAllowed: Boolean
        get() = _activeChannel.value == ConnectionChannel.BLE ||
            (_activeChannel.value == ConnectionChannel.SIMULATED && _rssiDbm.value != null)

    val mqttSessionBytes: StateFlow<Long>
        get() = mqttTransport.sessionBytesTransferred

    fun setPreferenceAndConnect(pref: TransportPreference) {
        _preference.value = pref
        scope.launch(Dispatchers.IO) {
            connectActiveStrategy()
        }
    }

    suspend fun connectActiveStrategy(): Boolean {
        stopAllTransports()
        return when (_preference.value) {
            TransportPreference.DEMO_SIMULATOR -> {
                bindAndConnect(simulatedTransport, triggerAgps = true)
            }

            TransportPreference.FORCE_BLE -> {
                bindAndConnect(bleTransport, triggerAgps = true)
            }

            TransportPreference.FORCE_MQTT -> {
                bindAndConnect(mqttTransport, triggerAgps = false)
            }

            TransportPreference.AUTO_HYBRID -> {
                _transportState.value = TransportState.SCANNING
                _statusMessage.value = "Hybride : recherche BLE RN4871 prioritaire…"
                val bleOk = bindAndConnect(bleTransport, triggerAgps = true)
                if (bleOk) {
                    watchBleDisconnectForMqttFallback()
                    true
                } else {
                    _statusMessage.value = "BLE hors portée → bascule automatique 4G MQTT…"
                    bindAndConnect(mqttTransport, triggerAgps = false)
                }
            }
        }
    }

    private fun watchBleDisconnectForMqttFallback() {
        bleFallbackWatcherJob?.cancel()
        bleFallbackWatcherJob = scope.launch(Dispatchers.IO) {
            bleTransport.state.collect { state ->
                if (_preference.value == TransportPreference.AUTO_HYBRID &&
                    activeTransport === bleTransport &&
                    (state == TransportState.DISCONNECTED || state == TransportState.ERROR)
                ) {
                    _statusMessage.value = "Lien BLE perdu → bascule automatique sur 4G MQTT"
                    bindAndConnect(mqttTransport, triggerAgps = false)
                }
            }
        }
    }

    private suspend fun bindAndConnect(
        transport: TrackerTransport,
        triggerAgps: Boolean
    ): Boolean {
        activeTransport = transport
        attachCollectors(transport)
        val ok = transport.connect()
        if (ok) {
            _activeChannel.value = transport.channel
            if (triggerAgps) {
                injectAgpsNow()
            }
        } else {
            _activeChannel.value = ConnectionChannel.DISCONNECTED
        }
        return ok
    }

    private fun attachCollectors(transport: TrackerTransport) {
        collectorsJob?.cancel()
        collectorsJob = scope.launch {
            launch {
                transport.state.collect { st ->
                    _transportState.value = st
                    if (st == TransportState.CONNECTED) {
                        _activeChannel.value = transport.channel
                    } else if (st == TransportState.DISCONNECTED) {
                        _activeChannel.value = ConnectionChannel.DISCONNECTED
                    }
                }
            }
            launch {
                transport.rssiDbm.collect { rssi ->
                    _rssiDbm.value = rssi
                    if (transport === simulatedTransport && _transportState.value == TransportState.CONNECTED) {
                        _activeChannel.value = if (rssi != null) {
                            ConnectionChannel.SIMULATED
                        } else {
                            ConnectionChannel.LTE_MQTT
                        }
                    }
                }
            }
            launch {
                transport.statusMessage.collect { msg -> _statusMessage.value = msg }
            }
            launch {
                transport.incomingFrames.collect { frame -> _incomingFrames.emit(frame) }
            }
        }
    }

    suspend fun injectAgpsNow(): Boolean {
        val agps = agpsProvider.captureAgpsPayload()
        val payload = TrackPulseProtocol.encodeAgps(agps)
        val sent = activeTransport.sendFrame(
            opcode = TrackPulseProtocol.CMD_INJECT_AGPS,
            payload = payload,
            requestAck = true
        )
        _lastAgpsLog.value = AgpsInjectionLog(
            timestampMs = System.currentTimeMillis(),
            latitude = agps.latitude,
            longitude = agps.longitude,
            accuracyM = agps.accuracyM,
            success = sent,
            message = if (sent) {
                if (agps.hasValidPosition) {
                    "A-GPS injecté (UTC + GPS ±${agps.accuracyM} m)"
                } else {
                    "Heure UTC + position approx. injectées"
                }
            } else {
                "Échec envoi A-GPS"
            }
        )
        return sent
    }

    suspend fun sendFrame(
        opcode: Byte,
        payload: ByteArray = ByteArray(0),
        requestAck: Boolean = false
    ): Boolean {
        return activeTransport.sendFrame(opcode, payload, requestAck)
    }

    suspend fun disconnectAll() {
        stopAllTransports()
        _activeChannel.value = ConnectionChannel.DISCONNECTED
        _transportState.value = TransportState.DISCONNECTED
        _rssiDbm.value = null
    }

    private suspend fun stopAllTransports() {
        bleFallbackWatcherJob?.cancel()
        collectorsJob?.cancel()
        bleTransport.disconnect()
        mqttTransport.disconnect()
        simulatedTransport.disconnect()
    }
}
