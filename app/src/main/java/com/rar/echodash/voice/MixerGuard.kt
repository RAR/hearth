package com.rar.echodash.voice

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reapplies the Echo codec's mic gain on every voice-stack start. The MTK boards boot with
 * the capture PGA starved (speech at ~2-5% healthy amplitude — the long-standing wake-word
 * inconsistency) and ALSA mixer state resets on reboot. Needs the audio GID, granted via the
 * one-time platform.xml unlock (MODIFY_AUDIO_SETTINGS -> audio) documented in the spec;
 * without it tinymix fails and we log-and-continue at HAL gain.
 */
object MixerGuard {
    /** Tuned on-device 2026-07-17: default 40 = starved, 80 = analog clipping. */
    const val MICPGA_TARGET = 64

    private const val TAG = "MixerGuard"
    private const val TINYMIX = "/system/bin/tinymix"

    /**
     * Pure decision core: which mixer commands to run. Empty unless tinymix exists and this
     * is an MTK Echo board ("mt81xx" hardware) — the Tab M9 and anything else no-op.
     */
    fun mixerCommands(tinymixExists: Boolean, hardware: String): List<List<String>> {
        if (!tinymixExists || !hardware.lowercase().contains("mt81")) return emptyList()
        return listOf(
            listOf(TINYMIX, "ADC_A MICPGA Volume Ctrl", MICPGA_TARGET.toString(), MICPGA_TARGET.toString()),
        )
    }

    /** Run the commands for this device, soft-failing with one log line either way. */
    fun apply() {
        val commands = mixerCommands(File(TINYMIX).exists(), android.os.Build.HARDWARE)
        if (commands.isEmpty()) {
            Log.d(TAG, "skipped (not this hardware)")
            return
        }
        for (cmd in commands) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    Log.w(TAG, "timed out: ${cmd[1]}")
                    continue
                }
                if (p.exitValue() == 0) {
                    Log.i(TAG, "applied ${cmd[1]} = ${cmd[2]}")
                } else {
                    Log.w(TAG, "failed (exit=${p.exitValue()}): ${cmd[1]}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed: ${cmd[1]}", e)
            }
        }
    }
}
