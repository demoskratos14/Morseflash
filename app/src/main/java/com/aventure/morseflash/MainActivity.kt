package com.aventure.morseflash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
                MorseScreen()
            }
        }
    }
}

@Composable
fun MorseScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val torch = remember { TorchController(context) }

    var text by remember { mutableStateOf("") }
    var unitMs by remember { mutableFloatStateOf(250f) }
    var transmitting by remember { mutableStateOf(false) }
    var lampOn by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    // Garde l'écran allumé pendant l'émission, éteint la torche en quittant.
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
        val steps = MorseCode.toSteps(text, unitMs.toLong())
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
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Morse Flash", style = MaterialTheme.typography.headlineMedium)

        if (!torch.isAvailable) {
            Text(
                "Aucun flash détecté sur cet appareil.",
                color = MaterialTheme.colorScheme.error
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = { if (!transmitting) text = it },
            label = { Text("Ton message") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            enabled = !transmitting
        )

        Column(Modifier.fillMaxWidth()) {
            Text("Vitesse : unité de ${unitMs.toInt()} ms")
            Slider(
                value = unitMs,
                onValueChange = { unitMs = it },
                valueRange = 100f..500f,
                enabled = !transmitting
            )
            Text(
                "Plus l'unité est courte, plus c'est rapide.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (text.isNotBlank()) {
            Text(
                MorseCode.toMorseString(text),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (lampOn) Color(0xFFFFC107) else Color(0xFFBDBDBD))
        )

        Button(
            onClick = { if (transmitting) stop() else start() },
            enabled = torch.isAvailable && (transmitting || text.isNotBlank()),
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
