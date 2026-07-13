package com.rar.echodash.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtUrlTest {

    @Test
    fun relativeIsPrefixedWithBase() {
        assertEquals("http://ha:8123/api/x", resolveArtUrl("/api/x", "http://ha:8123"))
        // trailing slash on base is trimmed, not doubled
        assertEquals("http://ha:8123/api/x", resolveArtUrl("/api/x", "http://ha:8123/"))
    }

    @Test
    fun relativeWithoutLeadingSlashStillJoins() {
        assertEquals("http://ha:8123/x.jpg", resolveArtUrl("x.jpg", "http://ha:8123"))
    }

    @Test
    fun absoluteHttpPassesThrough() {
        assertEquals("http://cdn/a.jpg", resolveArtUrl("http://cdn/a.jpg", "http://ha:8123"))
        assertEquals("https://cdn/a.jpg", resolveArtUrl("https://cdn/a.jpg", null))
    }

    @Test
    fun nullOrBlankRawReturnsNull() {
        assertNull(resolveArtUrl(null, "http://ha:8123"))
        assertNull(resolveArtUrl("   ", "http://ha:8123"))
    }

    @Test
    fun relativeWithNullBaseReturnsNull() {
        assertNull(resolveArtUrl("/api/x", null))
    }
}
