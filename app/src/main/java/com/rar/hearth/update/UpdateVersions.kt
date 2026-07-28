package com.rar.hearth.update

/**
 * Release-tag arithmetic. Pure Kotlin (no Android imports) so it unit-tests on the JVM.
 *
 * Tags are exactly `v<major>.<minor>.<versionCode>` -- the last component is the app's
 * versionCode, which is the commit count and therefore monotonic on a linear master.
 * versionName is deliberately NOT used for ordering: its `+<sha>` suffix makes it unordered.
 */
private val TAG_RE = Regex("""^v\d+\.\d+\.(\d+)$""")

/** The versionCode encoded in [tag], or null if the tag is not one we recognise. */
fun parseTagVersionCode(tag: String): Int? =
    TAG_RE.find(tag)?.groupValues?.get(1)?.toIntOrNull()

/**
 * True when [latestCode] is worth installing over [currentCode].
 *
 * A `.dirty` build carries the versionCode of the commit it was built from but is not that
 * build, so at an equal code the release is still worth offering. Dirty never justifies a
 * downgrade -- Android would refuse the install anyway.
 */
fun updateAvailable(currentCode: Int, latestCode: Int, currentIsDirty: Boolean): Boolean =
    latestCode > currentCode || (latestCode == currentCode && currentIsDirty)
