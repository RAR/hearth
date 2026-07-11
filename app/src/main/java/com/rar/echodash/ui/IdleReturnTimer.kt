package com.rar.echodash.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After [timeoutMs] with no interaction on a non-Home view, invokes [onReturnHome]. Home is exempt.
 * Confined to [scope]'s dispatcher; callers hop into that scope. Plain, testable — not in composables.
 */
class IdleReturnTimer(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 60_000,
    private val onReturnHome: () -> Unit,
) {
    private var onHome = true
    private var job: Job? = null

    fun onViewChanged(isHome: Boolean) {
        onHome = isHome
        if (isHome) cancel() else arm()
    }

    fun onInteraction() {
        if (!onHome) arm()
    }

    private fun arm() {
        job?.cancel()
        job = scope.launch {
            delay(timeoutMs)
            onReturnHome()
        }
    }

    fun cancel() { job?.cancel(); job = null }
}
