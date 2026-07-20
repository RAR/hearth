package com.rar.hearth.sendspin.musicassistant

import com.rar.hearth.sendspin.musicassistant.model.MaLibraryItem
import com.rar.hearth.sendspin.musicassistant.model.MaMediaType

/**
 * Represents a radio station from Music Assistant.
 *
 * Implements MaLibraryItem for use in unified adapters.
 */
data class MaRadio(
    val radioId: String,
    override val name: String,
    override val imageUri: String?,
    override val uri: String?,
    val provider: String?         // "tunein", "radiobrowser", etc.
) : MaLibraryItem {
    override val id: String get() = radioId
    override val mediaType: MaMediaType = MaMediaType.RADIO
}
