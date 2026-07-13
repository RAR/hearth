package com.rar.echodash.voice

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure-JVM synthesizer for the timer-alarm presets. [render] returns ONE full cycle
 * (audible portion followed by the trailing silence gap) of 16-bit mono PCM at [rate] Hz, so a
 * player can loop the single buffer with a gap between repeats, plus one-shot wake/done
 * acknowledgment chirps ([earcon]). No Android imports, so this is unit-testable. Playback
 * (AudioTrack) lives in TimerChime.
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

    /**
     * One-shot voice acknowledgment chirps (no trailing gap — these are not looping alarm
     * cycles). "wake" rises (660→880 Hz), "done" falls (880→660 Hz), "preview" is
     * wake + 150 ms silence + done for the config page. Unknown kinds fall back to "wake".
     */
    fun earcon(kind: String, volume: Int, rate: Int): ShortArray {
        val amp = (volume / 100.0) * 0.6 * Short.MAX_VALUE
        return when (kind) {
            "done" -> chirp(amp, rate, 880.0 to 100, 660.0 to 120)
            "preview" -> earcon("wake", volume, rate) +
                ShortArray(rate * 150 / 1000) +
                earcon("done", volume, rate)
            else -> chirp(amp, rate, 660.0 to 130, 880.0 to 150) // "wake" and any unknown value
        }
    }

    /** Two consecutive notes, each (frequency Hz to duration ms), with 8 ms linear ramps. */
    private fun chirp(amp: Double, rate: Int, first: Pair<Double, Int>, second: Pair<Double, Int>): ShortArray {
        val ramp = rate * 8 / 1000
        fun note(freq: Double, ms: Int): ShortArray {
            val n = rate * ms / 1000
            return ShortArray(n) { i ->
                val env = minOf(1.0, i.toDouble() / ramp, (n - 1 - i).toDouble() / ramp)
                (sin(2 * PI * freq * i / rate) * amp * env).toInt().toShort()
            }
        }
        return note(first.first, first.second) + note(second.first, second.second)
    }
}
