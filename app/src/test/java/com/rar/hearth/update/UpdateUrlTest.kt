package com.rar.hearth.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlTest {

    @Test
    fun acceptsARealReleaseDownloadUrl() {
        assertTrue(isAllowedApkUrl(
            "https://github.com/RAR/hearth/releases/download/v0.2.514/hearth-v0.2.514.apk"))
    }

    @Test
    fun rejectsLookalikeHosts() {
        // The classic prefix-match bug: these all START with something that looks right.
        assertFalse(isAllowedApkUrl(
            "https://github.com.evil.example/RAR/hearth/releases/download/v1/x.apk"))
        assertFalse(isAllowedApkUrl(
            "https://evil.example/https://github.com/RAR/hearth/releases/download/v1/x.apk"))
        assertFalse(isAllowedApkUrl(
            "https://github.com@evil.example/RAR/hearth/releases/download/v1/x.apk"))
        assertFalse(isAllowedApkUrl(
            "https://notgithub.com/RAR/hearth/releases/download/v1/x.apk"))
    }

    @Test
    fun rejectsTheWrongRepoEvenOnTheRightHost() {
        assertFalse(isAllowedApkUrl(
            "https://github.com/someoneelse/evil/releases/download/v1/x.apk"))
        assertFalse(isAllowedApkUrl(
            "https://github.com/RAR/otherrepo/releases/download/v1/x.apk"))
    }

    @Test
    fun rejectsPlaintextAndOtherSchemes() {
        assertFalse(isAllowedApkUrl(
            "http://github.com/RAR/hearth/releases/download/v0.2.514/hearth.apk"))
        assertFalse(isAllowedApkUrl(
            "file:///data/local/tmp/evil.apk"))
        assertFalse(isAllowedApkUrl(
            "ftp://github.com/RAR/hearth/releases/download/v1/x.apk"))
    }

    @Test
    fun rejectsPathTraversalOutOfTheReleasesArea() {
        assertFalse(isAllowedApkUrl(
            "https://github.com/RAR/hearth/releases/download/../../../evil.apk"))
        assertFalse(isAllowedApkUrl(
            "https://github.com/RAR/hearth/releases/download/v1/..%2f..%2fevil.apk"))
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(isAllowedApkUrl(""))
        assertFalse(isAllowedApkUrl("   "))
        assertFalse(isAllowedApkUrl("not a url"))
        // Right prefix, but not an APK.
        assertFalse(isAllowedApkUrl(
            "https://github.com/RAR/hearth/releases/download/v0.2.514/notes.txt"))
    }
}
