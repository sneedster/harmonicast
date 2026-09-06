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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request

data class NearbyRoomState(
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val roomCode: String = "",
    val artworkKey: String = "",
    val artwork: ByteArray? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Int = 0,
    val positionSeconds: Int = 0,
    val isPlaying: Boolean = false,
    val queue: List<Song> = emptyList(),
    val searchResults: List<Song> = emptyList(),
    val queueOffset: Int = 0,
    val searchOffset: Int = 0,
    val queueHasMore: Boolean = false,
    val searchHasMore: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val error: String = "",
    val vote: Int = 0,
)

internal object NearbyRoomWire {
    val serviceUuid: UUID = UUID.fromString("d8e8f2a0-8c67-4ef1-9db3-2c77b9a15e01")
    val statusUuid: UUID = UUID.fromString("d8e8f2a0-8c67-4ef1-9db3-2c77b9a15e02")
    val commandUuid: UUID = UUID.fromString("d8e8f2a0-8c67-4ef1-9db3-2c77b9a15e03")
    val responseUuid: UUID = UUID.fromString("d8e8f2a0-8c67-4ef1-9db3-2c77b9a15e04")
    const val MANUFACTURER_ID = 0x4843
    const val PAGE_SIZE = 2
    private const val ARTWORK_CHUNK_SIZE = 240

    fun roomAdvertisement(roomCode: String) = roomCode.take(4).toByteArray(StandardCharsets.US_ASCII)

    fun roomCode(value: ByteArray?) = value?.toString(StandardCharsets.US_ASCII)
        ?.takeIf { it.length == 4 && it.all(Char::isLetter) }
        .orEmpty()

    fun status(roomCode: String, snapshot: PlaybackSnapshot): ByteArray {
        val song = snapshot.nowPlaying.song
        return JSONObject()
            .put("room", roomCode)
            .put("art", artworkKey(song))
            .put("title", utf8Prefix(song?.title.orEmpty(), 96))
            .put("artist", utf8Prefix(song?.artist.orEmpty(), 72))
            .put("album", utf8Prefix(song?.album.orEmpty(), 64))
            .put("duration", song?.duration ?: 0)
            .put("position", snapshot.positionSeconds.toInt().coerceAtLeast(0))
            .put("playing", snapshot.nowPlaying.isPlaying)
            .toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeStatus(value: ByteArray): NearbyRoomState {
        val json = JSONObject(String(value, StandardCharsets.UTF_8))
        return NearbyRoomState(
            connected = true,
            roomCode = json.optString("room"),
            artworkKey = json.optString("art"),
            title = json.optString("title"),
            artist = json.optString("artist"),
            album = json.optString("album"),
            durationSeconds = json.optInt("duration").coerceAtLeast(0),
            positionSeconds = json.optInt("position").coerceAtLeast(0),
            isPlaying = json.optBoolean("playing"),
        )
    }

    fun command(id: Int, action: String, offset: Int = 0, value: String = "") = JSONObject()
        .put("id", id)
        .put("action", action)
        .put("offset", offset.coerceAtLeast(0))
        .put("value", value.take(160))
        .toString().toByteArray(StandardCharsets.UTF_8)

    fun artworkKey(song: Song?): String {
        if (song == null || song.artworkUri.isNullOrBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(song.id.toByteArray(StandardCharsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    fun artworkResponse(id: Int, key: String, artwork: ByteArray, offset: Int): ByteArray {
        val start = offset.coerceIn(0, artwork.size)
        val end = minOf(start + ARTWORK_CHUNK_SIZE, artwork.size)
        return JSONObject()
            .put("id", id)
            .put("action", "art")
            .put("ok", true)
            .put("key", key)
            .put("offset", start)
            .put("next", end)
            .put("more", end < artwork.size)
            .put("data", Base64.getEncoder().encodeToString(artwork.copyOfRange(start, end)))
            .toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeSongs(items: JSONArray): List<Song> = List(items.length()) { index ->
        val item = items.getJSONObject(index)
        Song(
            id = item.optString("id"),
            title = item.optString("title"),
            artist = item.optString("artist"),
            album = item.optString("album"),
            duration = item.optInt("duration").coerceAtLeast(0),
        )
    }

    private fun utf8Prefix(value: String, maxBytes: Int): String {
        var end = value.length
        while (end > 0 && value.substring(0, end).toByteArray(StandardCharsets.UTF_8).size > maxBytes) end--
        return value.substring(0, end)
    }

    fun pagedResponse(id: Int, action: String, body: String, offset: Int): ByteArray {
        val all = JSONArray(body)
        val start = offset.coerceIn(0, all.length())
        val end = minOf(start + PAGE_SIZE, all.length())
        val items = JSONArray()
        for (index in start until end) {
            val song = all.getJSONObject(index)
            items.put(JSONObject()
                .put("id", utf8Prefix(song.optString("id"), 64))
                .put("title", utf8Prefix(song.optString("title"), 32))
                .put("artist", utf8Prefix(song.optString("artist"), 24))
                .put("album", utf8Prefix(song.optString("album"), 24))
                .put("duration", song.optInt("duration").coerceAtLeast(0)))
        }
        return JSONObject()
            .put("id", id).put("action", action).put("ok", true)
            .put("offset", start).put("more", end < all.length()).put("items", items)
            .toString().toByteArray(StandardCharsets.UTF_8)
    }
}

/** BLE peripheral owned by the host service. It never changes either phone's network route. */
@SuppressLint("MissingPermission")
class NearbyRoomHost(
    context: Context,
    private val core: HarmonicastCore,
    private val roomCode: String,
    private val routeGuest: suspend (GuestApiRequest) -> GuestApiResponse,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val responses = ConcurrentHashMap<String, ByteArray>()
    private val participants = ConcurrentHashMap<String, String>()
    private val participantSequence = AtomicInteger(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val artworkHttp = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val artworkLock = Any()
    private var cachedArtworkKey = ""
    private var cachedArtwork = ByteArray(0)

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
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices += device
                    participantFor(device)
                }
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
            if (characteristic.uuid !in setOf(NearbyRoomWire.statusUuid, NearbyRoomWire.responseUuid)) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val payload = if (characteristic.uuid == NearbyRoomWire.statusUuid) {
                runCatching { NearbyRoomWire.status(roomCode, runBlocking { core.playback.snapshot() }) }
                    .getOrElse { "{\"room\":\"$roomCode\"}".toByteArray(StandardCharsets.UTF_8) }
            } else responses[device.address]
                ?: "{\"pending\":true}".toByteArray(StandardCharsets.UTF_8)
            if (offset > payload.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload.copyOfRange(offset, payload.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid != NearbyRoomWire.commandUuid || preparedWrite || offset != 0) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val command = runCatching { JSONObject(String(value, StandardCharsets.UTF_8)) }.getOrNull() ?: return
            val id = command.optInt("id")
            responses[device.address] = JSONObject().put("id", id).put("pending", true)
                .toString().toByteArray(StandardCharsets.UTF_8)
            scope.launch {
                responses[device.address] = handleCommand(command, participantFor(device))
            }
        }
    }

    private fun participantFor(device: BluetoothDevice) = participants.computeIfAbsent(device.address) {
        "Nearby guest ${participantSequence.getAndIncrement()}"
    }

    private suspend fun handleCommand(command: JSONObject, participantId: String): ByteArray {
        val id = command.optInt("id")
        val action = command.optString("action")
        val offset = command.optInt("offset", 0).coerceAtLeast(0)
        val value = command.optString("value")
        if (action == "art") {
            val artwork = loadArtwork(value)
            return NearbyRoomWire.artworkResponse(id, value, artwork, offset)
        }
        val request = when (action) {
            "queue" -> GuestApiRequest("GET", "/v1/queue", null, participantId = participantId)
            "search" -> GuestApiRequest("GET", "/v1/search", null, mapOf("q" to value), participantId = participantId)
            "request" -> GuestApiRequest("POST", "/v1/requests", null, body = JSONObject().put("songId", value).toString(), participantId = participantId)
            "vote" -> GuestApiRequest("POST", "/v1/votes", null, body = JSONObject().put("direction", value).toString(), participantId = participantId)
            else -> return errorResponse(id, action, "Guest operation is unavailable")
        }
        val response = routeGuest(request)
        if (response.status !in 200..299) {
            val message = runCatching { JSONObject(response.body).optString("error") }.getOrDefault("Guest operation failed")
            return errorResponse(id, action, message)
        }
        return if (action in setOf("queue", "search")) {
            NearbyRoomWire.pagedResponse(id, action, response.body, offset)
        } else JSONObject().put("id", id).put("action", action).put("ok", true)
            .toString().toByteArray(StandardCharsets.UTF_8)
    }

    private suspend fun loadArtwork(requestedKey: String): ByteArray {
        if (requestedKey.isBlank()) return ByteArray(0)
        synchronized(artworkLock) {
            if (cachedArtworkKey == requestedKey) return cachedArtwork
        }
        val song = core.playback.snapshot().nowPlaying.song ?: return ByteArray(0)
        if (NearbyRoomWire.artworkKey(song) != requestedKey) return ByteArray(0)
        val url = core.library.artworkUrl(song) ?: return ByteArray(0)
        val encoded = runCatching {
            artworkHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use ByteArray(0)
                val source = response.body?.bytes() ?: return@use ByteArray(0)
                val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return@use ByteArray(0)
                val scaled = Bitmap.createScaledBitmap(bitmap, 112, 112, true)
                ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 58, output)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                    output.toByteArray()
                }
            }
        }.getOrDefault(ByteArray(0))
        synchronized(artworkLock) {
            cachedArtworkKey = requestedKey
            cachedArtwork = encoded
        }
        return encoded
    }

    private fun errorResponse(id: Int, action: String, message: String) = JSONObject()
        .put("id", id).put("action", action).put("ok", false).put("error", message.take(120))
        .toString().toByteArray(StandardCharsets.UTF_8)

    fun start(): Boolean {
        return try {
            if (!adapter.isEnabled || !adapter.isMultipleAdvertisementSupported) return false
            val status = BluetoothGattCharacteristic(
                NearbyRoomWire.statusUuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            val command = BluetoothGattCharacteristic(
                NearbyRoomWire.commandUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            val response = BluetoothGattCharacteristic(
                NearbyRoomWire.responseUuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            val service = BluetoothGattService(NearbyRoomWire.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                .apply {
                    addCharacteristic(status)
                    addCharacteristic(command)
                    addCharacteristic(response)
                }
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
            responses.clear()
            participants.clear()
            synchronized(artworkLock) {
                cachedArtworkKey = ""
                cachedArtwork = ByteArray(0)
            }
            scope.cancel()
        }
    }
}

/** BLE central used by guest mode for nearby discovery and the first read-only handshake. */
@SuppressLint("MissingPermission")
class NearbyRoomClient(context: Context, private val onState: (NearbyRoomState) -> Unit) {
    private companion object {
        const val STATUS_REFRESH_MS = 2_000L
        const val SCAN_TIMEOUT_MS = 10_000L
        const val RESPONSE_POLL_MS = 150L
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var advertisedRoom = ""
    private var roomState = NearbyRoomState()
    private var nextCommandId = 1
    private var artworkBuffer: ByteArrayOutputStream? = null
    private var loadQueueAfterArtwork = false
    private data class PendingCommand(val id: Int, val action: String, val offset: Int, val value: String)
    private var pendingCommand: PendingCommand? = null

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            publish(NearbyRoomState(error = "No nearby Harmonicast room found"))
        }
    }

    private val refreshStatus = object : Runnable {
        override fun run() {
            if (pendingCommand != null) {
                handler.postDelayed(this, STATUS_REFRESH_MS)
                return
            }
            val activeGatt = gatt ?: return
            val characteristic = statusCharacteristic ?: return
            if (!activeGatt.readCharacteristic(characteristic)) {
                failRoom(activeGatt, "Nearby room ended")
            }
        }
    }

    private val pollResponse = object : Runnable {
        override fun run() {
            val activeGatt = gatt ?: return
            val characteristic = responseCharacteristic ?: return
            if (!activeGatt.readCharacteristic(characteristic)) {
                failCommand("Could not read the host response")
            }
        }
    }

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
            publish(NearbyRoomState(roomCode = room.ifBlank { "ROOM" }))
            gatt = result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            publish(NearbyRoomState(error = "Nearby scan failed ($errorCode)"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            android.util.Log.d("HarmonicastNearby", "GATT connection status=$status state=$newState")
            if (this@NearbyRoomClient.gatt !== gatt) {
                gatt.close()
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(517)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                failRoom(gatt, "Nearby room ended")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(NearbyRoomWire.serviceUuid)
            val characteristic = service?.getCharacteristic(NearbyRoomWire.statusUuid)
            val command = service?.getCharacteristic(NearbyRoomWire.commandUuid)
            val response = service?.getCharacteristic(NearbyRoomWire.responseUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || command == null || response == null) {
                failRoom(gatt, "Harmonicast room service is unavailable")
            } else {
                statusCharacteristic = characteristic
                commandCharacteristic = command
                responseCharacteristic = response
                if (!gatt.readCharacteristic(characteristic)) {
                    failRoom(gatt, "Harmonicast room service is unavailable")
                }
            }
        }

        @Deprecated("Used on Android 12 and earlier")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            when (characteristic.uuid) {
                NearbyRoomWire.statusUuid -> acceptStatus(gatt, characteristic.value, status)
                NearbyRoomWire.responseUuid -> acceptResponse(gatt, characteristic.value, status)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            when (characteristic.uuid) {
                NearbyRoomWire.statusUuid -> acceptStatus(gatt, value, status)
                NearbyRoomWire.responseUuid -> acceptResponse(gatt, value, status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != NearbyRoomWire.commandUuid || pendingCommand == null) return
            if (status == BluetoothGatt.GATT_SUCCESS) handler.postDelayed(pollResponse, RESPONSE_POLL_MS)
            else failCommand("Could not send the guest request")
        }
    }

    fun scan() {
        close()
        if (!adapter.isEnabled) {
            publish(NearbyRoomState(error = "Turn on Bluetooth to find a room"))
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            publish(NearbyRoomState(error = "Bluetooth scanning is unavailable"))
            return
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        publish(NearbyRoomState(scanning = true))
        // Some Android BLE chipsets do not apply a 128-bit UUID filter to scan-response
        // packets consistently. Scan for ten seconds, then validate Harmonicast's
        // manufacturer marker before connecting.
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private fun acceptStatus(sourceGatt: BluetoothGatt, value: ByteArray, status: Int) {
        if (gatt !== sourceGatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failRoom(sourceGatt, "Nearby room ended")
            return
        }
        val state = runCatching { NearbyRoomWire.decodeStatus(value) }.getOrElse {
            failRoom(sourceGatt, "Room sent an invalid response")
            return
        }
        val firstStatus = !roomState.connected
        val songChanged = roomState.title != state.title || roomState.artist != state.artist ||
            roomState.artworkKey != state.artworkKey
        publish(roomState.copy(
            connected = true,
            roomCode = state.roomCode,
            artworkKey = state.artworkKey,
            artwork = if (songChanged) null else roomState.artwork,
            title = state.title,
            artist = state.artist,
            album = state.album,
            durationSeconds = state.durationSeconds,
            positionSeconds = state.positionSeconds,
            isPlaying = state.isPlaying,
            vote = if (songChanged) 0 else roomState.vote,
            error = "",
        ))
        handler.removeCallbacks(refreshStatus)
        handler.postDelayed(refreshStatus, STATUS_REFRESH_MS)
        if (firstStatus) loadQueueAfterArtwork = true
        if ((firstStatus || songChanged) && state.artworkKey.isNotBlank()) loadArtwork(state.artworkKey)
        else if (firstStatus) {
            loadQueueAfterArtwork = false
            loadQueue()
        }
    }

    private fun publish(state: NearbyRoomState) {
        roomState = state
        handler.post { onState(state) }
    }

    private fun acceptResponse(sourceGatt: BluetoothGatt, value: ByteArray, status: Int) {
        if (gatt !== sourceGatt || pendingCommand == null) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failCommand("Could not read the host response")
            return
        }
        val json = runCatching { JSONObject(String(value, StandardCharsets.UTF_8)) }.getOrElse {
            failCommand("Host returned an invalid response")
            return
        }
        if (json.optBoolean("pending")) {
            handler.postDelayed(pollResponse, RESPONSE_POLL_MS)
            return
        }
        val pending = pendingCommand ?: return
        if (json.optInt("id") != pending.id) {
            handler.postDelayed(pollResponse, RESPONSE_POLL_MS)
            return
        }
        pendingCommand = null
        if (!json.optBoolean("ok")) {
            publish(roomState.copy(busy = false, error = json.optString("error", "Guest operation failed")))
            return
        }
        when (pending.action) {
            "queue" -> publish(roomState.copy(
                queue = NearbyRoomWire.decodeSongs(json.optJSONArray("items") ?: JSONArray()),
                queueOffset = json.optInt("offset"),
                queueHasMore = json.optBoolean("more"),
                busy = false,
                error = "",
            ))
            "search" -> publish(roomState.copy(
                searchResults = NearbyRoomWire.decodeSongs(json.optJSONArray("items") ?: JSONArray()),
                searchOffset = json.optInt("offset"),
                searchHasMore = json.optBoolean("more"),
                busy = false,
                error = "",
            ))
            "request" -> {
                publish(roomState.copy(busy = false, message = "Request added to the queue", error = ""))
                loadQueue()
            }
            "vote" -> publish(roomState.copy(
                busy = false,
                message = if (pending.value == "up") "You voted this track up" else "You voted this track down",
                error = "",
                vote = if (pending.value == "up") 1 else -1,
            ))
            "art" -> {
                val key = json.optString("key")
                val chunk = runCatching { Base64.getDecoder().decode(json.optString("data")) }.getOrDefault(ByteArray(0))
                if (pending.offset == 0 || artworkBuffer == null) artworkBuffer = ByteArrayOutputStream()
                artworkBuffer?.write(chunk)
                if (json.optBoolean("more")) {
                    sendCommand("art", json.optInt("next"), key)
                    return
                }
                val artwork = artworkBuffer?.toByteArray()?.takeIf { it.isNotEmpty() }
                artworkBuffer = null
                publish(roomState.copy(
                    artworkKey = key,
                    artwork = artwork,
                    busy = false,
                    error = "",
                ))
                if (loadQueueAfterArtwork) {
                    loadQueueAfterArtwork = false
                    loadQueue()
                }
            }
        }
        if (pendingCommand == null) handler.postDelayed(refreshStatus, STATUS_REFRESH_MS)
    }

    @Suppress("DEPRECATION")
    private fun sendCommand(action: String, offset: Int = 0, value: String = "") {
        val activeGatt = gatt ?: return
        val characteristic = commandCharacteristic ?: return
        if (pendingCommand != null) return
        val pending = PendingCommand(nextCommandId++, action, offset.coerceAtLeast(0), value)
        pendingCommand = pending
        handler.removeCallbacks(refreshStatus)
        publish(roomState.copy(busy = true, message = "", error = ""))
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = NearbyRoomWire.command(pending.id, action, pending.offset, value)
        if (!activeGatt.writeCharacteristic(characteristic)) failCommand("Could not send the guest request")
    }

    private fun failCommand(message: String) {
        handler.removeCallbacks(pollResponse)
        val artworkFailed = pendingCommand?.action == "art"
        pendingCommand = null
        artworkBuffer = null
        publish(roomState.copy(busy = false, error = if (artworkFailed) roomState.error else message))
        if (artworkFailed && loadQueueAfterArtwork) {
            loadQueueAfterArtwork = false
            loadQueue()
            return
        }
        handler.postDelayed(refreshStatus, STATUS_REFRESH_MS)
    }

    fun loadQueue(offset: Int = 0) = sendCommand("queue", offset)
    private fun loadArtwork(key: String) = sendCommand("art", value = key)
    fun search(query: String, offset: Int = 0) {
        if (query.isBlank()) {
            publish(roomState.copy(error = "Enter a song or artist to search"))
            return
        }
        sendCommand("search", offset, query.trim())
    }
    fun request(song: Song) = sendCommand("request", value = song.id)
    fun vote(up: Boolean) = sendCommand("vote", value = if (up) "up" else "down")

    private fun failRoom(sourceGatt: BluetoothGatt, message: String) {
        if (gatt !== sourceGatt) return
        handler.removeCallbacks(refreshStatus)
        handler.removeCallbacks(pollResponse)
        statusCharacteristic = null
        commandCharacteristic = null
        responseCharacteristic = null
        pendingCommand = null
        artworkBuffer = null
        loadQueueAfterArtwork = false
        gatt = null
        runCatching { sourceGatt.disconnect() }
        sourceGatt.close()
        publish(NearbyRoomState(roomCode = advertisedRoom, error = message))
    }

    private fun stopScan() {
        if (scanning) runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        handler.removeCallbacks(scanTimeout)
    }

    fun close() {
        stopScan()
        handler.removeCallbacks(refreshStatus)
        handler.removeCallbacks(pollResponse)
        statusCharacteristic = null
        commandCharacteristic = null
        responseCharacteristic = null
        pendingCommand = null
        artworkBuffer = null
        loadQueueAfterArtwork = false
        val activeGatt = gatt
        gatt = null
        activeGatt?.disconnect()
        activeGatt?.close()
        roomState = NearbyRoomState()
    }
}
