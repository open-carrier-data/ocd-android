package io.github.open_carrier_data.check.owner.core

/**
 * What a test needs after a boot, a SIM change or a restart, waited for as events with a bound
 * (2026-10-25, "what can be done now should be done now"; it replaces the fixed 10 minutes after
 * a boot): the SIM is loaded, CarrierSettings has served its config for the slot, and IMS has a
 * stable registration state, registered or clearly not registered after its attempts. A phone
 * that has run for a while is ready at the first look.
 */
enum class SimPhase {
    /** Loaded: the SIM is read and its subscription is there. */
    LOADED,

    /** Maybe on its way (not ready, ready but not loaded, not known yet, or loaded without its subscription read yet). */
    COMING,

    /** No SIM, or one only the owner can open (PIN, PUK, network lock), or a card error: nothing to wait for. */
    NONE,
}

/** One slot in one look. [imsGaveReason]: IMS named why its last registration attempt ended. */
data class SlotLook(
    val slot: Int,
    val sim: SimPhase,
    val configServed: Boolean? = null,
    val imsRegistered: Boolean? = null,
    val imsState: String? = null,
    val imsGaveReason: Boolean = false,
)

/** One look at every slot, on the elapsed clock. */
data class ReadyLook(val atMs: Long, val slots: List<SlotLook>)

enum class NotReady(val wire: String, val plain: String) {
    SIM("sim_loading", "the SIM is still loading"),
    CONFIG("config_not_served", "CarrierSettings has not served its config for the SIM yet"),
    IMS("ims_settling", "IMS has no stable registration state yet"),
}

data class SlotWait(val slot: Int, val why: NotReady) {
    val line: String get() = "slot ${slot + 1}: ${why.plain}"
}

data class ReadyResult(val ok: Boolean, val waitedMs: Long, val wait: SlotWait?, val looks: Int) {
    val line: String get() = wait?.line ?: "the SIM loaded, CarrierSettings's config served and IMS settled"
}

object Readiness {
    /** The bound of the wait: at most 2.5 minutes, then the run says what is missing. */
    const val MAX_WAIT_MS = 150_000L
    const val POLL_MS = 5_000L

    /** IMS not registered counts as a clear state after this long without a registered or registering look, with the SIM loaded and its config served. */
    const val NOT_REGISTERED_STEADY_MS = 60_000L

    /** IMS not registered that named why its last attempt ended counts as clear on two looks this far apart. */
    const val NOT_REGISTERED_WITH_REASON_MS = POLL_MS

    /**
     * The first slot that is not ready and why; null when every loaded SIM is (or there is nothing to wait for).
     * A slot still loading holds the run only while no SIM is loaded at all: an empty second slot can sit in
     * NOT_READY for good (the S20's, 2026-10-25), and a loaded SIM is tested without waiting for the other.
     */
    fun blocker(looks: List<ReadyLook>): SlotWait? {
        val last = looks.lastOrNull() ?: return SlotWait(0, NotReady.SIM)
        val loaded = last.slots.filter { it.sim == SimPhase.LOADED }.sortedBy { it.slot }
        if (loaded.isEmpty()) return last.slots.filter { it.sim == SimPhase.COMING }.minByOrNull { it.slot }?.let { SlotWait(it.slot, NotReady.SIM) }
        for (s in loaded) {
            if (s.configServed != true) return SlotWait(s.slot, NotReady.CONFIG)
            if (!imsSettled(looks, s.slot)) return SlotWait(s.slot, NotReady.IMS)
        }
        return null
    }

    /** Registered now; or not registered without an attempt in between, for [NOT_REGISTERED_STEADY_MS] (or two looks with a reason). */
    fun imsSettled(looks: List<ReadyLook>, slot: Int): Boolean {
        val mine = looks.map { look -> look.atMs to look.slots.firstOrNull { it.slot == slot } }
        val now = mine.lastOrNull()?.second ?: return false
        if (now.imsRegistered == true) return true
        // The looks since the SIM loaded with its config served, newest last, in which IMS neither registered nor tried.
        val steady = mine.takeLastWhile { (_, s) ->
            s != null && s.sim == SimPhase.LOADED && s.configServed == true && s.imsRegistered != true && s.imsState != "registering"
        }
        if (steady.isEmpty()) return false
        val span = steady.last().first - steady.first().first
        if (span >= NOT_REGISTERED_STEADY_MS) return true
        val reasoned = steady.takeLastWhile { (_, s) -> s!!.imsGaveReason }
        return reasoned.size >= 2 && reasoned.last().first - reasoned.first().first >= NOT_REGISTERED_WITH_REASON_MS
    }

    /** Looks until ready or [maxMs]; a ready phone answers at the first look. */
    suspend fun await(look: suspend () -> ReadyLook, nowMs: () -> Long, sleep: suspend (Long) -> Unit, maxMs: Long = MAX_WAIT_MS): ReadyResult {
        val start = nowMs()
        val looks = ArrayList<ReadyLook>()
        while (true) {
            looks += look()
            if (looks.size > 64) looks.removeAt(0)
            val wait = blocker(looks)
            val waited = nowMs() - start
            if (wait == null) return ReadyResult(true, waited, null, looks.size)
            if (waited >= maxMs) return ReadyResult(false, waited, wait, looks.size)
            sleep(POLL_MS)
        }
    }
}
