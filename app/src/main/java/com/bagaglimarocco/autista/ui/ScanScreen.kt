package com.bagaglimarocco.autista.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bagaglimarocco.autista.R
import com.bagaglimarocco.autista.data.BagEntity
import com.bagaglimarocco.autista.ui.theme.LocalStatus
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun ScanScreen(
    state: UiState,
    lastScanned: BagEntity?,
    onCode: (String, Boolean) -> Unit,
    onDamage: (String) -> Unit,
) {
    val current = state.current
    if (current == null) {
        EmptyNote(stringResource(R.string.all_done))
        return
    }
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    var manualOpen by remember { mutableStateOf(false) }
    var damageOpen by remember { mutableStateOf(false) }
    val status = LocalStatus.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CardShape)
                    .background(Color(0xFF0A0F14)),
                contentAlignment = Alignment.Center,
            ) {
                if (hasPermission) {
                    CameraPreview(onCode = { onCode(it, true) })
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .aspectRatio(1f)
                            .border(4.dp, MaterialTheme.colorScheme.secondary, CardShape),
                    )
                    Text(
                        stringResource(R.string.scan_aim),
                        color = Color(0xFFCFD8E2),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(stringResource(R.string.scan_no_permission), color = Color.White)
                        TextButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.scan_allow), color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }

        lastScanned?.let { bag ->
            item {
                Column(
                    Modifier.fillMaxWidth().clip(CardShape).background(status.okSoft).padding(14.dp),
                ) {
                    Text("✓ " + stringResource(R.string.scan_ok), color = status.ok, fontWeight = FontWeight.Bold)
                    Text("${bag.code} · ${"%.1f".format(bag.weightKg)} kg · ${bag.description}", fontFamily = FontFamily.Monospace)
                }
            }
        }

        item {
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.unloaded_here), style = MaterialTheme.typography.bodyMedium)
                        Text(current.stop.name, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${current.unloaded} / ${current.bags.size}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { manualOpen = true }, modifier = Modifier.weight(1f), shape = CardShape) {
                    Text(stringResource(R.string.type_code))
                }
                OutlinedButton(onClick = { damageOpen = true }, modifier = Modifier.weight(1f), shape = CardShape) {
                    Text(stringResource(R.string.report_damage), color = status.bad)
                }
            }
        }

        if (current.remaining.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.still_to_unload)) }
            items(current.remaining, key = { it.code }) { BagRow(it) }
        }
    }

    if (manualOpen) {
        CodeDialog(
            onDismiss = { manualOpen = false },
            onConfirm = { onCode(it, false); manualOpen = false },
        )
    }
    if (damageOpen) {
        CodeDialog(
            onDismiss = { damageOpen = false },
            onConfirm = { onDamage(it); damageOpen = false },
            initial = lastScanned?.code.orEmpty(),
        )
    }
}

@Composable
private fun CodeDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit, initial: String = "") {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.type_code)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(20) },
                label = { Text(stringResource(R.string.code_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("✕") } },
    )
}

/** Anteprima della fotocamera che legge i QR code con ML Kit (funziona offline). */
@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128).build(),
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            try {
                val p = providerFuture.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy, scanner) { code -> previewView.post { currentOnCode(code) } } }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // La fotocamera può mancare o essere occupata: l'app non deve chiudersi.
                Log.e("ScanScreen", "Fotocamera non disponibile", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            try { provider?.unbindAll() } catch (_: Exception) {}
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@OptIn(ExperimentalGetImage::class)
private fun analyze(proxy: ImageProxy, scanner: BarcodeScanner, onCode: (String) -> Unit) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onCode) }
        .addOnCompleteListener { proxy.close() }
}
