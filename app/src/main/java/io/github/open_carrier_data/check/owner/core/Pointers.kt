package io.github.open_carrier_data.check.owner.core

import io.github.open_carrier_data.check.core.DeviceInfo
import kotlinx.serialization.Serializable

/*
 * A report points, phones prove (the anti-fake research of 2026-10-20,
 * "Make a fake report worth nothing"). A result names an entry of the OCD
 * release's closed list, never a value: `volte:on`, `volte:off`, the same for
 * the other four switches, or `apn:` and the row key of an APN row the
 * release has for the SIM. The upload (IssueReport) and the private ledger
 * below read the same entries, so they cannot disagree.
 */

/** What one run found for one entry of the release's closed list. */
data class EntryResult(
    /** volte:on, vowifi:off, apn:web.vodafone.de|default,supl|IPV4V6, ... */
    val entry: String,
    /** The entry is OCD's value for this phone and SIM (an APN row entry always is). */
    val ocd: Boolean,
    /** The entry is the phone's own value before the test (an APN row: the phone has it). */
    val phone: Boolean,
    /** Set for a while and reverted with proof; false: the phone's own value, observed. */
    val tested: Boolean,
    /** works, signed_in, service_off, not_possible, failed or not_tested ([Pointers]). */
    val result: String,
    val reason: String?,
    /** (test, network): t2 or with_feature_off, and the call network type it showed. */
    val calls: List<Pair<String, String>> = emptyList(),
    val kept: Boolean = false,
    val method: String? = null,
    val restored: Boolean? = null,
    /** A settled result under rounds 26 to 28 (OCD round 29), from this phone's call ledger and SMS checks on this build ([Settled]). */
    val settled: SettledLevel? = null,
) {
    val passed: Boolean get() = result in Pointers.PASSED

    /** A failure that says the entry fails here: measured, and the phone's state came back (not a test that broke). */
    val cleanFailure: Boolean get() = result == Pointers.FAILED && reason !in Pointers.UNCLEAN && restored != false
}

object Pointers {
    const val WORKS = "works"
    const val SIGNED_IN = "signed_in"
    const val SERVICE_OFF = "service_off"
    const val NOT_POSSIBLE = "not_possible"
    const val FAILED = "failed"
    const val NOT_TESTED = "not_tested"
    val PASSED = setOf(WORKS, SIGNED_IN, SERVICE_OFF)

    /** Failures of the test, not of the entry. */
    val UNCLEAN = setOf("revert_not_proven", "insert_no_row", "read_back_differs")

    /** An on that cannot come up here: the modem, the IMS stack, the device flag, the network's offer, the carrier's 5G mode. */
    private val NOT_POSSIBLE_ON = setOf(
        "device_not_capable", OwnerRunner.NOT_AVAILABLE, OwnerRunner.IMS_STACK_NO_VIDEO, "blocked_at_network_offers", "blocked_at_sa_in_config",
    )

    /** "Not now": the phone's state stopped the test, so it says nothing about the entry. */
    private val NOT_NOW = setOf(
        "blocked_at_user_switch", "blocked_at_wifi_connected", "wifi_not_connected", "wifi_calling_backoff", "call_started",
        OwnerRunner.FIRST_USE_PENDING, "ims_state_unreadable", "off_tests_not_chosen", OwnerRunner.NO_VOICE_PATH, "voice_path_lost",
        "revert_waits_for_call", "switch_unreadable", "not_yet",
    )

    private fun notNow(reason: String?): Boolean =
        reason != null && (reason in NOT_NOW || reason.startsWith("blocked_at_backoff_") || reason.startsWith(RunInterrupted.INTERRUPTED))

    private val APN_ON_PHONE = setOf("present", OwnerRunner.SAME_AS_PHONE_ROW)
    private val APN_FAILED = setOf("revert_not_proven", "insert_no_row", "read_back_differs")

    /** The schema's form of an APN row key: the APN in lower case, the types sorted, the protocol. */
    val APN_KEY = Regex("[a-z0-9][a-z0-9._-]{0,62}\\|[a-z_*]{1,16}(,[a-z_*]{1,16}){0,15}\\|(IP|IPV6|IPV4V6|PPP|NON-IP|UNSTRUCTURED)?")

    /**
     * The two entries of one feature, `<feature>:on` and `<feature>:off`: the
     * phone's own value as observed, the other as tested. [t2]: calls both
     * ways measured on this phone and network (round 26 L3, which replaces
     * T2's person's yes; VoLTE and Wi-Fi calling).
     * Empty when the phone's value was not read.
     */
    fun entries(f: FeatureResult, t2: Boolean): List<EntryResult> {
        val baseline = f.baseline ?: return emptyList()
        val wire = f.feature.wire
        val t2Calls = if (t2 && f.feature in Ladders.CALL_TESTED) listOf("t2" to if (f.feature == OwnerFeature.VOWIFI) "iwlan" else "lte") else emptyList()
        val on = if (baseline) {
            val (result, reason) = when (f.baselineWorks) {
                true -> (if (t2Calls.isNotEmpty()) WORKS else SIGNED_IN) to null
                false -> FAILED to "on_not_signed_in"
                null -> NOT_TESTED to "not_observable"
            }
            EntryResult("$wire:on", ocd = f.ocd.value == true, phone = true, tested = false, result = result, reason = reason,
                calls = if (result == WORKS) t2Calls else emptyList(), method = f.method)
        } else {
            val reason = f.onReason
            val (result, why) = when {
                f.refused == "revert_not_proven" -> FAILED to "revert_not_proven"
                f.onWorks == true -> (if (t2Calls.isNotEmpty()) WORKS else SIGNED_IN) to reason
                reason in NOT_POSSIBLE_ON -> NOT_POSSIBLE to reason
                notNow(reason) -> NOT_TESTED to reason
                f.onWorks == false -> FAILED to (reason ?: "not_signed_in_when_tested")
                else -> NOT_TESTED to (reason ?: f.refused ?: "not_measured")
            }
            val ran = f.onWorks != null && result != NOT_POSSIBLE && result != NOT_TESTED
            EntryResult("$wire:on", ocd = f.ocd.value == true, phone = false, tested = true, result = result, reason = why,
                calls = if (result == WORKS) t2Calls else emptyList(), kept = f.applied && f.best == true, restored = if (ran) f.restored else null)
        }
        val offCall = f.offCallNetwork?.takeIf { f.feature.voice }
        val off = if (!baseline) {
            val (result, reason) = when (f.baselineWorks) {
                false -> SERVICE_OFF to null
                true -> FAILED to "service_stayed"
                null -> NOT_TESTED to "not_observable"
            }
            EntryResult("$wire:off", ocd = f.ocd.value == false, phone = true, tested = false, result = result, reason = reason)
        } else {
            val reason = f.offReason
            val (result, why) = when {
                f.refused == "revert_not_proven" -> FAILED to "revert_not_proven"
                f.refused == "lost_voice" -> FAILED to "lost_voice"
                f.offWorks == true -> (if (f.feature.voice && f.offVoiceMeasured == true && offCall != null) WORKS else SERVICE_OFF) to reason
                notNow(reason) -> NOT_TESTED to reason
                f.offWorks == false -> FAILED to (reason ?: "service_stayed")
                else -> NOT_TESTED to (reason ?: f.refused ?: "not_measured")
            }
            val ran = f.offWorks != null && result != NOT_TESTED
            EntryResult("$wire:off", ocd = f.ocd.value == false, phone = false, tested = true, result = result, reason = why,
                calls = listOfNotNull(offCall?.let { "with_feature_off" to it }), kept = f.applied && f.best == false,
                restored = if (ran) f.restored else null)
        }
        return listOf(on, off)
    }

    /** One APN row of OCD's for the SIM; null when its key falls outside the schema's form. */
    fun apn(a: ApnResult): EntryResult? {
        if (!APN_KEY.matches(a.apnKey)) return null
        val entry = "apn:${a.apnKey}"
        return when {
            a.status in APN_ON_PHONE -> EntryResult(entry, ocd = true, phone = true, tested = false, result = NOT_TESTED, reason = a.status)
            a.status == "inserted" && a.dataWorks == true -> EntryResult(entry, ocd = true, phone = false, tested = true, result = WORKS, reason = "data_validated", kept = a.kept)
            a.status == "inserted" && a.dataWorks == false -> EntryResult(entry, ocd = true, phone = false, tested = true, result = FAILED, reason = "no_data")
            a.status in APN_FAILED -> EntryResult(entry, ocd = true, phone = false, tested = true, result = FAILED, reason = a.status)
            else -> EntryResult(entry, ocd = true, phone = false, tested = true, result = NOT_TESTED, reason = if (a.status == "inserted") "not_measured" else a.status)
        }
    }
}

/**
 * The owner's private pointer ledger (the anti-fake research, "build first"
 * 2): for each test his phones run, what pointed at the entry (OCD's value,
 * the phone's own value, neither; later an outside report) and whether it
 * passed. The one number a faker cannot touch, and how often OCD's own values
 * pass on real phones. It stays in the app's private state; nothing reads it
 * out but the screen.
 */
@Serializable
data class LedgerRow(
    /** model|rom family|Android major|network code|carrier ID: round 19's cell on the owner's phone. */
    val cell: String,
    val entry: String,
    /** ocd, phone or none ([PointerLedger]). */
    val pointer: String,
    val passed: Int = 0,
    val failed: Int = 0,
    val firstMs: Long,
    val lastMs: Long,
)

object PointerLedger {
    const val OCD = "ocd"
    const val PHONE = "phone"
    const val NONE = "none"
    const val MAX_ROWS = 600

    fun cell(device: DeviceInfo, sim: SimKey): String =
        "${device.model}|${device.romFamily.wire}|${device.androidMajor}|${sim.mccmnc}|${sim.carrierId ?: "-"}"

    /** [rows] with the passes and clean failures of [results] counted under every pointer that named the entry. */
    fun add(rows: List<LedgerRow>, cell: String, results: List<EntryResult>, atMs: Long): List<LedgerRow> {
        val out = rows.toMutableList()
        for (r in results) {
            val pass = r.passed
            if (!pass && !r.cleanFailure) continue
            val pointers = listOfNotNull(OCD.takeIf { r.ocd }, PHONE.takeIf { r.phone }).ifEmpty { listOf(NONE) }
            for (pointer in pointers) {
                val index = out.indexOfFirst { it.cell == cell && it.entry == r.entry && it.pointer == pointer }
                val row = if (index >= 0) out[index] else LedgerRow(cell, r.entry, pointer, firstMs = atMs, lastMs = atMs)
                val next = row.copy(passed = row.passed + if (pass) 1 else 0, failed = row.failed + if (pass) 0 else 1, lastMs = atMs)
                if (index >= 0) out[index] = next else out += next
            }
        }
        return out.sortedByDescending { it.lastMs }.take(MAX_ROWS)
    }

    /** "Open Carrier Data's values passed 4 of 5 tests; your phone's own 6 of 6"; null before the first counted test. */
    fun summary(rows: List<LedgerRow>): String? {
        fun count(pointer: String) = rows.filter { it.pointer == pointer }.let { r -> r.sumOf { it.passed } to r.sumOf { it.passed + it.failed } }
        val (ocdPassed, ocdAll) = count(OCD)
        val (phonePassed, phoneAll) = count(PHONE)
        val parts = listOfNotNull(
            "Open Carrier Data's values passed $ocdPassed of $ocdAll ${if (ocdAll == 1) "test" else "tests"}".takeIf { ocdAll > 0 },
            "your phone's own $phonePassed of $phoneAll".takeIf { phoneAll > 0 },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")?.replaceFirstChar { it.uppercase() }
    }
}
