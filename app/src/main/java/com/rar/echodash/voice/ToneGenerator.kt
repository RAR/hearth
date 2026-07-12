package com.rar.echodash.voice

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure-JVM synthesizer for the timer-alarm presets. [render] returns ONE full cycle
 * (audible portion followed by the trailing silence gap) of 16-bit mono PCM at [rate] Hz, so a
 * player can loop the single buffer with a gap between repeats. No Android imports, so this is
 * unit-testable. Playback (AudioTrack) lives in TimerChime.
 *
 * Amplitude is (volume / 100) * 0.6 * Short.MAX_VALUE: volume 100 matches the historic fixed
 * 0.6-headroom loudness, volume 0 renders pure silence. Unknown tones fall back to "twotone".
 */
object ToneGenerator {

    fun render(tone: String, volume: Int, rate: Int): ShortArray {
        val amp = (volume / 100.0) * 0.6 * Short.MAX_VALUE
        return when (tone) {
            "beeps" -> beeps(amp, rate)
            "chime" -> chime(amp, rate)
            "trill" -> trill(amp, rate)
            else -> twotone(amp, rate) // "twotone" and any unknown value
        }
    }

    private fun twotone(amp: Double, rate: Int): ShortArray {
        val beep = rate * 200 / 1000 // 200 ms per beep
        val gap = rate               // ~1 s trailing silence
        val out = ShortArray(beep * 2 + gap)
        for (i in 0 until beep) {
            out[i] = (sin(2 * PI * 880.0 * i / rate) * amp).toInt().toShort()
            out[beep + i] = (sin(2 * PI * 1320.0 * i / rate) * amp).toInt().toShort()
        }
        return out
    }

    private fun beeps(amp: Double, rate: Int): ShortArray {
        val beep = rate * 120 / 1000 // 120 ms beep
        val gap = rate * 80 / 1000   // 80 ms between beeps
        val pause = rate             // ~1 s trailing silence
        val out = ShortArray(beep * 3 + gap * 2 + pause)
        for (b in 0 until 3) {
            val base = b * (beep + gap)
            for (i in 0 until beep) {
                out[base + i] = (sin(2 * PI * 1000.0 * i / rate) * amp).toInt().toShort()
            }
        }
        return out
    }

    private fun chime(amp: Double, rate: Int): ShortArray {
        val note = rate * 350 / 1000  // 350 ms per note
        val gap = rate * 1600 / 1000  // ~1.6 s trailing silence
        val out = ShortArray(note * 2 + gap)
        for (i in 0 until note) {
            val env = 1.0 - i.toDouble() / note // linear decay to 0 across each note
            out[i] = (sin(2 * PI * 1318.5 * i / rate) * amp * env).toInt().toShort()
            out[note + i] = (sin(2 * PI * 1046.5 * i / rate) * amp * env).toInt().toShort()
        }
        return out
    }

    private fun trill(amp: Double, rate: Int): ShortArray {
        val sound = rate            // 1 s of alternation
        val seg = rate * 60 / 1000  // 60 ms segments
        val gap = rate * 600 / 1000 // ~0.6 s trailing silence
        val out = ShortArray(sound + gap)
        for (i in 0 until sound) {
            val freq = if ((i / seg) % 2 == 0) 1400.0 else 1800.0
            out[i] = (sin(2 * PI * freq * i / rate) * amp).toInt().toShort()
        }
        return out
    }
}
