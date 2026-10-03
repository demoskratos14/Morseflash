package com.aventure.morseflash

import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Position suivie de la lampe, en coordonnées normalisées de l'aperçu affiché (0..1). */
private class TrackState {
    @Volatile var u = 0.5f
    @Volatile var v = 0.5f
    @Volatile var acquired = false
    @Volatile var bright = false
}

/**
 * Repère automatiquement le point le plus lumineux de l'image (la lampe de l'émetteur),
 * le suit d'une image à l'autre, et mesure sa luminosité à chaque image.
 */
private class LampTracker(
    private val track: TrackState,
    private val onSample: (Long, Float) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile private var resetRequested = false

    fun requestReset() {
        resetRequested = true
    }

    private val hist = IntArray(256)
    private var cx = -1f
    private var cy = -1f
    private var acquired = false
    private var lastBrightMs = 0L

    // Résultats des recherches (évite les allocations à chaque image)
    private var gMax = 0
    private var gMean = 0f
    private var gX = 0
    private var gY = 0
    private var cenX = 0f
    private var cenY = 0f

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = image.width
            val h = image.height
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val nowMs = image.imageInfo.timestamp / 1_000_000L

            if (resetRequested || cx < 0f) {
                resetRequested = false
                cx = w / 2f
                cy = h / 2f
                acquired = false
            }

            // 1) Pas encore de lampe repérée : recherche du point lumineux dans toute l'image
            if (!acquired) {
                globalSearch(buf, w, h, rs, ps)
                if (gMax >= ACQUIRE_MIN && gMax - gMean >= ACQUIRE_CONTRAST) {
                    centroid(buf, w, h, rs, ps, gX, gY, 14, (gMax * 0.75f).toInt())
                    cx = cenX
                    cy = cenY
                    acquired = true
                    lastBrightMs = nowMs
                }
            }

            // 2) Mesure dans une fenêtre autour de la lampe
            val half = WIN_HALF
            val px = cx.toInt().coerceIn(half, w - half - 1)
            val py = cy.toInt().coerceIn(half, h - half - 1)
            java.util.Arrays.fill(hist, 0)
            var maxV = 0
            for (y in py - half..py + half) {
                val row = y * rs
                for (x in px - half..px + half) {
                    val pv = buf.get(row + x * ps).toInt() and 0xFF
                    hist[pv]++
                    if (pv > maxV) maxV = pv
                }
            }
            // Moyenne des pixels les plus lumineux de la fenêtre
            var count = 0
            var sum = 0L
            var lvl = 255
            while (lvl >= 0 && count < TOP_N) {
                val c = hist[lvl]
                if (c > 0) {
                    count += c
                    sum += c.toLong() * lvl
                }
                lvl--
            }
            val value = if (count > 0) sum.toFloat() / count else 0f

            // 3) Lampe allumée : on recentre doucement la fenêtre sur elle (suivi)
            val bright = value >= BRIGHT_MIN
            if (bright) {
                lastBrightMs = nowMs
                centroid(buf, w, h, rs, ps, px, py, half, (maxV * 0.75f).toInt())
                cx += 0.5f * (cenX - cx)
                cy += 0.5f * (cenY - cy)
            } else if (acquired && nowMs - lastBrightMs > LOST_MS) {
                acquired = false
            }

            // 4) Publication de la position (en tenant compte de la rotation de l'image)
            val nx = cx / w
            val ny = cy / h
            when (image.imageInfo.rotationDegrees) {
                90 -> { track.u = 1f - ny; track.v = nx }
                180 -> { track.u = 1f - nx; track.v = 1f - ny }
                270 -> { track.u = ny; track.v = 1f - nx }
                else -> { track.u = nx; track.v = ny }
            }
            track.acquired = acquired
            track.bright = bright

            onSample(nowMs, value)
        } finally {
            image.close()
        }
    }

    private fun globalSearch(buf: ByteBuffer, w: Int, h: Int, rs: Int, ps: Int) {
        var max = 0
        var mx = 0
        var my = 0
        var sum = 0L
        var n = 0
        var y = 0
        while (y < h) {
            val row = y * rs
            var x = 0
            while (x < w) {
                val pv = buf.get(row + x * ps).toInt() and 0xFF
                sum += pv
                n++
                if (pv > max) {
                    max = pv
                    mx = x
                    my = y
                }
                x += 2
            }
            y += 2
        }
        gMax = max
        gX = mx
        gY = my
        gMean = if (n > 0) sum.toFloat() / n else 0f
    }

    /** Centre de gravité (pondéré par la luminosité) des pixels >= [minV] autour de (px, py). */
    private fun centroid(
        buf: ByteBuffer, w: Int, h: Int, rs: Int, ps: Int,
        px: Int, py: Int, half: Int, minV: Int
    ) {
        val x0 = (px - half).coerceAtLeast(0)
        val x1 = (px + half).coerceAtMost(w - 1)
        val y0 = (py - half).coerceAtLeast(0)
        val y1 = (py + half).coerceAtMost(h - 1)
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        for (y in y0..y1) {
            val row = y * rs
            for (x in x0..x1) {
                val pv = buf.get(row + x * ps).toInt() and 0xFF
                if (pv >= minV) {
                    sx += x.toDouble() * pv
                    sy += y.toDouble() * pv
                    sw += pv
                }
            }
        }
        if (sw > 0.0) {
            cenX = (sx / sw).toFloat()
            cenY = (sy / sw).toFloat()
        } else {
            cenX = px.toFloat()
            cenY = py.toFloat()
        }
    }

    companion object {
        private const val WIN_HALF = 36
        private const val TOP_N = 6
        private const val BRIGHT_MIN = 150f
        private const val ACQUIRE_MIN = 170
        private const val ACQUIRE_CONTRAST = 70f
        private const val LOST_MS = 4000L
    }
}

@Composable
fun ReceiveScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = context as ComponentActivity

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val decoder = remember { MorseDecoder() }
    val listeningFlag = remember { AtomicBoolean(false) }
    val track = remember { TrackState() }
    val tracker = remember {
        LampTracker(track) { t, lum ->
            if (listeningFlag.get()) {
                synchronized(decoder) { decoder.addSample(t, lum) }
            }
        }
    }

    var listening by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(MorseDecoder.Result()) }
    var status by remember { mutableStateOf(MorseDecoder.Status(0f, false, false)) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomMax by remember { mutableFloatStateOf(1f) }

    var markerU by remember { mutableFloatStateOf(0.5f) }
    var markerV by remember { mutableFloatStateOf(0.5f) }
    var markerAcquired by remember { mutableStateOf(false) }
    var markerBright by remember { mutableStateOf(false) }

    // Synthèse vocale : lecture du message reçu
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsFrench by remember { mutableStateOf(true) }
    var speaking by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val lang = engine?.setLanguage(Locale.FRENCH)
                ttsFrench = lang != TextToSpeech.LANG_MISSING_DATA &&
                    lang != TextToSpeech.LANG_NOT_SUPPORTED
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        speaking = false
                    }
                    override fun onError(utteranceId: String?) {
                        speaking = false
                    }
                })
                ttsReady = true
            }
        }
        tts = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
        }
    }

    // Écran allumé pendant l'écoute
    DisposableEffect(listening) {
        view.keepScreenOn = listening
        onDispose { view.keepScreenOn = false }
    }

    // Position du repère de suivi (même avant le démarrage, pour pouvoir vérifier la visée)
    LaunchedEffect(Unit) {
        while (true) {
            markerU = track.u
            markerV = track.v
            markerAcquired = track.acquired
            markerBright = track.bright
            delay(80)
        }
    }

    // Rafraîchit le texte décodé pendant l'écoute
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        while (true) {
            synchronized(decoder) {
                result = decoder.result()
                status = decoder.status()
            }
            delay(100)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("L'accès à la caméra est nécessaire pour lire le clignotement du flash.")
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Autoriser la caméra") }
                }
            }
        } else {
            val previewView = remember {
                PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
            }

            DisposableEffect(lifecycleOwner) {
                val executor = Executors.newSingleThreadExecutor()
                val providerFuture = ProcessCameraProvider.getInstance(context)
                var provider: ProcessCameraProvider? = null

                providerFuture.addListener({
                    try {
                        val p = providerFuture.get()
                        provider = p

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor, tracker)

                        p.unbindAll()
                        val cam = p.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )

                        // Image sous-exposée : la lampe de l'émetteur ressort davantage
                        val exp = cam.cameraInfo.exposureState
                        if (exp.isExposureCompensationSupported) {
                            cam.cameraControl.setExposureCompensationIndex(
                                exp.exposureCompensationRange.lower
                            )
                        }

                        zoomMax = (cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f)
                            .coerceAtMost(10f)
                        zoom = 1f
                        camera = cam
                    } catch (e: Exception) {
                        // Caméra indisponible
                    }
                }, ContextCompat.getMainExecutor(context))

                onDispose {
                    listeningFlag.set(false)
                    provider?.unbindAll()
                    executor.shutdown()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(Color.Black, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                )
                // Repère de suivi de la lampe
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val markerSize = 32.dp
                    val markerColor = when {
                        markerAcquired && markerBright -> Color(0xFF4CAF50)
                        markerAcquired -> Color(0xFFFFC107)
                        else -> Color.White
                    }
                    Box(
                        modifier = Modifier
                            .offset(
                                x = maxWidth * markerU - markerSize / 2,
                                y = maxHeight * markerV - markerSize / 2
                            )
                            .size(markerSize)
                            .border(3.dp, markerColor, RoundedCornerShape(4.dp))
                    )
                }
            }

            Text(
                when {
                    listening && !status.hasSignal -> "En attente d'un signal lumineux…"
                    listening && status.lampOn -> "Lumière détectée"
                    listening -> "Signal capté, lampe éteinte"
                    markerAcquired -> "Lampe repérée et suivie. Tu peux démarrer la réception."
                    else -> "Allume la lampe de l'autre téléphone : elle sera repérée automatiquement."
                },
                color = Color.White
            )

            if (camera != null && zoomMax > 1.2f) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Zoom : ${"%.1f".format(zoom)}×", color = Color.White)
                    Slider(
                        value = zoom,
                        onValueChange = {
                            zoom = it
                            camera?.cameraControl?.setZoomRatio(it)
                            tracker.requestReset()
                        },
                        valueRange = 1f..zoomMax
                    )
                    Text(
                        "Zoome pour agrandir la lampe quand elle est loin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (listening) {
                            listeningFlag.set(false)
                            listening = false
                        } else {
                            synchronized(decoder) { decoder.reset() }
                            result = MorseDecoder.Result()
                            status = MorseDecoder.Status(0f, false, false)
                            listeningFlag.set(true)
                            listening = true
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = if (listening)
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.buttonColors()
                ) {
                    Text(if (listening) "Arrêter" else "Démarrer la réception")
                }
                OutlinedButton(
                    onClick = {
                        synchronized(decoder) { decoder.reset() }
                        result = MorseDecoder.Result()
                        status = MorseDecoder.Status(0f, false, false)
                        tracker.requestReset()
                    },
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.85f)
                    )
                ) {
                    Text("Effacer")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Texte reçu", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (result.text.isEmpty()) "…" else result.text,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    val morseShown = buildString {
                        append(result.morse)
                        if (result.pending.isNotEmpty()) {
                            if (isNotEmpty()) append(' ')
                            append(result.pending)
                        }
                    }
                    if (morseShown.isNotEmpty()) {
                        Text(
                            morseShown,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (result.unitMs > 0) {
                        Text(
                            "Vitesse estimée : ${result.unitMs} ms par unité" +
                                if (result.calibrated) " (calibrage détecté)" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            if (speaking) {
                                tts?.stop()
                                speaking = false
                            } else {
                                // Si la réception tourne encore, on l'arrête puis on lit le message obtenu
                                if (listening) {
                                    listeningFlag.set(false)
                                    listening = false
                                }
                                val finalResult = synchronized(decoder) { decoder.result() }
                                result = finalResult
                                if (finalResult.text.isNotBlank()) {
                                    speaking = true
                                    tts?.speak(
                                        finalResult.text,
                                        TextToSpeech.QUEUE_FLUSH,
                                        null,
                                        "morse_message"
                                    )
                                }
                            }
                        },
                        enabled = ttsReady && (speaking || result.text.isNotEmpty()),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (speaking) "Arrêter la lecture" else "Lire le message à voix haute")
                    }
                    if (!ttsFrench) {
                        Text(
                            "Voix française non installée : la lecture utilisera la voix par défaut du téléphone.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
