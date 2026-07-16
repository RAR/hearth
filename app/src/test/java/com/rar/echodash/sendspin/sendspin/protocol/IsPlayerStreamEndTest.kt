package com.rar.echodash.sendspin.sendspin.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the role-match extracted from [SendSpinProtocolHandler.handleStreamEnd] (437baec): a
 * `stream/end` concerns the player only when some role's BASE name (sans "@vN") equals the player's.
 */
class IsPlayerStreamEndTest {

    private val player = "player@v1" // == SendSpinProtocol.Roles.PLAYER

    @Test fun unversioned_player_matches_versioned_constant() {
        assertTrue(isPlayerStreamEnd(listOf("player"), player))
    }

    @Test fun versioned_player_matches() {
        assertTrue(isPlayerStreamEnd(listOf("player@v1"), player))
    }

    @Test fun other_major_version_still_matches_on_base() {
        assertTrue(isPlayerStreamEnd(listOf("player@v2"), player))
    }

    @Test fun controller_only_does_not_match() {
        assertFalse(isPlayerStreamEnd(listOf("controller"), player))
    }

    @Test fun mixed_roles_match_when_player_present() {
        assertTrue(isPlayerStreamEnd(listOf("controller@v1", "player"), player))
    }

    @Test fun null_roles_means_all_roles_act() {
        // No roles field -> "all roles" -> act. Pins current handleStreamEnd behavior.
        assertTrue(isPlayerStreamEnd(null, player))
    }

    @Test fun empty_roles_matches_nothing_and_is_ignored() {
        // Empty list matches no role -> ignore. Pins current handleStreamEnd behavior.
        assertFalse(isPlayerStreamEnd(emptyList(), player))
    }
}
