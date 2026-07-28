package com.rar.hearth.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionsTest {

    @Test
    fun parsesTheVersionCodeOutOfAWellFormedTag() {
        assertEquals(514, parseTagVersionCode("v0.2.514"))
        assertEquals(1, parseTagVersionCode("v0.2.1"))
        assertEquals(12345, parseTagVersionCode("v1.10.12345"))
    }

    @Test
    fun rejectsMalformedTagsRatherThanGuessing() {
        // A tag we cannot read must be "unknown", never 0 -- 0 would read as
        // "older than everything" and wrongly enable the button.
        assertNull(parseTagVersionCode(""))
        assertNull(parseTagVersionCode("v0.2"))            // no versionCode component
        assertNull(parseTagVersionCode("0.2.514"))         // missing the v prefix
        assertNull(parseTagVersionCode("v0.2.x"))          // non-numeric
        assertNull(parseTagVersionCode("release-514"))
        assertNull(parseTagVersionCode("v0.2.514-rc1"))    // suffix we do not define
        assertNull(parseTagVersionCode("v0.2.-5"))         // negative
    }

    @Test
    fun offersAnUpdateOnlyWhenTheReleaseIsNewer() {
        assertTrue(updateAvailable(currentCode = 513, latestCode = 514, currentIsDirty = false))
        assertFalse(updateAvailable(currentCode = 514, latestCode = 514, currentIsDirty = false))
        assertFalse(updateAvailable(currentCode = 515, latestCode = 514, currentIsDirty = false))
    }

    @Test
    fun aDirtyBuildIsOfferedTheReleaseAtItsOwnVersionCode() {
        // A .dirty build at 514 is NOT the 514 release -- it is uncommitted local
        // work. Offering it is the way back onto a clean build.
        assertTrue(updateAvailable(currentCode = 514, latestCode = 514, currentIsDirty = true))
        // But dirty never justifies going backwards.
        assertFalse(updateAvailable(currentCode = 515, latestCode = 514, currentIsDirty = true))
    }
}
