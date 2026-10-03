package com.aventure.morseflash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MorseApp()
            }
        }
    }
}

@Composable
fun MorseApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    var calibrate by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_morse),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Morse Flash",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )

            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = Color.White
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Émettre") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Recevoir") })
            }

            if (tab == 0) {
                TransmitScreen(
                    text = text,
                    onTextChange = { text = it },
                    calibrate = calibrate,
                    onCalibrateChange = { calibrate = it }
                )
            } else {
                ReceiveScreen()
            }
        }
    }
}

@Composable
fun TransmitScreen(
    text: String,
    onTextChange: (String) -> Unit,
    calibrate: Boolean,
    onCalibrateChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val torch = remember { TorchController(context) }

    var transmitting by remember { mutableStateOf(false) }
    var lampOn by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    val unitMs = MorseCode.TRANSMIT_UNIT_MS
    val steps = remember(text, calibrate) { MorseCode.toSteps(text, unitMs, calibrate) }
    val durationSeconds = steps.sumOf { it.ms } / 1000

    // Garde l'écran allumé pendant l'émission, éteint la torche en quittant l'onglet.
    DisposableEffect(transmitting) {
        view.keepScreenOn = transmitting
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            torch.set(false)
        }
    }

    fun start() {
        if (steps.isEmpty()) return
        job = scope.launch {
            transmitting = true
            try {
                for (step in steps) {
                    torch.set(step.on)
                    lampOn = step.on
                    delay(step.ms)
                }
            } finally {
                torch.set(false)
                lampOn = false
                transmitting = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!torch.isAvailable) {
            Text(
                "Aucun flash détecté sur cet appareil.",
                color = Color(0xFFFFB4AB)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.9f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (!transmitting) onTextChange(it) },
                    label = { Text("Ton message") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !transmitting
                )

                Text(
                    "Vitesse verrouillée (${unitMs} ms par unité), réglée pour une lecture fiable par caméra.",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(
                        checked = calibrate,
                        onCheckedChange = { if (!transmitting) onCalibrateChange(it) },
                        enabled = !transmitting
                    )
                    Text(
                        "Séquence de calibrage au début (optionnelle) : aide le récepteur à régler sa vitesse.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (text.isNotBlank()) {
                    Text(
                        (if (calibrate) "-.-.- / " else "") + MorseCode.toMorseString(text),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (steps.isNotEmpty()) {
                        Text(
                            "Durée de l'émission : environ $durationSeconds s",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (lampOn) Color(0xFFFFC107) else Color(0xFFBDBDBD))
        )

        Button(
            onClick = { if (transmitting) stop() else start() },
            enabled = torch.isAvailable && (transmitting || steps.isNotEmpty()),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = if (transmitting)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors()
        ) {
            Text(if (transmitting) "Arrêter" else "Émettre")
        }
    }
}
