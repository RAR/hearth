package com.rar.echodash.ui.model

import com.rar.echodash.sendspin.musicassistant.MaPlayer

/**
 * Rows for the Speakers pane: self pinned to the very top, then available players before
 * unavailable, A-Z (case-insensitive) within each tier. Stable for a self that isn't in the
 * list (no pin, just the availability + A-Z ordering).
 */
fun speakerRows(players: List<MaPlayer>, selfId: String): List<MaPlayer> =
    players.sortedWith(
        compareByDescending<MaPlayer> { it.playerId == selfId } // self (true) sorts first
            .thenByDescending { it.available }                  // available (true) before not
            .thenBy { it.name.lowercase() },                    // A-Z within tier
    )

/**
 * True when [player] shares a sync group with [self], in either leadership direction: player is
 * synced to self, self is synced to player, or either lists the other in its group members
 * (a dedicated/virtual group). False when [self] is null or [player] IS self — a player is not
 * "grouped with itself" for the pane's Ungroup affordance (and MA lists a leader's own id in
 * its group_members, which this short-circuit avoids mis-reading).
 */
fun inGroupWithSelf(player: MaPlayer, self: MaPlayer?): Boolean {
    if (self == null || player.playerId == self.playerId) return false
    return player.syncedTo == self.playerId ||
        self.syncedTo == player.playerId ||
        self.groupMembers.contains(player.playerId) ||
        player.groupMembers.contains(self.playerId)
}

/**
 * True when the "Group with me" action may be offered for [player]. Exclusions first: never self,
 * never an unavailable player, never one already grouped with self. Then a permissive heuristic
 * over [MaPlayer.canGroupWith] — the server enforces the real rule, so we err toward showing the
 * chip and let an illegal join surface as the pane's error toast:
 *  - EMPTY canGroupWith → true (MA omits the field for unrestricted players).
 *  - self's player id present → true (explicit allow).
 *  - any entry that is NOT one of [allPlayerIds] (the currently-listed players) → true: such an
 *    entry is a provider-instance grant covering that provider's players, which is commonly how
 *    MA expresses "groupable" rather than an individual player id.
 *  - otherwise → false: a pure allowlist of other players' ids that excludes us.
 * ([allPlayerIds] = the ids the pane is showing; live-verify item 5.)
 */
fun canOfferGroup(player: MaPlayer, self: MaPlayer?, allPlayerIds: Set<String>): Boolean {
    if (self == null || player.playerId == self.playerId) return false
    if (!player.available) return false
    if (inGroupWithSelf(player, self)) return false
    val grants = player.canGroupWith
    if (grants.isEmpty()) return true
    if (grants.contains(self.playerId)) return true
    // A grant that isn't a listed player id is a provider-instance grant → permissive.
    return grants.any { it !in allPlayerIds }
}
