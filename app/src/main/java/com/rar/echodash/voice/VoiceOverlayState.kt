package com.rar.echodash.voice

/** UI-facing phase of the bottom-center voice pill. Pure data; read by the Compose overlay. */
enum class VoiceOverlayPhase { HIDDEN, LISTENING, TRANSCRIPT, THINKING, RESPONSE, FAILED }

data class VoiceOverlayState(
    val phase: VoiceOverlayPhase = VoiceOverlayPhase.HIDDEN,
    val text: String = "",
)
