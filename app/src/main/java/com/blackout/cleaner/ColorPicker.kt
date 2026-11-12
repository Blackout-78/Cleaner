package com.blackout.cleaner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Color wheel: around = hue, center to edge = strength, slider = brightness, box = hex code. */
@Composable
fun ColorPickerDialog(title: String, initial: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val start = FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2].coerceAtLeast(0.2f)) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    val color = Color(argb)
    var hex by remember(argb) { mutableStateOf("#%06X".format(argb and 0xFFFFFF)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.surface,
        title = { Text(title, color = C.text) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(220.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { o ->
                                val cx = size.width / 2f; val cy = size.height / 2f
                                hue = ((Math.toDegrees(atan2(o.y - cy, o.x - cx).toDouble()) + 360) % 360).toFloat()
                                sat = (hypot(o.x - cx, o.y - cy) / min(cx, cy)).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val cx = size.width / 2f; val cy = size.height / 2f
                                val dx = change.position.x - cx; val dy = change.position.y - cy
                                hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
                                sat = (hypot(dx, dy) / min(cx, cy)).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Canvas(Modifier.size(220.dp)) {
                        val r = size.minDimension / 2
                        val hues = (0..12).map { Color.hsv((it * 30f) % 360f, 1f, 1f) }
                        drawCircle(Brush.sweepGradient(hues, center), r)
                        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r)
                        drawCircle(Color.Black.copy(alpha = 1f - value), r)
                        val a = Math.toRadians(hue.toDouble())
                        val m = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
                        drawCircle(Color.White, 11.dp.toPx(), m, style = Stroke(3.dp.toPx()))
                        drawCircle(Color.Black, 13.dp.toPx(), m, style = Stroke(1.dp.toPx()))
                    }
                }
                Text("Brightness", fontSize = 13.sp, color = C.muted)
                Slider(
                    value = value, onValueChange = { value = it }, valueRange = 0.2f..1f,
                    colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(color).border(2.dp, C.line, CircleShape))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            hex = t
                            val clean = t.trim().removePrefix("#")
                            if (clean.length == 6) clean.toLongOrNull(16)?.let { v ->
                                val out = FloatArray(3)
                                android.graphics.Color.colorToHSV((0xFF000000 or v).toInt(), out)
                                hue = out[0]; sat = out[1]; value = out[2].coerceAtLeast(0.2f)
                            }
                        },
                        label = { Text("Hex code") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = C.text, unfocusedTextColor = C.text,
                            focusedBorderColor = C.teal, unfocusedBorderColor = C.line
                        ),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Use this color", color = C.teal) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.muted) } }
    )
}
