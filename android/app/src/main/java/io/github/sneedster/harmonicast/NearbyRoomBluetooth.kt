package io.github.sneedster.harmonicast

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

data class NearbyRoomState(
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val roomCode: String = "",
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val error: String = "",
)

internal object NearbyRoomWire {
    val serviceUuid: UUID = UUID.fromString("d8e8f2a0-8c67-4ef1-9db3-2c77b9a15e01")
    val statusUuid: UUID = UUID.fromString("d8e8f2a0-8c67-4ef1-9db3-2c77b9a15e02")
    const val MANUFACTURER_ID = 0x4843

    fun roomAdvertisement(roomCode: String) = roomCode.take(4).toByteArray(StandardCharsets.US_ASCII)

    fun roomCode(value: ByteArray?) = value?.toString(StandardCharsets.US_ASCII)
        ?.takeIf { it.length == 4 && it.all(Char::isLetter) }
        .orEmpty()

    fun status(roomCode: String, snapshot: PlaybackSnapshot): ByteArray {
        val song = snapshot.nowPlaying.song
        return JSONObject()
            .put("room", roomCode)
            .put("title", song?.title.orEmpty().take(120))
            .put("artist", song?.artist.orEmpty().take(120))
            .put("playing", snapshot.nowPlaying.isPlaying)
            .toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeStatus(value: ByteArray): NearbyRoomState {
        val json = JSONObject(String(value, StandardCharsets.UTF_8))
        return NearbyRoomState(
            connected = true,
            roomCode = json.optString("room"),
            title = json.optString("title"),
            artist = json.optString("artist"),
            isPlaying = json.optBoolean("playing"),
        )
    }
}

/** BLE peripheral owned by the host service. It never changes either phone's network route. */
@SuppressLint("MissingPermission")
class NearbyRoomHost(
    context: Context,
    private val core: HarmonicastCore,
    private val roomCode: String,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            android.util.Log.d("HarmonicastNearby", "BLE room advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            android.util.Log.e("HarmonicastNearby", "BLE advertising failed ($errorCode)")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(connectedDevices) {
                if (newState == BluetoothProfile.STATE_CONNECTED) connectedDevices += device
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) connectedDevices -= device
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
            else android.util.Log.e("HarmonicastNearby", "BLE service registration failed ($status)")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != NearbyRoomWire.statusUuid) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val payload = runCatching {
                NearbyRoomWire.status(roomCode, runBlocking { core.playback.snapshot() })
            }.getOrElse { "{\"room\":\"$roomCode\"}".toByteArray(StandardCharsets.UTF_8) }
            if (offset > payload.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload.copyOfRange(offset, payload.size))
            }
        }
    }

    fun start(): Boolean {
        return try {
            if (!adapter.isEnabled || !adapter.isMultipleAdvertisementSupported) return false
            val status = BluetoothGattCharacteristic(
                NearbyRoomWire.statusUuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            val service = BluetoothGattService(NearbyRoomWire.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                .apply { addCharacteristic(status) }
            server = manager.openGattServer(appContext, serverCallback)?.also { it.addService(service) }
            server != null
        } catch (error: SecurityException) {
            android.util.Log.e("HarmonicastNearby", "Bluetooth permission missing", error)
            false
        }
    }

    private fun startAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val room = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(NearbyRoomWire.serviceUuid))
            .addManufacturerData(NearbyRoomWire.MANUFACTURER_ID, NearbyRoomWire.roomAdvertisement(roomCode))
            .setIncludeDeviceName(false)
            .build()
        advertiser.startAdvertising(settings, room, advertiseCallback)
        advertising = true
    }

    fun stop() {
        runCatching {
            if (advertising) adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            advertising = false
            val clients = synchronized(connectedDevices) {
                connectedDevices.toList().also { connectedDevices.clear() }
            }
            clients.forEach { server?.cancelConnection(it) }
            server?.close()
            server = null
        }
    }
}

/** BLE central used by guest mode for nearby discovery and the first read-only handshake. */
@SuppressLint("MissingPermission")
class NearbyRoomClient(context: Context, private val onState: (NearbyRoomState) -> Unit) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var advertisedRoom = ""

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val hasService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(NearbyRoomWire.serviceUuid)) == true
            val room = NearbyRoomWire.roomCode(
                result.scanRecord?.getManufacturerSpecificData(NearbyRoomWire.MANUFACTURER_ID),
            )
            if (room.isBlank() && !hasService) return
            android.util.Log.d("HarmonicastNearby", "Found Harmonicast BLE advertisement")
            advertisedRoom = room
            stopScan()
            onState(NearbyRoomState(roomCode = room.ifBlank { "ROOM" }))
            gatt = result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onState(NearbyRoomState(error = "Nearby scan failed ($errorCode)"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            android.util.Log.d("HarmonicastNearby", "GATT connection status=$status state=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(517)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onState(NearbyRoomState(roomCode = advertisedRoom, error = "Nearby room disconnected"))
                gatt.close()
                this@NearbyRoomClient.gatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(NearbyRoomWire.serviceUuid)
                ?.getCharacteristic(NearbyRoomWire.statusUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || !gatt.readCharacteristic(characteristic)) {
                onState(NearbyRoomState(roomCode = advertisedRoom, error = "Harmonicast room service is unavailable"))
            }
        }

        @Deprecated("Used on Android 12 and earlier")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid == NearbyRoomWire.statusUuid) acceptStatus(characteristic.value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid == NearbyRoomWire.statusUuid) acceptStatus(value, status)
        }
    }

    fun scan() {
        close()
        if (!adapter.isEnabled) {
            onState(NearbyRoomState(error = "Turn on Bluetooth to find a room"))
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            onState(NearbyRoomState(error = "Bluetooth scanning is unavailable"))
            return
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        onState(NearbyRoomState(scanning = true))
        // Some Android BLE chipsets do not apply a 128-bit UUID filter to scan-response
        // packets consistently. Scan for ten seconds, then validate Harmonicast's
        // manufacturer marker before connecting.
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed({
            if (scanning) {
                stopScan()
                onState(NearbyRoomState(error = "No nearby Harmonicast room found"))
            }
        }, 10_000)
    }

    private fun acceptStatus(value: ByteArray, status: Int) {
        val state = if (status == BluetoothGatt.GATT_SUCCESS) {
            runCatching { NearbyRoomWire.decodeStatus(value) }.getOrElse {
                NearbyRoomState(roomCode = advertisedRoom, error = "Room sent an invalid response")
            }
        } else NearbyRoomState(roomCode = advertisedRoom, error = "Could not read room status")
        handler.post { onState(state) }
    }

    private fun stopScan() {
        if (scanning) runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    fun close() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
