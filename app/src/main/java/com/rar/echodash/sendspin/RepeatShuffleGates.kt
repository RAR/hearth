package com.rar.echodash.sendspin

/** Which of the repeat/shuffle toggles the server's advertised command set allows. */
data class RepeatShuffleGates(val canRepeat: Boolean, val canShuffle: Boolean)

/**
 * Derive per-toggle visibility from the SendSpin controller's advertised `supported_commands`.
 * A null set (server never sent one) is optimistic -- both true, matching the engine-side drop
 * guard, which also passes unknown sets through. Otherwise canRepeat requires any repeat_*
 * command and canShuffle requires shuffle or unshuffle. Kept next to [SendspinEndpoint], its only
 * consumer, as a pure unit-testable fn (mirrors PlaybackIntent).
 */
fun repeatShuffleGates(supported: List<String>?): RepeatShuffleGates {
    if (supported == null) return RepeatShuffleGates(canRepeat = true, canShuffle = true)
    val canRepeat = supported.any { it == "repeat_off" || it == "repeat_one" || it == "repeat_all" }
    val canShuffle = supported.any { it == "shuffle" || it == "unshuffle" }
    return RepeatShuffleGates(canRepeat, canShuffle)
}
