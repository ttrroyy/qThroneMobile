package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.Outbound

/**
 * The storage layer's lookup of a stored profile by id (ProfilesRepo::GetProfile): the parsed outbound of the row,
 * or null when no such profile exists. The generator never mutates what it gets back, except for the transient
 * bridge fields of a `Custom` xrayfullconfig hop (bridgePort / bridgeAuth, custom.h:19-21) and the Normalize() clamps
 * of a started auto-selector (autoselector.h:140-167, which the desktop applies before every build too).
 */
fun interface ProfileProvider {
    fun get(id: Long): Outbound?
}
