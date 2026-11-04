package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.CallDirection
import kotlinx.serialization.Serializable

/*
 * The real-call watch (OCD rule round 47, fix 5). Without test calls, a value that keeps IMS registered but
 * breaks calls would go unnoticed until someone needs to call, and VoiceLoss cannot see it: it needs IMS voice
 * lost, which is false while IMS stays registered. So the watch reads the calls the person makes anyway. It is
 * passive and dials nothing.
 *
 * It arms per SIM when an install changes a VOICE, WIFI_CALLING or SMS class value. A failure is an outgoing real
 * call that never went ACTIVE, with one call only, a fresh disconnect reading and a code on its own closed list.
 * Busy, not found, bad address, a rejection, forbidden, unwanted, no answer, the person's own hang-up and a
 * request this phone cancelled never count, so two wrong numbers or busy lines roll nothing back. A call that went
 * ACTIVE disarms the watch. Two failures ask for CarrierSettings's hold in a dataset step that keeps the Guard's
 * refusals, with no candidates: registration cannot judge a candidate here. No measured failure is written, no level
 * changes, and the split never runs on this hold. It ends on the person's Resume.
 */

/** One SIM's watch after an install that touched calls or SMS: the commit, since when, and the failures so far. */
@Serializable
data class CallWatchArm(val sim: SimKey, val commit: String, val armedAtMs: Long, val failures: Int = 0)

object RealCallWatch {
    /** Two failures, as round 28's noStreamTwice: one call alone can fail for a reason of its own. */
    const val FAILURES = 2

    val CLASSES = setOf(TestClass.VOICE, TestClass.WIFI_CALLING, TestClass.SMS)

    /**
     * The closed list, ImsReasonInfo codes: the network or the IMS path refused the setup itself.
     * 331 bad request, 334 not supported, 335 request timeout, 340 not acceptable, 342 client error, 343 transaction
     * does not exist, 351 server internal error, 352 service unavailable, 353 server timeout, 354 server error,
     * 362 global error, 401 to 404 media setup. Left out on purpose: 332 forbidden, 333 not found, 336 temporarily
     * unavailable, 337 bad address, 338 busy, 339 request cancelled, 341 not reachable, 361 user rejected.
     */
    val SETUP_FAILURES = setOf(331, 334, 335, 340, 342, 343, 351, 352, 353, 354, 362, 401, 402, 403, 404)

    /** Whether [record] counts as a failed call for the watch. */
    fun failed(record: CallRecord): Boolean {
        val end = record.disconnect ?: return false
        return record.method == CallMethod.REAL_CALL && record.direction == CallDirection.OUTGOING && !record.active &&
            !record.severalCalls && record.disconnectFresh == true && !end.byThisPhone && end.imsCode in SETUP_FAILURES
    }

    /** Arms [sim] for [commit]: a newer commit starts over, the same commit keeps its count. */
    fun arm(arms: List<CallWatchArm>, sim: SimKey, commit: String, nowMs: Long): List<CallWatchArm> {
        if (arms.any { it.sim.sameCard(sim) && it.commit == commit }) return arms
        return arms.filterNot { it.sim.sameCard(sim) } + CallWatchArm(sim, commit, nowMs)
    }

    /** The state after [record]: an ACTIVE call disarms its SIM; the second failure leaves the hold due. */
    fun after(state: OwnerState, record: CallRecord): OwnerState {
        val sim = record.sim ?: return state
        val arm = state.callWatches.firstOrNull { it.sim.sameCard(sim) } ?: return state
        val others = state.callWatches.filterNot { it === arm }
        return when {
            record.active -> state.copy(callWatches = others)
            !failed(record) -> state
            arm.failures + 1 >= FAILURES -> state.copy(callWatches = others, realCallHoldDue = true)
            else -> state.copy(callWatches = others + arm.copy(failures = arm.failures + 1))
        }
    }
}
