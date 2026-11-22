package com.example.trackpulse.data.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.trackpulse.domain.model.ConnectionChannel
import com.example.trackpulse.domain.protocol.FrameStreamParser
import com.example.trackpulse.domain.protocol.TrackPulseProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Transport BLE GATT natif pour le module Microchip RN4871 (service Transparent UART).
 * Reconstruit le flux binaire via FrameStreamParser et sonde le RSSI pour le Proximity Finder.
 */
class BleTrackerTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceNameFilterProvider: () -> String = { "TrackPulse" }
) : TrackerTransport {

    companion object {
        // UUIDs natifs Microchip RN4871 Transparent UART (cf. PROTOCOL.md §2.1)
        val RN4871_SERVICE_UUID: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        val RN4871_TX_NOTIFY_UUID: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
        val RN4871_RX_WRITE_UUID: UUID = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 6_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val RSSI_POLL_INTERVAL_MS = 450L
    }

    override val channel: ConnectionChannel = ConnectionChannel.BLE

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _rssiDbm = MutableStateFlow<Int?>(null)
    override val rssiDbm: StateFlow<Int?> = _rssiDbm.asStateFlow()

    private val _statusMessage = MutableStateFlow("BLE inactif")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<TrackPulseProtocol.Frame>(extraBufferCapacity = 64)
    override val incomingFrames: SharedFlow<TrackPulseProtocol.Frame> = _incomingFrames.asSharedFlow()

    private val parser = FrameStreamParser()
    private val writeMutex = Mutex()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedPayloadSize: Int = 20
    private var rssiJob: Job? = null
    private var connectDeferred: CompletableDeferred<Boolean>? = null

    fun hasBlePermissions(): Boolean {
        val scanOk = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val connectOk = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        return scanOk && connectOk
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Boolean {
        if (!hasBlePermissions()) {
            _state.value = TransportState.ERROR
            _statusMessage.value = "Permissions BLE requises"
            return false
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = TransportState.DISCONNECTED
            _statusMessage.value = "Bluetooth désactivé"
            return false
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = TransportState.ERROR
            _statusMessage.value = "Scanner BLE indisponible"
            return false
        }

        _state.value = TransportState.SCANNING
        _statusMessage.value = "Recherche RN4871 (${deviceNameFilterProvider()})…"

        val foundDeviceDeferred = CompletableDeferred<BluetoothDevice?>()
        val filterName = deviceNameFilterProvider().lowercase()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val advName = result.scanRecord?.deviceName ?: dev.name ?: ""
                val hasService = result.scanRecord?.serviceUuids?.any {
                    it == ParcelUuid(RN4871_SERVICE_UUID)
                } == true
                if (hasService || advName.lowercase().contains(filterName)) {
                    _rssiDbm.value = result.rssi
                    if (!foundDeviceDeferred.isCompleted) {
                        foundDeviceDeferred.complete(dev)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (!foundDeviceDeferred.isCompleted) {
                    foundDeviceDeferred.complete(null)
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching { scanner.startScan(null, settings, scanCallback) }
        val device = withTimeoutOrNull(SCAN_TIMEOUT_MS) { foundDeviceDeferred.await() }
        runCatching { scanner.stopScan(scanCallback) }

        if (device == null) {
            _state.value = TransportState.DISCONNECTED
            _statusMessage.value = "Aucun boîtier RN4871 à portée"
            return false
        }

        _state.value = TransportState.CONNECTING
        _statusMessage.value = "Connexion GATT à ${device.name ?: device.address}…"
        parser.reset()

        val ready = CompletableDeferred<Boolean>()
        connectDeferred = ready

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() } == true
        if (!connected) {
            disconnect()
            _statusMessage.value = "Échec liaison GATT RN4871"
        }
        return connected
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        rssiJob?.cancel()
        rssiJob = null
        rxCharacteristic = null
        connectDeferred?.complete(false)
        connectDeferred = null
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        parser.reset()
        _rssiDbm.value = null
        _state.value = TransportState.DISCONNECTED
        _statusMessage.value = "BLE déconnecté"
    }

    @SuppressLint("MissingPermission")
    override suspend fun sendFrame(opcode: Byte, payload: ByteArray, requestAck: Boolean): Boolean {
        val currentGatt = gatt ?: return false
        val rxChar = rxCharacteristic ?: return false
        if (_state.value != TransportState.CONNECTED) return false

        val encoded = TrackPulseProtocol.encodeFrame(
            opcode = opcode,
            payload = payload,
            requestAck = requestAck,
            is4gChannel = false
        )

        return writeMutex.withLock {
            var offset = 0
            while (offset < encoded.size) {
                val chunkEnd = (offset + negotiatedPayloadSize).coerceAtMost(encoded.size)
                val chunk = encoded.copyOfRange(offset, chunkEnd)
                val ok = writeChunk(currentGatt, rxChar, chunk)
                if (!ok) return@withLock false
                offset = chunkEnd
                if (offset < encoded.size) delay(12L)
            }
            true
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeChunk(
        bluetoothGatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        val writeType = if (
            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt.writeCharacteristic(char, chunk, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            char.writeType = writeType
            char.value = chunk
            bluetoothGatt.writeCharacteristic(char)
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _statusMessage.value = "Négociation MTU & services GATT…"
                gatt.requestMtu(247)
            } else {
                rssiJob?.cancel()
                rxCharacteristic = null
                _state.value = TransportState.DISCONNECTED
                _rssiDbm.value = null
                _statusMessage.value = "Lien BLE perdu (code $status)"
                connectDeferred?.complete(false)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedPayloadSize = if (status == BluetoothGatt.GATT_SUCCESS) {
                (mtu - 3).coerceIn(20, 244)
            } else {
                20
            }
            gatt.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectDeferred?.complete(false)
                return
            }
            val service = gatt.getService(RN4871_SERVICE_UUID)
            val txChar = service?.getCharacteristic(RN4871_TX_NOTIFY_UUID)
            val rxChar = service?.getCharacteristic(RN4871_RX_WRITE_UUID)
            if (txChar == null || rxChar == null) {
                _statusMessage.value = "Service RN4871 Transparent UART introuvable"
                connectDeferred?.complete(false)
                return
            }

            rxCharacteristic = rxChar
            gatt.setCharacteristicNotification(txChar, true)
            val cccd = txChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }

            _state.value = TransportState.CONNECTED
            _statusMessage.value = "BLE connecté (MTU ${negotiatedPayloadSize + 3})"
            startRssiPolling(gatt)
            connectDeferred?.complete(true)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { handleIncomingBytes(it) }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssiDbm.value = rssi
            }
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val frames = parser.feed(bytes)
        if (frames.isNotEmpty()) {
            scope.launch(Dispatchers.Default) {
                frames.forEach { _incomingFrames.emit(it) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRssiPolling(bluetoothGatt: BluetoothGatt) {
        rssiJob?.cancel()
        rssiJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == TransportState.CONNECTED) {
                runCatching { bluetoothGatt.readRemoteRssi() }
                delay(RSSI_POLL_INTERVAL_MS)
            }
        }
    }
}
