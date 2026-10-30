package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.EpdgLookup
import io.github.open_carrier_data.check.core.ProbeLadder
import io.github.open_carrier_data.check.core.ProbePolicy
import io.github.open_carrier_data.check.core.ProbeResults
import io.github.open_carrier_data.check.core.UserPlaneClass
import kotlinx.serialization.Serializable

/*
 * OCD rule round 42, item 2: the owner/rom build's record of when the mobile-data probe ladder last
 * ran for a SIM, so an automatic run keeps to "at most once per SIM per 6 hours" ([ProbePolicy]).
 * It keeps a time and a class only, never an address.
 */
@Serializable
data class ProbeRunMark(
    val sim: SimKey,
    val atMs: Long,
    val userPlane: UserPlaneClass,
    /** Each step's outcome (booleans and classes only, never an address); null in a mark of a build before 0.4.8. */
    val results: ProbeResults? = null,
    /** The owner's "check now" started it (not the automatic rule). */
    val checkNow: Boolean = false,
) {
    /** The log line of this run: who started it, the class and each step. */
    val line: String
        get() = "probe ladder (${if (checkNow) "check now" else "automatic"}): ${userPlane.plain}" +
            (results?.let { "; " + ProbeLadder.steps(it) } ?: "")

    companion object {
        const val MAX = 8

        fun last(marks: List<ProbeRunMark>, sim: SimKey): ProbeRunMark? = marks.lastOrNull { it.sim.sameCard(sim) }

        fun add(marks: List<ProbeRunMark>, mark: ProbeRunMark): List<ProbeRunMark> =
            (marks.filterNot { it.sim.sameCard(mark.sim) } + mark).takeLast(MAX)

        /**
         * The latest run for [sim] while it is under 6 hours old (and not from a clock that went back),
         * else null. The verdict reads it only while the measured failure lasts, so an hourly check between
         * two runs keeps the same verdict instead of flipping back and forth.
         */
        fun fresh(marks: List<ProbeRunMark>, sim: SimKey, nowMs: Long): ProbeRunMark? =
            last(marks, sim)?.takeIf { nowMs - it.atMs in 0 until ProbePolicy.AUTO_EVERY_MS }
    }
}

/**
 * OCD rule round 42, item 2, W1: when the ePDG name lookup last ran for a SIM and what each resolver
 * answered, as a class only ("resolves", "no address", "fails"; null where that resolver was not
 * asked), never an address. An automatic run keeps to once per SIM per 6 hours, and the verdict reads
 * the latest run while it is that fresh.
 */
@Serializable
data class EpdgRunMark(val sim: SimKey, val atMs: Long, val wifi: EpdgLookup? = null, val cellular: EpdgLookup? = null) {
    companion object {
        const val MAX = 8

        fun last(marks: List<EpdgRunMark>, sim: SimKey): EpdgRunMark? = marks.lastOrNull { it.sim.sameCard(sim) }

        fun add(marks: List<EpdgRunMark>, mark: EpdgRunMark): List<EpdgRunMark> =
            (marks.filterNot { it.sim.sameCard(mark.sim) } + mark).takeLast(MAX)

        /** The latest run for [sim] when it is under 6 hours old (and not from a clock that went back), else null. */
        fun fresh(marks: List<EpdgRunMark>, sim: SimKey, nowMs: Long): EpdgRunMark? =
            last(marks, sim)?.takeIf { nowMs - it.atMs in 0 until ProbePolicy.AUTO_EVERY_MS }
    }
}
