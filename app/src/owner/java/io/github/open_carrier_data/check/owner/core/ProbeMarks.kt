package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.UserPlaneClass
import kotlinx.serialization.Serializable

/*
 * OCD rule round 42, item 2: the owner/rom build's record of when the mobile-data probe ladder last
 * ran for a SIM, so an automatic run keeps to "at most once per SIM per 6 hours" ([ProbePolicy]).
 * It keeps a time and a class only, never an address.
 */
@Serializable
data class ProbeRunMark(val sim: SimKey, val atMs: Long, val userPlane: UserPlaneClass) {
    companion object {
        const val MAX = 8

        fun last(marks: List<ProbeRunMark>, sim: SimKey): ProbeRunMark? = marks.lastOrNull { it.sim.sameCard(sim) }

        fun add(marks: List<ProbeRunMark>, mark: ProbeRunMark): List<ProbeRunMark> =
            (marks.filterNot { it.sim.sameCard(mark.sim) } + mark).takeLast(MAX)
    }
}
