package com.example.audiolab
import BleManager
import ads_mobile_sdk.h5
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import android.media.MediaPlayer
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch





@Composable
fun LEDCircle(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(15.dp)
            .background(color, CircleShape)
            .border(1.dp, Color.Black, CircleShape)
    )
}

// Create a shared ViewModel object to hold the LED states
object AppState {
    val ledStatus = mutableStateListOf<Color>().apply { repeat(39) { add(Color.Gray) } }
    val glowingStates = mutableStateListOf(*Array(7) { false })
    val buttonStates = mutableStateListOf<Boolean>().apply { repeat(24) { add(false) } }

    fun resetAllLeds() {
        // Reset all LEDs to gray (off state)
        for (i in ledStatus.indices) {
            ledStatus[i] = Color.Gray
        }

        // Reset all glowing states
        for (i in glowingStates.indices) {
            glowingStates[i] = false
        }

        // Reset all button states
        for (i in buttonStates.indices) {
            buttonStates[i] = false
        }
    }
}

@Composable
fun LabatKeyboardScreen(bleManager: BleManager) {
    val context = LocalContext.current
    val message = remember { mutableStateOf("Press any key to test") }
    val bluetoothStatus = remember { mutableStateOf("Bluetooth Disconnected") }
    val isConnected = bleManager.isConnected.value
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter
    val keyPress = bleManager.keyPressFlow.collectAsState().value
    val keyStates = remember { mutableStateListOf(*BooleanArray(24) { false }.toTypedArray()) }

    val mediaPlayer = remember { MediaPlayer.create(context, R.raw.beep) }
    val scrollState = rememberScrollState()

    DisposableEffect(Unit) {
        bluetoothStatus.value = if (bleManager.isConnected.value) "Connected to ESP" else "Bluetooth Disconnected"
        onDispose { mediaPlayer.release() }
    }

    LaunchedEffect(isConnected) {
        bluetoothStatus.value = if (isConnected) "Connected to ESP" else "Bluetooth Disconnected"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = "Labat Audiolab+ App",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = bluetoothStatus.value,
                    color = if (isConnected) Color.Green else Color.Red,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                TopTitles(isConnected)
                Spacer(modifier = Modifier.height(16.dp))
                OvershootLEDs(isConnected)
                Spacer(modifier = Modifier.height(8.dp))
                MiddleButtons(bleManager, mediaPlayer, message, keyPress, keyStates)
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (!bleManager.isConnected.value) {
                                try {
                                    val device = bluetoothAdapter.getRemoteDevice(BleManager.ESP32_MAC_ADDRESS)
                                    bleManager.connect(device) {
                                        bluetoothStatus.value = "Connected to ESP"
                                        message.value = "Connected to ESP"
                                        Log.d("BLE", "Connected successfully")
                                    }
                                } catch (e: Exception) {
                                    bluetoothStatus.value = "Connection Failed"
                                    Log.e("BLE", "Connection failed: ${e.message}")
                                }
                            } else {
                                bluetoothStatus.value = "Already connected"
                                message.value = "Already connected to ESP"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) Color.Green else Color.DarkGray
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Connect", color = Color.White)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = {
                                // Reset UI state in AppState
                                AppState.resetAllLeds()

                                // Reset key states in the local state too
                                for (i in keyStates.indices) {
                                    keyStates[i] = false
                                }

                                // Notify ESP32
                                bleManager.writeCommand("RESET_LEDS")

                                // Clear message
                                message.value = "All LEDs and Buttons Reset"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Text("Reset", color = Color.Black)
                        }

                        Image(
                            painter = painterResource(id = R.drawable.bt),
                            contentDescription = "Bluetooth Icon",
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            bleManager.disconnect()
                            bluetoothStatus.value = "Bluetooth Disconnected"
                            message.value = "Disconnected"
                            // Also reset LEDs when disconnecting
                            AppState.resetAllLeds()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}


@Composable
fun TopTitles(isConnected: Boolean) {
    val ledLabels = listOf("LOMBARD", "STENGER", "ABLB", "SISI", "TDT", "LIVE VOICE", "PURE TONE")

    // Start LED glowing sequence when connected
    LaunchedEffect(isConnected) {
        if (isConnected) {
            for (i in ledLabels.indices) {
                AppState.glowingStates[i] = true
                delay(500L)
            }
        } else {
            for (i in AppState.glowingStates.indices) {
                AppState.glowingStates[i] = false
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("VU Meter", color = Color.White, fontSize = 16.sp)
        Text("dB", color = Color.White, fontSize = 16.sp)
        Text("Hz", color = Color.White, fontSize = 16.sp)
        Text("dB/Score", color = Color.White, fontSize = 16.sp)

        // LED Display: Two columns inside a Row
        Row(
            modifier = Modifier.height(IntrinsicSize.Min), // Equal height columns
            horizontalArrangement = Arrangement.Center
        ) {
            // Column 1 (4 LEDs)
            Column(
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.fillMaxHeight()
            ) {
                for (i in 0..3) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LEDCircle(if (AppState.glowingStates[i]) Color.Green else Color.Gray)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(ledLabels[i], color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(32.dp)) // spacing between columns

            // Column 2 (3 LEDs)
            Column(
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.fillMaxHeight()
            ) {
                for (i in 4 until ledLabels.size) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LEDCircle(if (AppState.glowingStates[i]) Color.Green else Color.Gray)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(ledLabels[i], color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun OvershootLEDs(isConnected: Boolean) {
    val positiveLabels = listOf("+3", "+2", "+1")
    val negativeLabels = listOf("0", "-3", "-5", "-7", "-10", "-20")
    val ledColor = if (isConnected) Color.Green else Color.Gray
    val patRespColor = if (isConnected) Color.Red else Color.Gray
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start
    ) {
        // Overshoot section
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier.padding(end = 24.dp)
        ) {
            Text(
                "Overshoot(dB)",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            positiveLabels.forEach { label ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(ledColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label, color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // Normal section - labels only
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                "Normal(dB)",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            negativeLabels.forEach { label ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(ledColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label, color = Color.White, fontSize = 12.sp)
                }
            }
        }
        // Spacer to push PAT. RESP block to center of the whole Row
        Spacer(modifier = Modifier.weight(1f))
        // PAT. RESP block centered horizontally
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(top = 24.dp) // adjust vertical alignment if needed
        ) {
            Text(
                "PAT. RESP",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(patRespColor)
                    )
                    if (it < 2) Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MiddleButtons(
    bleManager: BleManager,
    mediaPlayer: MediaPlayer,
    message: MutableState<String>,
    keyPress: String,
    keyStates: MutableList<Boolean>
) {
    val buttonNames = listOf(
        "EAR", "MODE", "STIMTYPE", "TALKOVER", "PULSE", "INVERT",
        "TALKBACK", "SAVE", "CANCEL", "TEST", "20dB", "NO_RESP", "MARK",
        "STIMULUS", "INT1+", "INT1-", "FREQ+", "FREQ-", "INT2+", "INT2-",
        "MASKING", "F1", "F2", "SELECT"
    )

    // Split buttons into two rows
    val firstRowButtons = buttonNames.take(12) // First 12 buttons
    val secondRowButtons = buttonNames.drop(12) // Remaining buttons

    // Improved name mapping to handle various key formats from BLE
    val nameMap = mapOf(
        "TALKBAC" to "TALKBACK",
        "STIMULU" to "STIMULUS",
        "STIMTYP" to "STIMTYPE",
        "TALKOVE" to "TALKOVER",
        "20dB" to "20dB",
        "NO_RESP" to "NO_RESP",
        // Add more mappings for any truncated or differently named keys
        "INT1+" to "INT1+",
        "INT1-" to "INT1-",
        "INT2+" to "INT2+",
        "INT2-" to "INT2-",
        "FREQ+" to "FREQ+",
        "FREQ-" to "FREQ-"
    )

    // Using mutableStateListOf to ensure UI updates when keyStates change
    // Now using AppState to store button states so they persist
    val keyStatesInternal = remember { AppState.buttonStates }

    LaunchedEffect(Unit) {
        // Initialize internal key states from external state
        keyStates.forEachIndexed { index, value ->
            if (index < keyStatesInternal.size) {
                keyStatesInternal[index] = value
            }
        }
    }

    val isConnected by bleManager.isConnected

    // Handle BLE key press parsing with improved detection - MODIFIED to maintain pressed state
    LaunchedEffect(keyPress) {
        if (keyPress.isBlank()) return@LaunchedEffect

        val keyMessage = keyPress.trim()
        Log.d("KeyDebug", "Received key message: $keyMessage")

        // First try to parse as "KEY PRESSED: ButtonName" format
        val keyPressPattern = "KEY PRESSED: (\\w+[+-]?)".toRegex()
        val match = keyPressPattern.find(keyMessage)

        if (match != null) {
            val rawKey = match.groupValues[1]
            val keyName = nameMap[rawKey] ?: rawKey
            val index = buttonNames.indexOf(keyName)

            Log.d("KeyDebug", "Matched key: $keyName, index: $index")

            if (index != -1) {
                // Toggle button state instead of setting it temporarily
                keyStatesInternal[index] = !keyStatesInternal[index]
                keyStates[index] = keyStatesInternal[index]
                message.value = "$keyName ${if(keyStatesInternal[index]) "Activated" else "Deactivated"}"
                try {
                    mediaPlayer.start()
                } catch (e: Exception) {
                    Log.e("MiddleButtons", "MediaPlayer error: ${e.message}")
                }

                // No delay and reset - we want the state to persist until toggled again
            }
        } else {
            // Alternative parsing for different message formats
            val parts = keyMessage.split(":").lastOrNull()?.trim()?.split(" ") ?: return@LaunchedEffect
            if (parts.size >= 2) {
                val rawKey = parts.last()
                val keyName = nameMap[rawKey] ?: rawKey
                val index = buttonNames.indexOf(keyName)

                Log.d("KeyDebug", "Alternative parse - key: $keyName, index: $index")

                if (index != -1) {
                    // Toggle button state instead of setting it temporarily
                    keyStatesInternal[index] = !keyStatesInternal[index]
                    keyStates[index] = keyStatesInternal[index]
                    message.value = "$keyName ${if(keyStatesInternal[index]) "Activated" else "Deactivated"}"
                    try {
                        mediaPlayer.start()
                    } catch (e: Exception) {
                        Log.e("MiddleButtons", "MediaPlayer error: ${e.message}")
                    }

                    // No delay and reset - we want the state to persist until toggled again
                }
            }
        }
    }

    // On connection, blink LEDs
    LaunchedEffect(isConnected) {
        if (isConnected) {
            delay(3_500L)
            for (i in 0 until 39) {
                if (!isActive) break
                bleManager.writeCommand("LED_ON_${i + 1}_${if (i < 12) "ORANGE" else "BLUE"}")
                AppState.ledStatus[i] = if (i < 12) Color(0xFFFFA500) else Color.Blue
                delay(500L)
            }
        } else {
            AppState.ledStatus.indices.forEach { AppState.ledStatus[it] = Color.Gray }
        }
    }

    // Ping BLE device every 5s
    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (isActive) {
                bleManager.writeCommand("PING")
                delay(5000L)
            }
        }
    }

    // === UI ===
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            message.value,
            color = Color.White,
            fontSize = 24.sp, // Reduced from 30sp
            modifier = Modifier.padding(start = 500.dp) // Reduced from 500dp
        )
        Spacer(modifier = Modifier.height(12.dp)) // Reduced from 30dp

        // First Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            firstRowButtons.forEachIndexed { localIndex, name ->
                val index = localIndex // Index in the original buttonNames list

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    // LED indicators above buttons - Optimized height container for alignment
                    Box(
                        modifier = Modifier.height(66.dp), // Slightly increased to accommodate 3 LED rows
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        when (name) {
                            "EAR" -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[0])
                                        Spacer(Modifier.width(3.dp))
                                        Text("L+R", color = Color.White, fontSize = 7.sp)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[1])
                                        Spacer(Modifier.width(3.dp))
                                        Text("L", color = Color.White, fontSize = 7.sp)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[2])
                                        Spacer(Modifier.width(3.dp))
                                        Text("R", color = Color.White, fontSize = 7.sp)
                                    }
                                }
                            }
                            "MODE" -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[3])
                                        Spacer(Modifier.width(3.dp))
                                        Text("FF", color = Color.White, fontSize = 7.sp)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[4])
                                        Spacer(Modifier.width(3.dp))
                                        Text("BC", color = Color.White, fontSize = 7.sp)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[5])
                                        Spacer(Modifier.width(3.dp))
                                        Text("AC", color = Color.White, fontSize = 7.sp)
                                    }
                                }
                            }
                            "STIMTYPE" -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[6])
                                        Spacer(Modifier.width(3.dp))
                                        Text("WARBLE", color = Color.White, fontSize = 6.sp)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[7])
                                        Spacer(Modifier.width(3.dp))
                                        Text("TONE", color = Color.White, fontSize = 7.sp)
                                    }
                                }
                            }
                            "TALKOVER" -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    LEDCircle(AppState.ledStatus[8])
                                }
                            }
                            "PULSE" -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    LEDCircle(AppState.ledStatus[9])
                                }
                            }
                            "INVERT" -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    LEDCircle(AppState.ledStatus[10])
                                }
                            }
                            "TALKBACK" -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    LEDCircle(AppState.ledStatus[11])
                                }
                            }
                            else -> {
                                // Empty space for buttons without LEDs
                                Spacer(modifier = Modifier.height(8.dp)) // Reduced from 12dp
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp)) // Reduced from 8dp

                    Button(
                        onClick = {
                            bleManager.writeCommand(name)
                            // Toggle button state
                            AppState.buttonStates[index] = !AppState.buttonStates[index]
                            keyStates[index] = AppState.buttonStates[index]
                            message.value = "$name ${if(AppState.buttonStates[index]) "Activated" else "Deactivated"}"
                            try {
                                mediaPlayer.start()
                            } catch (e: Exception) {
                                Log.e("MiddleButtons", "MediaPlayer error: ${e.message}")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (AppState.buttonStates[index]) Color.Green else Color.DarkGray
                        ),
                        modifier = Modifier
                            .padding(1.dp) // Reduced from 2dp
                            .height(40.dp) // Reduced from 48dp
                    ) {
                        Text(
                            name,
                            color = if (AppState.buttonStates[index]) Color.Black else Color.White,
                            fontSize = 9.sp, // Reduced from 10sp
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp)) // Reduced from 20dp

        // Second Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            secondRowButtons.forEachIndexed { localIndex, name ->
                val index = localIndex + 12 // Index in the original buttonNames list

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    // LED indicators above buttons - Optimized height container for alignment
                    Box(
                        modifier = Modifier.height(76.dp), // Adjusted for better spacing
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        when (name) {
                            "MARK" -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    LEDCircle(AppState.ledStatus[12])
                                }
                            }
                            "STIMULUS" -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    LEDCircle(AppState.ledStatus[13])
                                }
                            }
                            "MASKING" -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[14])
                                        Spacer(Modifier.width(3.dp))
                                        Text("SN", color = Color.White, fontSize = 8.sp)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[15])
                                        Spacer(Modifier.width(3.dp))
                                        Text("NBN", color = Color.White, fontSize = 7.sp)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LEDCircle(AppState.ledStatus[16])
                                        Spacer(Modifier.width(3.dp))
                                        Text("WN", color = Color.White, fontSize = 8.sp)
                                    }
                                }
                            }
                            "F1" -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(bottom = 2.dp) // Reduced from 4dp
                                    ) {
                                        LEDCircle(AppState.ledStatus[17])
                                        Spacer(Modifier.width(4.dp)) // Reduced from 6dp
                                        LEDCircle(AppState.ledStatus[18])
                                        Spacer(Modifier.width(4.dp))
                                        LEDCircle(AppState.ledStatus[19])
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.micky),
                                            contentDescription = "Micky",
                                            modifier = Modifier.size(20.dp) // Reduced from 24dp
                                        )
                                        Spacer(Modifier.width(3.dp)) // Reduced from 4dp
                                        Image(
                                            painter = painterResource(id = R.drawable.monitor),
                                            contentDescription = "Monitor",
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Image(
                                            painter = painterResource(id = R.drawable.lightbulb),
                                            contentDescription = "Lightbulb",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                            else -> {
                                // Empty space for buttons without LEDs
                                Spacer(modifier = Modifier.height(8.dp)) // Reduced from 12dp
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp)) // Reduced from 8dp

                    Button(
                        onClick = {
                            bleManager.writeCommand(name)
                            // Toggle button state
                            AppState.buttonStates[index] = !AppState.buttonStates[index]
                            keyStates[index] = AppState.buttonStates[index]
                            message.value = "$name ${if(AppState.buttonStates[index]) "Activated" else "Deactivated"}"
                            try {
                                mediaPlayer.start()
                            } catch (e: Exception) {
                                Log.e("MiddleButtons", "MediaPlayer error: ${e.message}")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (AppState.buttonStates[index]) Color.Green else Color.DarkGray
                        ),
                        modifier = Modifier
                            .padding(1.dp) // Reduced from 2dp
                            .height(40.dp) // Reduced from 48dp
                    ) {
                        Text(
                            name,
                            color = if (AppState.buttonStates[index]) Color.Black else Color.White,
                            fontSize = 9.sp, // Reduced from 10sp
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// Make sure this exists in your project
@Composable
fun LEDCircle(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp) // Reduced from 12dp
            .background(color, CircleShape)
    )
}