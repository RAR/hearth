package com.rar.echodash.voice

/**
 * Maps timer-alarm tone keys to bundled ogg assets. The 7 system alarms play through MediaPlayer;
 * the 4 synthesized tones (twotone/beeps/chime/trill) have no asset and return null so the caller
 * falls back to [ToneGenerator]/AudioTrack. Pure/plain-JVM: no Android types, unit-testable.
 */
object TimerSounds {
    private val ASSETS: Map<String, String> = mapOf(
        "argon" to "sounds/alarm_argon.ogg",
        "oxygen" to "sounds/alarm_oxygen.ogg",
        "krypton" to "sounds/alarm_krypton.ogg",
        "timer" to "sounds/alarm_timer.ogg",
        "beep" to "sounds/alarm_beep.ogg",
        "helium" to "sounds/alarm_helium.ogg",
        "cyan" to "sounds/alarm_cyan.ogg",
    )

    /** Asset path under assets/ for [tone], or null for a synthesized tone / unknown key. */
    fun assetPath(tone: String): String? = ASSETS[tone]
}
