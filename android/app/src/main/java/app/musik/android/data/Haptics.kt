package app.musik.android.data

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/** Short vibrations for like / unlike / dislike; switched off in Profile → Вибрация. */
object Haptics {
    private fun vibrator(context: Context): Vibrator? = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }

    /** Two beats in time with the heart pulse: strong at once, softer 0.36 s later. */
    fun like(context: Context, settings: Settings) {
        if (!settings.haptics) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 28, 332, 24), intArrayOf(0, 255, 0, 180), -1)
        runCatching { vibrator(context)?.vibrate(effect) }
    }

    /** A crisp press: play, pause, skip, radio. */
    fun press(context: Context, settings: Settings) {
        if (!settings.haptics) return
        runCatching { vibrator(context)?.vibrate(VibrationEffect.createOneShot(16, 140)) }
    }

    /** One light tap: unlike, dislike, undislike. */
    fun tick(context: Context, settings: Settings) {
        if (!settings.haptics) return
        runCatching { vibrator(context)?.vibrate(VibrationEffect.createOneShot(18, 90)) }
    }
}
