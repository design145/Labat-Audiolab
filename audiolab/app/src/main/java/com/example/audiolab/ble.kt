import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class BleManager(private val context: Context) {
    companion object {
        const val ESP32_MAC_ADDRESS = "08:B6:1F:29:33:2E"
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var bleCharacteristic: BluetoothGattCharacteristic? = null
    private var onMessageReceived: ((String) -> Unit)? = null
    private var device: BluetoothDevice? = null
    private var isReconnecting = false
    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandQueue = ConcurrentLinkedQueue<String>()
    private var isProcessingCommands = false

    val isConnected = mutableStateOf(false)
    val connectionState = mutableStateOf("Disconnected")
    val keyPressFlow = MutableStateFlow("")

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && !isConnected.value) {
                Log.d("BLE", "Attempting to reconnect: Attempt ${reconnectAttempts + 1}")
                device?.let {
                    connectInternal(it)
                    reconnectAttempts++
                    mainHandler.postDelayed(this, RECONNECT_DELAY_MS)
                }
            } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.e("BLE", "Failed to reconnect after $MAX_RECONNECT_ATTEMPTS attempts")
                isReconnecting = false
                connectionState.value = "Failed to reconnect"
            }
        }
    }

    fun setOnMessageReceivedListener(listener: (String) -> Unit) {
        onMessageReceived = listener
    }

    fun connect(device: BluetoothDevice, onConnected: () -> Unit) {
        this.device = device
        isReconnecting = false
        reconnectAttempts = 0
        connectionState.value = "Connecting..."
        connectInternal(device, onConnected)
    }

    private fun connectInternal(device: BluetoothDevice, onConnected: (() -> Unit)? = null) {
        Log.d("BLE", "Attempting to connect to ${device.address}")

        // Disconnect previous connection if exists
        disconnect(false)

        bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d("BLE", "Connection state changed: status=$status, newState=$newState")

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d("BLE", "Connected to GATT server")
                            gatt.discoverServices()
                            mainHandler.post {
                                isConnected.value = true
                                connectionState.value = "Connected"
                                isReconnecting = false
                                reconnectAttempts = 0
                                onConnected?.invoke()
                            }
                        } else {
                            Log.e("BLE", "Error connecting with status: $status")
                            gatt.close()
                            mainHandler.post {
                                isConnected.value = false
                                connectionState.value = "Connection Error: $status"
                                scheduleReconnect()
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d("BLE", "Disconnected from GATT server")
                        gatt.close()
                        mainHandler.post {
                            isConnected.value = false
                            connectionState.value = "Disconnected"
                            // Schedule reconnect if not already reconnecting
                            if (!isReconnecting) {
                                scheduleReconnect()
                            }
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SERVICE_UUID)
                    bleCharacteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

                    bleCharacteristic?.let { characteristic ->
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CCCD_UUID)
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            val writeSuccess = gatt.writeDescriptor(it)
                            Log.d("BLE", "Writing descriptor: $writeSuccess")
                        } ?: Log.e("BLE", "CCCD descriptor not found")
                    } ?: Log.e("BLE", "Required characteristic not found")

                    Log.d("BLE", "Service and characteristic discovery complete")
                    mainHandler.post {
                        connectionState.value = "Connected to ESP32"
                        processCommandQueue()
                    }
                } else {
                    Log.e("BLE", "Service discovery failed with status: $status")
                    mainHandler.post {
                        connectionState.value = "Service Discovery Failed"
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val message = characteristic.getStringValue(0)
                Log.d("BLE", "Notification received: $message")

                // Notify raw message listener
                mainHandler.post {
                    onMessageReceived?.invoke(message)
                }

                // Process message and update keyPressFlow
                when {
                    message.contains("Key Press") -> {
                        val keyNumber = message.substringAfter("Key Press ").toIntOrNull()
                        keyNumber?.let {
                            mainHandler.post {
                                keyPressFlow.value = "KEY_PRESSED_$it"
                            }
                        }
                    }
                    message.contains("Key Release") -> {
                        val keyNumber = message.substringAfter("Key Release ").toIntOrNull()
                        keyNumber?.let {
                            mainHandler.post {
                                keyPressFlow.value = "KEY_RELEASED_$it"
                            }
                        }
                    }
                    else -> {
                        // Handle other types of messages
                        mainHandler.post {
                            keyPressFlow.value = message
                        }
                    }
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                val success = status == BluetoothGatt.GATT_SUCCESS
                Log.d("BLE", "Characteristic write ${if (success) "succeeded" else "failed with status: $status"}")

                mainHandler.post {
                    isProcessingCommands = false
                    processCommandQueue()
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("BLE", "Notifications enabled successfully")
                } else {
                    Log.e("BLE", "Failed to enable notifications with status: $status")
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isReconnecting && device != null) {
            isReconnecting = true
            reconnectAttempts = 0
            connectionState.value = "Attempting to reconnect..."
            mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
        }
    }

    fun writeCommand(command: String) {
        commandQueue.add(command)
        if (!isProcessingCommands) {
            processCommandQueue()
        }
    }

    private fun processCommandQueue() {
        if (commandQueue.isEmpty() || isProcessingCommands || !isConnected.value || bleCharacteristic == null || bluetoothGatt == null) {
            return
        }

        isProcessingCommands = true
        val command = commandQueue.poll() ?: return

        try {
            bleCharacteristic?.apply {
                value = command.toByteArray()
                val writeSuccess = bluetoothGatt?.writeCharacteristic(this)
                Log.d("BLE", "Command sent: $command, success: $writeSuccess")

                if (writeSuccess != true) {
                    Log.e("BLE", "Failed to write characteristic")
                    isProcessingCommands = false
                    processCommandQueue()
                }
            } ?: run {
                Log.e("BLE", "BLE Characteristic not initialized")
                isProcessingCommands = false
                processCommandQueue()
            }
        } catch (e: Exception) {
            Log.e("BLE", "Error writing command: ${e.message}")
            isProcessingCommands = false
            processCommandQueue()
        }
    }

    fun disconnect(notifyStateChange: Boolean = true) {
        mainHandler.removeCallbacks(reconnectRunnable)
        isReconnecting = false

        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                Log.e("BLE", "Error during disconnect: ${e.message}")
            }
        }

        bluetoothGatt = null
        bleCharacteristic = null
        commandQueue.clear()
        isProcessingCommands = false

        if (notifyStateChange) {
            isConnected.value = false
            connectionState.value = "Disconnected"
        }
    }

    // Call this method when the app is being closed to ensure clean disconnection
    fun cleanUp() {
        disconnect()
        device = null
    }
}