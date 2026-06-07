package com.ufo.galaxy.transport

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.ufo.galaxy.network.GatewayClient
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE Transport Adapter for Android — AIP v3 over Bluetooth Low Energy.
 *
 * Enables Android devices to communicate with nearby Galaxy peers via BLE
 * when WiFi/MQTT is unavailable (e.g., offline LAN, wearable pairing).
 *
 * Uses BLE GATT:
 * - Service UUID: 0000aip3-0000-1000-8000-00805f9b34fb
 * - RX Characteristic (App → Peer): 0000aip3-0001-... (WRITE)
 * - TX Characteristic (Peer → App): 0000aip3-0002... (NOTIFY)
 *
 * Features:
 * - BLE Central mode (scans and connects to peripherals)
 * - Message fragmentation for >512 byte payloads
 * - Auto-reconnect on connection loss
 *
 * Usage:
 *   val ble = BleGatewayClient(context)
 *   ble.startScan()
 *   // Select device from scan results
 *   ble.connectToDevice(selectedDevice)
 *   ble.sendJson("{\"type\":\"device.ping\",...}")
 */
class BleGatewayClient(
    private val context: Context,
) : GatewayClient {

    companion object {
        private const val TAG = "BleGatewayClient"

        // AIP v3 BLE Service and Characteristic UUIDs
        val AIP_SERVICE_UUID: UUID = UUID.fromString("0000aip3-0000-1000-8000-00805f9b34fb")
        val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000aip3-0001-1000-8000-00805f9b34fb")
        val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000aip3-0002-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 10000L
        private const val MTU_SIZE = 512

        // CRITICAL-5: Reconnect backoff constants
        private const val RECONNECT_BASE_DELAY_MS = 2000L
        private const val RECONNECT_MAX_DELAY_MS = 60000L
        private const val RECONNECT_MAX_ATTEMPTS = 10
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    @Volatile
    private var connected = false

    private val pendingMessages = ConcurrentLinkedQueue<String>()
    private val scanResults = mutableMapOf<String, BluetoothDevice>()

    private var scanCallback: ScanCallback? = null
    private var gattCallback: BluetoothGattCallback? = null

    // CRITICAL-5: Reconnect attempt tracking per device address
    private val reconnectAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ROUND-2-FIX: Saved Runnable references for cancellable postDelayed tasks.
    private var scanTimeoutRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Discovered peers: device_address → device_name */
    val discoveredPeers: Map<String, String>
        get() = scanResults.mapValues { it.value.name ?: "Unknown" }

    // LOW-FIX: Optional message callback for incoming BLE payloads.
    // Set by upstream code to receive JSON messages from the BLE transport.
    var onMessageReceived: ((String) -> Unit)? = null

    /**
     * Start scanning for BLE peripherals advertising AIP v3 service.
     */
    fun startScan(onDeviceFound: ((address: String, name: String) -> Unit)? = null) {
        // CRITICAL-4: Check Android 12+ BLUETOOTH_SCAN permission before scanning
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_SCAN permission not granted — cannot start scan")
                return
            }
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth is disabled")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE scanner unavailable")
            return
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address
                val name = device.name ?: "Unknown"

                if (!scanResults.containsKey(address)) {
                    scanResults[address] = device
                    Log.d(TAG, "BLE device found: $name ($address)")
                    onDeviceFound?.invoke(address, name)
                }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AIP_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scan started")

            // Auto-stop after timeout
            scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
            val timeoutRunnable = Runnable { stopScan() }
            scanTimeoutRunnable = timeoutRunnable
            handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission denied: ${e.message}")
        }
    }

    /**
     * Stop BLE scan.
     */
    fun stopScan() {
        scanTimeoutRunnable?.let { handler.removeCallbacks(it); scanTimeoutRunnable = null }
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            Log.i(TAG, "BLE scan stopped, found ${scanResults.size} devices")
        } catch (e: Exception) {
            Log.w(TAG, "BLE stopScan error: ${e.message}")
        }
    }

    /**
     * Connect to a BLE device.
     */
    fun connectToDevice(address: String): Boolean {
        // CRITICAL-4: Check Android 12+ BLUETOOTH_CONNECT permission before connecting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted — cannot connect")
                return false
            }
        }

        val device = scanResults[address] ?: run {
            Log.w(TAG, "Device $address not in scan results")
            return false
        }

        gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "BLE connected to ${device.address}")
                        synchronized(connectionLock) { connected = true }
                        gatt.requestMtu(MTU_SIZE)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "BLE disconnected from ${device.address}")
                        synchronized(connectionLock) { connected = false }
                        // CRITICAL-5: Exponential backoff with max retry limit
                        val attempts = reconnectAttempts.getOrDefault(address, 0)
                        if (attempts < RECONNECT_MAX_ATTEMPTS) {
                            reconnectAttempts[address] = attempts + 1
                            val delay = (RECONNECT_BASE_DELAY_MS * (1 shl attempts.coerceAtMost(5)))
                                .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                            Log.d(TAG, "BLE reconnect scheduled in ${delay}ms (attempt ${attempts + 1}/$RECONNECT_MAX_ATTEMPTS)")
                            reconnectRunnable?.let { handler.removeCallbacks(it) }
                            val reconnRunnable = Runnable { connectToDevice(address) }
                            reconnectRunnable = reconnRunnable
                            handler.postDelayed(reconnRunnable, delay)
                        } else {
                            Log.e(TAG, "BLE max reconnect attempts ($RECONNECT_MAX_ATTEMPTS) reached for $address — giving up")
                            reconnectAttempts.remove(address)
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "MTU set to $mtu")
                }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed: $status")
                    return
                }

                val service = gatt.getService(AIP_SERVICE_UUID)
                if (service == null) {
                    Log.w(TAG, "AIP v3 service not found on peer")
                    return
                }

                // Enable notifications on TX characteristic
                val txChar = service.getCharacteristic(TX_CHARACTERISTIC_UUID)
                if (txChar != null) {
                    gatt.setCharacteristicNotification(txChar, true)
                    val descriptor = txChar.getDescriptor(CCCD_UUID)
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }

                // Flush pending messages
                flushPendingMessages(gatt)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == TX_CHARACTERISTIC_UUID) {
                    val payload = String(characteristic.value)
                    Log.d(TAG, "BLE received: $payload")
                    // LOW-FIX: Route received BLE message to GatewayClient message callback.
                    // Messages are delivered as-is (JSON string) for upstream parsing.
                    onMessageReceived?.invoke(payload)
                        ?: Log.w(TAG, "No message handler registered — BLE message dropped")
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "BLE write failed: $status")
                }
            }
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE connect permission denied: ${e.message}")
            return false
        }
    }

    // ROUND-3-FIX: Lock object to serialize disconnect() and sendJson() access to shared
    // mutable state (bluetoothGatt, connected flag).
    private val connectionLock = Any()

    /**
     * Disconnect from BLE device.
     */
    fun disconnect() {
        // ROUND-3-FIX: Synchronize state changes so that sendJson() cannot observe
        // a partially-torn-down connection (e.g., connected=false but bluetoothGatt
        // is still non-null, or vice-versa).
        synchronized(connectionLock) {
            connected = false
            reconnectRunnable?.let { handler.removeCallbacks(it); reconnectRunnable = null }
            scanTimeoutRunnable?.let { handler.removeCallbacks(it); scanTimeoutRunnable = null }
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "BLE disconnect error: ${e.message}")
            }
            bluetoothGatt = null
        }
    }

    override fun isConnected(): Boolean = connected

    /**
     * Send AIP v3 message via BLE.
     *
     * Automatically fragments messages larger than MTU.
     */
    // ROUND-3-FIX: Synchronize access to shared mutable state (connected, bluetoothGatt)
    // so that disconnect() cannot tear down the connection while we are in the middle of
    // a write. This prevents crashes from writing to a closed GATT.
    override fun sendJson(json: String): Boolean {
        // Snapshot connection state under the lock to avoid TOCTOU race with disconnect().
        val (isConnectedSnapshot, gattSnapshot) = synchronized(connectionLock) {
            connected to bluetoothGatt
        }
        if (!isConnectedSnapshot || gattSnapshot == null) {
            pendingMessages.offer(json)
            Log.d(TAG, "Message queued (BLE disconnected)")
            return false
        }

        return try {
            val service = gattSnapshot.getService(AIP_SERVICE_UUID)
            val rxChar = service?.getCharacteristic(RX_CHARACTERISTIC_UUID)

            if (rxChar == null) {
                Log.w(TAG, "RX characteristic not found")
                return false
            }

            val bytes = json.toByteArray()
            if (bytes.size <= MTU_SIZE - 3) {
                // Single write
                rxChar.value = bytes
                gattSnapshot.writeCharacteristic(rxChar)
            } else {
                // Fragment
                val chunks = bytes.toList().chunked(MTU_SIZE - 3)
                chunks.forEachIndexed { index, chunk ->
                    val header = if (index == chunks.size - 1) 0x01 else 0x00 // last-chunk flag
                    val packet = byteArrayOf(header.toByte()) + chunk.toByteArray()
                    rxChar.value = packet
                    gattSnapshot.writeCharacteristic(rxChar)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "BLE send failed: ${e.message}")
            false
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun flushPendingMessages(gatt: BluetoothGatt) {
        val service = gatt.getService(AIP_SERVICE_UUID) ?: return
        val rxChar = service.getCharacteristic(RX_CHARACTERISTIC_UUID) ?: return
        while (pendingMessages.isNotEmpty()) {
            val msg = pendingMessages.poll() ?: break
            try {
                rxChar.value = msg.toByteArray()
                gatt.writeCharacteristic(rxChar)
                Log.d(TAG, "Flushed pending BLE message")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to flush pending message: ${e.message}")
            }
        }
    }
}