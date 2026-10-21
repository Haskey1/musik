package app.musik.android.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musik.android.data.Lyrics
import app.musik.android.playback.Biquad
import app.musik.android.playback.Equalizer
import app.musik.android.ui.App
import app.musik.android.ui.ChipButton
import app.musik.android.ui.EmptyHint
import app.musik.android.ui.MusikColors
import kotlin.math.exp
import kotlin.math.ln

/** Plain or time-synced (LRC) lyrics; the synced line under the playhead is highlighted. */
@Composable
fun LyricsPane(trackId: Int) {
    val player = App.player
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var lines by remember { mutableStateOf<List<Pair<Double, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(trackId) {
        loading = true
        lyrics = runCatching { App.api.lyrics(trackId) }.getOrNull()
        lines = parseLrc(lyrics?.syncedLyrics ?: "")
        loading = false
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = Color.White)
            lines.isNotEmpty() -> {
                val pos = player.clock.position
                val active = lines.indexOfLast { it.first <= pos + 0.3 }
                lines.forEachIndexed { i, (time, text) ->
                    Text(
                        text.ifEmpty { "♪" },
                        Modifier.clickable { player.seek(time) },
                        color = if (i == active) Color.White else Color.White.copy(alpha = 0.6f),
                        fontSize = 20.sp,
                        fontWeight = if (i == active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            !lyrics?.plainLyrics.isNullOrBlank() -> Text(lyrics?.plainLyrics ?: "", color = Color.White.copy(alpha = 0.9f))
            else -> EmptyHint(if (lyrics?.status == "instrumental") "Инструментал" else "Текста нет")
        }
    }
}

private val lrcTag = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")

fun parseLrc(text: String): List<Pair<Double, String>> {
    val out = mutableListOf<Pair<Double, String>>()
    for (raw in text.lines()) {
        val matches = lrcTag.findAll(raw).toList()
        val last = matches.lastOrNull() ?: continue
        val body = raw.substring(last.range.last + 1).trim()
        for (m in matches) {
            val minutes = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val sec = m.groupValues[2].toDoubleOrNull() ?: 0.0
            out += (minutes * 60 + sec) to body
        }
    }
    return out.sortedBy { it.first }
}

/** Six bands as draggable points on the response curve, presets below. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheet(onDismiss: () -> Unit) {
    val eq = App.equalizer
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MusikColors.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Эквалайзер", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val name = Equalizer.presets.firstOrNull { it.id == eq.presetId }?.name ?: "Своя настройка"
                    Text(if (eq.enabled) name else "выключен", color = MusikColors.muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = eq.enabled,
                    onCheckedChange = { eq.updateEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = MusikColors.accent, checkedThumbColor = Color.White),
                )
            }
            EqCurve(eq)
            Row(Modifier.fillMaxWidth()) {
                listOf("60", "150", "400", "1к", "2.4к", "15к").forEach {
                    Text(it, Modifier.weight(1f), color = MusikColors.muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Equalizer.presets.forEach { p ->
                    ChipButton(onClick = { eq.apply(p) }, active = eq.enabled && eq.presetId == p.id) { Text(p.name) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EqCurve(eq: Equalizer) {
    val n = Equalizer.bands.size
    val range = Equalizer.MAX_DB - Equalizer.MIN_DB
    var dragging by remember { mutableIntStateOf(-1) }
    val accent = if (eq.enabled) MusikColors.accent else MusikColors.muted
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(Unit) {
                fun band(x: Float) = (x / size.width * n).toInt().coerceIn(0, n - 1)
                fun gain(y: Float) = Equalizer.MAX_DB - (y / size.height).coerceIn(0f, 1f) * range
                detectDragGestures(
                    onDragStart = { pos -> dragging = band(pos.x); eq.setGain(gain(pos.y), dragging) },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                ) { change, _ ->
                    change.consume()
                    if (dragging >= 0) eq.setGain(gain(change.position.y), dragging)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val b = (pos.x / size.width * n).toInt().coerceIn(0, n - 1)
                    eq.setGain(Equalizer.MAX_DB - (pos.y / size.height).coerceIn(0f, 1f) * range, b)
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val w = size.width
            val h = size.height
            fun yOf(db: Double) = ((Equalizer.MAX_DB - db) / range * h).toFloat()
            // grid: 0 dB and ±6 dB
            for (db in listOf(-6.0, 0.0, 6.0)) {
                drawLine(
                    Color.White.copy(alpha = if (db == 0.0) 0.18f else 0.08f), Offset(0f, yOf(db)), Offset(w, yOf(db)),
                    strokeWidth = 1.dp.toPx(), pathEffect = if (db == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            val logs = Equalizer.bands.map { ln(it) }
            fun freqAt(x: Float): Double {
                val t = (x / w * n - 0.5).toDouble()
                val i = t.toInt().coerceIn(0, n - 2)
                val frac = t - i
                return exp(logs[i] + (logs[i + 1] - logs[i]) * frac).coerceIn(20.0, 20_000.0)
            }
            val path = Path()
            val steps = 120
            for (s in 0..steps) {
                val x = w * s / steps
                val db = Biquad.response(eq.gains, freqAt(x)).coerceIn(Equalizer.MIN_DB, Equalizer.MAX_DB)
                if (s == 0) path.moveTo(x, yOf(db)) else path.lineTo(x, yOf(db))
            }
            drawPath(path, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            eq.gains.forEachIndexed { i, g ->
                val c = Offset(w * (i + 0.5f) / n, yOf(g))
                drawCircle(Color.White, radius = if (i == dragging) 11.dp.toPx() else 8.dp.toPx(), center = c)
                drawCircle(accent, radius = if (i == dragging) 6.dp.toPx() else 4.dp.toPx(), center = c)
            }
        }
    }
}
