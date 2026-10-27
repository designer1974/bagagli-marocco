package com.bagaglimarocco.autista.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bagaglimarocco.autista.R

@Composable
fun SignScreen(state: UiState, onConfirm: (code: String, signature: Bitmap?) -> Unit) {
    val current = state.current
    if (current == null) {
        EmptyNote(stringResource(R.string.all_done))
        return
    }
    var code by rememberSaveable(current.stop.id) { mutableStateOf("") }
    val strokes = remember(current.stop.id) { mutableStateListOf<List<Offset>>() }
    var active by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val ink = MaterialTheme.colorScheme.onSurface

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SurfaceCard {
            Column {
                Text(stringResource(R.string.sign_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current.stop.name, style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.unloaded_of, current.unloaded, current.bags.size), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionTitle(stringResource(R.string.recipient_code))
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it.isDigit() }.take(4) },
            placeholder = { Text(stringResource(R.string.recipient_code_hint)) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 26.sp, textAlign = TextAlign.Center, letterSpacing = 8.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.signature)) }
            TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.clear)) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(CardShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, MaterialTheme.colorScheme.outline, CardShape)
                .onSizeChanged { padSize = it }
                .pointerInput(current.stop.id) {
                    detectDragGestures(
                        onDragStart = { active = listOf(it) },
                        onDrag = { change, _ -> active = active + change.position },
                        onDragEnd = { if (active.size > 1) strokes.add(active); active = emptyList() },
                        onDragCancel = { active = emptyList() },
                    )
                },
        ) {
            if (strokes.isEmpty() && active.isEmpty()) {
                Text(
                    stringResource(R.string.sign_here),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                (strokes + listOf(active)).forEach { pts ->
                    if (pts.size > 1) {
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, ink, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }

        BigButton(
            stringResource(R.string.go_sign),
            { onConfirm(code, renderSignature(strokes, padSize)) },
            icon = Icons.Filled.CheckCircle,
        )
    }
}

/** Disegna la firma su un'immagine bianca da salvare. Null se la firma è vuota. */
private fun renderSignature(strokes: List<List<Offset>>, size: IntSize): Bitmap? {
    if (strokes.isEmpty() || size.width <= 0 || size.height <= 0) return null
    val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 6f
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isAntiAlias = true
    }
    strokes.forEach { pts ->
        val path = android.graphics.Path()
        path.moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { path.lineTo(it.x, it.y) }
        canvas.drawPath(path, paint)
    }
    return bmp
}
