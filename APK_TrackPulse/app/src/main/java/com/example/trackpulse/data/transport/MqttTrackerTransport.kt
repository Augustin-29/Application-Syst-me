package com.example.trackpulse.data.transport

import com.example.trackpulse.domain.model.ConnectionChannel
import com.example.trackpulse.domain.model.MqttSettings
import com.example.trackpulse.domain.protocol.TrackPulseProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * Transport MQTT v3.1.1 léger pour la liaison distante 4G LTE-M / NB-IoT (SIM7080G).
 * Utilise des topics ultra-courts (`tp/<id>/u`, `/d`, `/a`) et des payloads binaires bruts
 * afin de préserver le forfait data de 500 Mo.
 */
class MqttTrackerTransport(
    private val scope: CoroutineScope,
    private val settingsProvider: () -> MqttSettings
) : TrackerTransport {

    override val channel: ConnectionChannel = ConnectionChannel.LTE_MQTT

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _rssiDbm = MutableStateFlow<Int?>(null)
    override val rssiDbm: StateFlow<Int?> = _rssiDbm.asStateFlow()

    private val _statusMessage = MutableStateFlow("MQTT 4G en veille")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<TrackPulseProtocol.Frame>(extraBufferCapacity = 64)
    override val incomingFrames: SharedFlow<TrackPulseProtocol.Frame> = _incomingFrames.asSharedFlow()

    private val _sessionBytesTransferred = MutableStateFlow(0L)
    val sessionBytesTransferred: StateFlow<Long> = _sessionBytesTransferred.asStateFlow()

    private var client: MqttAsyncClient? = null

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        _state.value = TransportState.CONNECTING
        _statusMessage.value = "Connexion MQTT ${settings.brokerUri}…"

        runCatching {
            disconnectInternal()
            val clientId = "tp-app-${UUID.randomUUID().toString().take(8)}"
            val mqttClient = MqttAsyncClient(settings.brokerUri, clientId, MemoryPersistence())
            client = mqttClient

            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    _state.value = TransportState.CONNECTED
                    _statusMessage.value = "4G/MQTT connecté (${settings.deviceId})"
                    subscribeTopics(mqttClient, settings)
                }

                override fun connectionLost(cause: Throwable?) {
                    _state.value = TransportState.DISCONNECTED
                    _statusMessage.value = "MQTT déconnecté"
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val raw = message?.payload ?: return
                    _sessionBytesTransferred.value += raw.size + (topic?.length ?: 0) + 60
                    val frame = TrackPulseProtocol.decodeFrame(raw) ?: return
                    scope.launch(Dispatchers.Default) {
                        _incomingFrames.emit(frame)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 8
                keepAliveInterval = 60
                if (settings.username.isNotBlank()) {
                    userName = settings.username
                    password = settings.password.toCharArray()
                }
            }

            val deferred = CompletableDeferred<Boolean>()
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    _state.value = TransportState.CONNECTED
                    _statusMessage.value = "4G/MQTT connecté (${settings.deviceId})"
                    subscribeTopics(mqttClient, settings)
                    deferred.complete(true)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    _state.value = TransportState.ERROR
                    _statusMessage.value = "Erreur MQTT : ${exception?.localizedMessage ?: "réseau"}"
                    deferred.complete(false)
                }
            })

            withTimeoutOrNull(8_000L) { deferred.await() } == true
        }.getOrElse { err ->
            _state.value = TransportState.ERROR
            _statusMessage.value = "MQTT indisponible : ${err.localizedMessage ?: "erreur"}"
            false
        }
    }

    private fun subscribeTopics(mqttClient: MqttAsyncClient, settings: MqttSettings) {
        runCatching {
            mqttClient.subscribe(
                arrayOf(settings.uplinkTopic, settings.alertTopic),
                intArrayOf(0, 1)
            )
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
        _state.value = TransportState.DISCONNECTED
        _statusMessage.value = "MQTT 4G en veille (0 o/s)"
    }

    private fun disconnectInternal() {
        runCatching {
            client?.let {
                if (it.isConnected) it.disconnectForcibly(1_000L, 1_000L)
                it.close()
            }
        }
        client = null
    }

    override suspend fun sendFrame(opcode: Byte, payload: ByteArray, requestAck: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val current = client ?: return@withContext false
            if (!current.isConnected) return@withContext false

            val settings = settingsProvider()
            val raw = TrackPulseProtocol.encodeFrame(
                opcode = opcode,
                payload = payload,
                requestAck = requestAck,
                is4gChannel = true
            )
            val msg = MqttMessage(raw).apply {
                qos = 1
                isRetained = false
            }
            runCatching {
                current.publish(settings.downlinkTopic, msg)
                // Estimation réaliste : payload + topic + en-têtes MQTT/TCP/IP (~64 octets)
                _sessionBytesTransferred.value += raw.size + settings.downlinkTopic.length + 64
                true
            }.getOrDefault(false)
        }
}
