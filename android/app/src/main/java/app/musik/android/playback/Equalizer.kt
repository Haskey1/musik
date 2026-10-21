package app.musik.android.playback

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.musik.android.data.Settings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Six-band equalizer, the same bands and presets as the iOS app. Audio passes through
 * [EqualizerProcessor] inside ExoPlayer's audio sink, so it works for streams and
 * saved files alike. Settings are kept in SharedPreferences.
 */
class Equalizer(private val settings: Settings) {
    data class Preset(val id: String, val name: String, val gains: List<Double>)

    companion object {
        /** Band centres, Hz — the same as Yandex Music's equalizer. */
        val bands = listOf(60.0, 150.0, 400.0, 1_000.0, 2_400.0, 15_000.0)
        const val MIN_DB = -12.0
        const val MAX_DB = 12.0
        const val CUSTOM = "custom"

        val presets = listOf(
            Preset("flat", "По умолчанию", listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
            Preset("classical", "Классическая музыка", listOf(3.0, 2.0, 0.0, 0.0, 2.0, 4.0)),
            Preset("club", "Клубная музыка", listOf(4.0, 3.0, 1.0, 0.0, 2.0, 3.0)),
            Preset("dance", "Танцевальная музыка", listOf(6.0, 4.0, 0.0, -1.0, 2.0, 3.0)),
            Preset("bass", "Усиление НЧ", listOf(7.0, 5.0, 2.0, 0.0, 0.0, 0.0)),
            Preset("bassTreble", "Усиление НЧ и ВЧ", listOf(6.0, 4.0, 0.0, -1.0, 3.0, 6.0)),
            Preset("treble", "Усиление ВЧ", listOf(0.0, 0.0, 0.0, 1.0, 4.0, 7.0)),
            Preset("laptop", "Колонки ноутбука", listOf(5.0, 4.0, 1.0, 0.0, 2.0, 3.0)),
            Preset("headphones", "Наушники", listOf(3.0, 2.0, -1.0, 0.0, 2.0, 4.0)),
            Preset("vocal", "Вокал", listOf(-2.0, -1.0, 2.0, 4.0, 3.0, 0.0)),
            Preset("rock", "Рок", listOf(4.0, 2.0, -2.0, -1.0, 2.0, 4.0)),
            Preset("hiphop", "Хип-хоп", listOf(6.0, 4.0, 0.0, 1.0, -1.0, 2.0)),
        )

        /** Gains as the audio thread sees them; `version` tells processors to rebuild filters. */
        @Volatile
        var snapshot = Snapshot(false, List(bands.size) { 0.0 }, 0)
            private set

        private fun publish(enabled: Boolean, gains: List<Double>) {
            snapshot = Snapshot(enabled, gains, snapshot.version + 1)
        }
    }

    data class Snapshot(val enabled: Boolean, val gains: List<Double>, val version: Int)

    var enabled by mutableStateOf(settings.getBoolean("eq.enabled", false))
        private set
    var gains by mutableStateOf(
        settings.getString("eq.gains")?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == bands.size } ?: List(bands.size) { 0.0 },
    )
        private set
    var presetId by mutableStateOf(settings.getString("eq.preset") ?: "flat")
        private set

    init {
        publish(enabled, gains)
    }

    fun updateEnabled(on: Boolean) {
        enabled = on
        save()
    }

    fun apply(preset: Preset) {
        gains = preset.gains
        presetId = preset.id
        enabled = true
        save()
    }

    /** Dragging a point: the setting becomes «Своя настройка». */
    fun setGain(value: Double, band: Int) {
        if (band !in gains.indices) return
        val v = ((value * 2).roundToInt() / 2.0).coerceIn(MIN_DB, MAX_DB) // half-dB steps
        if (v == gains[band]) return
        gains = gains.toMutableList().also { it[band] = v }
        presetId = CUSTOM
        enabled = true
        save()
    }

    private fun save() {
        settings.putBoolean("eq.enabled", enabled)
        settings.putString("eq.gains", gains.joinToString(","))
        settings.putString("eq.preset", presetId)
        publish(enabled, gains)
    }
}

/** RBJ "Audio EQ Cookbook" biquads, normalised: [b0, b1, b2, a1, a2]. */
object Biquad {
    enum class Kind { Peak, LowShelf, HighShelf }

    fun kindOf(band: Int) = when (band) {
        0 -> Kind.LowShelf
        Equalizer.bands.size - 1 -> Kind.HighShelf
        else -> Kind.Peak
    }

    fun coefficients(kind: Kind, frequency: Double, gainDb: Double, q: Double, sampleRate: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * frequency / sampleRate
        val cosw = cos(w0)
        val sinw = sin(w0)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (kind) {
            Kind.Peak -> {
                val alpha = sinw / (2 * q)
                b0 = 1 + alpha * a; b1 = -2 * cosw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cosw; a2 = 1 - alpha / a
            }
            Kind.LowShelf -> {
                val alpha = sinw / 2 * sqrt(2.0)
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cosw + s)
                b1 = 2 * a * ((a - 1) - (a + 1) * cosw)
                b2 = a * ((a + 1) - (a - 1) * cosw - s)
                a0 = (a + 1) + (a - 1) * cosw + s
                a1 = -2 * ((a - 1) + (a + 1) * cosw)
                a2 = (a + 1) + (a - 1) * cosw - s
            }
            Kind.HighShelf -> {
                val alpha = sinw / 2 * sqrt(2.0)
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cosw + s)
                b1 = -2 * a * ((a - 1) + (a + 1) * cosw)
                b2 = a * ((a + 1) + (a - 1) * cosw - s)
                a0 = (a + 1) - (a - 1) * cosw + s
                a1 = 2 * ((a - 1) - (a + 1) * cosw)
                a2 = (a + 1) - (a - 1) * cosw - s
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Gain in dB of the whole cascade at `frequency` — for drawing the curve. */
    fun response(gains: List<Double>, frequency: Double, sampleRate: Double = 44_100.0): Double {
        var total = 0.0
        val w = 2 * PI * frequency / sampleRate
        fun mag2(p0: Double, p1: Double, p2: Double): Double {
            val re = p0 + p1 * cos(w) + p2 * cos(2 * w)
            val im = -(p1 * sin(w) + p2 * sin(2 * w))
            return re * re + im * im
        }
        Equalizer.bands.forEachIndexed { i, f0 ->
            val c = coefficients(kindOf(i), f0, gains[i], 1.0, sampleRate)
            total += 10 * log10(mag2(c[0], c[1], c[2]) / mag2(1.0, c[3], c[4]))
        }
        return total
    }
}

/**
 * The equalizer inside ExoPlayer's audio sink: 16-bit or float PCM passes through a
 * cascade of six biquads per channel (transposed direct form II). While the equalizer
 * is off (or flat) audio is copied through untouched.
 */
@OptIn(UnstableApi::class)
class EqualizerProcessor : BaseAudioProcessor() {
    private var sampleRate = 44_100.0
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var version = -1
    private var active = false
    private var coeffs = Array(Equalizer.bands.size) { DoubleArray(5) }
    private var z1 = Array(2) { DoubleArray(Equalizer.bands.size) }
    private var z2 = Array(2) { DoubleArray(Equalizer.bands.size) }
    private var preamp = 1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate.toDouble()
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        z1 = Array(channels) { DoubleArray(Equalizer.bands.size) }
        z2 = Array(channels) { DoubleArray(Equalizer.bands.size) }
        version = -1
        return inputAudioFormat
    }

    private fun rebuildIfNeeded() {
        val s = Equalizer.snapshot
        if (s.version == version) return
        version = s.version
        val wasActive = active
        active = s.enabled && s.gains.any { kotlin.math.abs(it) >= 0.25 }
        if (!active) return
        coeffs = Array(Equalizer.bands.size) { i ->
            val f = min(Equalizer.bands[i], sampleRate * 0.45)
            Biquad.coefficients(Biquad.kindOf(i), f, s.gains[i], 1.0, sampleRate)
        }
        if (!wasActive) {
            z1.forEach { it.fill(0.0) }
            z2.forEach { it.fill(0.0) }
        }
        // leave headroom for boosts so loud tracks do not clip
        preamp = 10.0.pow(-max(s.gains.maxOrNull() ?: 0.0, 0.0) / 20)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        rebuildIfNeeded()
        val out = replaceOutputBuffer(remaining)
        if (!active) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val bands = coeffs.size
        var ch = 0
        if (encoding == C.ENCODING_PCM_16BIT) {
            val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            while (input.remaining() >= 2) {
                var x = input.short / 32768.0 * preamp
                x = filter(x, ch, bands)
                val v = (x * 32767.0).coerceIn(-32768.0, 32767.0)
                out.putShort(v.roundToInt().toShort())
                ch = if (ch + 1 == channels) 0 else ch + 1
            }
        } else {
            val input = inputBuffer.order(ByteOrder.nativeOrder())
            while (input.remaining() >= 4) {
                var x = input.float * preamp
                x = filter(x, ch, bands)
                out.putFloat(x.coerceIn(-1.0, 1.0).toFloat())
                ch = if (ch + 1 == channels) 0 else ch + 1
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun filter(sample: Double, ch: Int, bands: Int): Double {
        var x = sample
        val s1 = z1[ch]
        val s2 = z2[ch]
        for (b in 0 until bands) {
            val c = coeffs[b]
            val y = c[0] * x + s1[b]
            s1[b] = c[1] * x - c[3] * y + s2[b]
            s2[b] = c[2] * x - c[4] * y
            x = y
        }
        return x
    }

    override fun onFlush() {
        z1.forEach { it.fill(0.0) }
        z2.forEach { it.fill(0.0) }
    }
}
