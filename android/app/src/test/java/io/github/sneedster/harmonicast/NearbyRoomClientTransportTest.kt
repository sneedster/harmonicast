package io.github.sneedster.harmonicast

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothGatt
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [NearbyRoomClientTransportTest.ControlledGatt::class])
@LooperMode(LooperMode.Mode.PAUSED)
class NearbyRoomClientTransportTest {
    /** Keep radio reads asynchronous, as on a device; callbacks are driven explicitly. */
    @Implements(BluetoothGatt::class)
    class ControlledGatt : ShadowBluetoothGatt() {
        val reads = mutableListOf<BluetoothGattCharacteristic>()

        @Implementation
        fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
            reads += characteristic
            return true
        }
    }

    @Test fun rejectedGuestCommandResumesHeartbeatWithoutReconnecting() {
        withConnectedClient { fixture ->
            // The initial status triggers the real client's queue command.
            val command = JSONObject(String(fixture.radio.latestWrittenBytes))
            assertEquals("queue", command.getString("action"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(NearbyRoomWire.responseUuid, fixture.radio.reads.removeAt(0).uuid)
            fixture.reply(fixture.response, JSONObject().put("id", command.getInt("id"))
                .put("ok", false).put("error", "Guest operation failed").toString().toByteArray())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(fixture.states.last().connected)
            assertEquals("Guest operation failed", fixture.states.last().error)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

            assertEquals("A rejected operation must not permanently stop room heartbeats",
                listOf(NearbyRoomWire.statusUuid), fixture.radio.reads.map { it.uuid })
            fixture.radio.reads.clear()
            fixture.reply(fixture.status, NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying())))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(listOf(NearbyRoomWire.statusUuid), fixture.radio.reads.map { it.uuid })
            assertTrue(fixture.states.last().connected)
            assertFalse(fixture.radio.isClosed)
            assertEquals(1, shadowOf(fixture.gatt.device).bluetoothGatts.size)
        }
    }

    @Test fun healthyRoomKeepsPollingTheSameSession() {
        withConnectedClient { fixture ->
            fixture.finishQueue()
            repeat(4) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals(listOf(NearbyRoomWire.statusUuid), fixture.radio.reads.map { it.uuid })
                fixture.radio.reads.clear()
                fixture.reply(fixture.status, NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying())))
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(fixture.states.last().connected)
            assertFalse(fixture.radio.isClosed)
            assertEquals(1, shadowOf(fixture.gatt.device).bluetoothGatts.size)
        }
    }

    @Test fun failedStatusDisconnectsOnceAndDoesNotAutomaticallyReconnect() {
        withConnectedClient { fixture ->
            fixture.finishQueue()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            fixture.radio.reads.clear()
            fixture.reply(fixture.status, ByteArray(0), BluetoothGatt.GATT_FAILURE)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            assertFalse(fixture.states.last().connected)
            assertEquals("Nearby room ended", fixture.states.last().error)
            assertTrue(fixture.radio.isClosed)
            assertTrue(fixture.radio.reads.isEmpty())
            assertEquals(1, shadowOf(fixture.gatt.device).bluetoothGatts.size)
        }
    }

    private class Fixture(
        val gatt: BluetoothGatt,
        val radio: ControlledGatt,
        val status: BluetoothGattCharacteristic,
        val response: BluetoothGattCharacteristic,
        val states: MutableList<NearbyRoomState>,
    ) {
        @Suppress("DEPRECATION")
        fun reply(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, status: Int = BluetoothGatt.GATT_SUCCESS) {
            characteristic.value = bytes
            radio.gattCallback.onCharacteristicRead(gatt, characteristic, status)
        }

        fun finishQueue() {
            val command = JSONObject(String(radio.latestWrittenBytes))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(NearbyRoomWire.responseUuid, radio.reads.removeAt(0).uuid)
            reply(response, JSONObject().put("id", command.getInt("id"))
                .put("ok", true).put("items", org.json.JSONArray()).toString().toByteArray())
        }
    }

    private fun withConnectedClient(block: (Fixture) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        val device = context.getSystemService(BluetoothManager::class.java).adapter
            .getRemoteDevice("00:11:22:33:44:55")
        val states = mutableListOf<NearbyRoomState>()
        val client = NearbyRoomClient(context, states::add)
        @Suppress("UNCHECKED_CAST")
        val discovery = NearbyRoomClient::class.java.getDeclaredField("discovery").run {
            isAccessible = true
            get(client) as NearbyRoomDiscovery<android.bluetooth.BluetoothDevice>
        }
        discovery.add("MUSE", device)
        try {
            client.connect("MUSE")
            val gatt = shadowOf(device).bluetoothGatts.single()
            val radio = Shadow.extract<ControlledGatt>(gatt)
            val service = BluetoothGattService(NearbyRoomWire.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            fun characteristic(uuid: java.util.UUID, properties: Int, permission: Int) =
                BluetoothGattCharacteristic(uuid, properties, permission).also(service::addCharacteristic)
            val status = characteristic(NearbyRoomWire.statusUuid,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
            characteristic(NearbyRoomWire.commandUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
            val response = characteristic(NearbyRoomWire.responseUuid,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
            radio.addDiscoverableService(service)
            assertTrue(gatt.discoverServices())
            assertEquals(listOf(NearbyRoomWire.statusUuid), radio.reads.map { it.uuid })
            radio.reads.clear()
            val fixture = Fixture(gatt, radio, status, response, states)
            fixture.reply(status, NearbyRoomWire.status("MUSE", PlaybackSnapshot(NowPlaying())))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(states.last().connected)
            block(fixture)
        } finally {
            client.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
