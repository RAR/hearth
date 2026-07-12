package com.rar.echodash.voice

/** One on-screen countdown chip. [remainingSec] is already resolved against the clock. */
data class TimerChip(val id: String, val name: String, val remainingSec: Long, val active: Boolean)

/** Full-attention "Timer done" alert. [label] is the timer name, or "Timer" if unnamed. */
data class TimerAlert(val label: String)

data class TimersUiState(val chips: List<TimerChip> = emptyList(), val alert: TimerAlert? = null)
