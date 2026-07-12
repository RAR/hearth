package com.rar.echodash.web

import kotlin.random.Random

/** A 6-digit PIN, zero-padded (so "000123" is valid). Generated once and persisted in app prefs. */
fun generatePin(random: Random = Random.Default): String =
    "%06d".format(random.nextInt(0, 1_000_000))
