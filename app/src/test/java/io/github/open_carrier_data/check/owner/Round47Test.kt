package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ApnCandidateGate
import io.github.open_carrier_data.check.owner.core.Groups
import io.github.open_carrier_data.check.owner.core.OnItsOwn
import io.github.open_carrier_data.check.owner.core.Sm7Kinds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 47, item 1: automatic means nobody can see, hear or pay for it. Test calls, test SMS, the balance USSD,
 * Test now's trials, the hold split and the MMS candidates need a tap; the emergency data test has none at all.
 */
class Round47Test {
    private val root = "src/main/java/io/github/open_carrier_data/check/owner"
    private fun source(name: String) = File("$root/$name").readText()
    private val all = File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the automatic switches and round 46's rules are gone`() {
        val text = all.joinToString("\n") { it.readText() }
        for (gone in listOf("callsOutMode", "CallsOutMode", "CallsOutPlan", "selfSmsOn", "balanceUssdOn", "ActiveTestsOptIn", "OptInWords", "EmergencyData.due", "deleteLogEntry", "noticeActed")) {
            assertFalse(gone, gone in text)
        }
    }

    @Test
    fun `only the taps reach the call, the SMS and the USSD`() {
        val work = source("OwnerWork.kt")
        assertEquals(1, Regex("caller\\.run\\(").findAll(work).count())
        assertEquals(2, all.sumOf { Regex("MailboxCaller\\(").findAll(it.readText()).count() }) // the class, and one use
        assertEquals(1, all.sumOf { Regex("OwnerSelfSms\\.send\\(").findAll(it.readText()).count() })
        assertEquals(1, all.sumOf { Regex("OwnerBalance\\.readOnTap\\(").findAll(it.readText()).count() })
        // Each of those steps is queued from the screen's own action only.
        val scheduled = all.flatMap { f -> Regex("OwnerScheduler\\.(callMailboxNow|selfSmsNow|balanceNow)\\(").findAll(f.readText()).map { f.name to it.groupValues[1] }.toList() }
        assertEquals(setOf("OwnerActivity.kt"), scheduled.map { it.first }.toSet())
    }

    @Test
    fun `the emergency data test has no automatic path and no row`() {
        val uses = all.filter { "OwnerEmergencyData.session(" in it.readText() }.map { it.name }
        assertEquals(listOf("OwnerWork.kt"), uses)
        val work = source("OwnerWork.kt")
        assertEquals(1, Regex("OwnerEmergencyData\\.session\\(").findAll(work).count())
        assertTrue(work.substringBefore("OwnerEmergencyData.session(").substringAfterLast("->").isNotEmpty())
        assertTrue(work.contains("STEP_EMERGENCY_DATA -> withPhone(store)"))
        val screen = source("OwnerScreen.kt")
        assertFalse(screen.contains("emergencyData") || screen.contains("Emergency data test"))
        // The step is queued only by the adb extra of the MODIFY_PHONE_STATE-guarded activity, and the grant check stays.
        assertTrue(source("OwnerActivity.kt").contains("EXTRA_EMERGENCY_DATA_TEST"))
        assertTrue(source("OwnerEmergencyData.kt").contains("if (!OwnerAccess.restrictedNetworksGranted(context)) return"))
    }

    @Test
    fun `no phone declares a tester, so SM7 applies on every phone`() {
        val dataset = source("OwnerDataset.kt")
        val declare = dataset.substringAfter("private fun declareTester(").substringBefore("Round 31 (R31.8)")
        assertTrue(declare.contains("client.setTester(false,"))
        assertFalse(declare.contains("setTester(true") || declare.contains("setTester(want"))
        assertEquals(1, Regex("\\.setTester\\(").findAll(dataset).count())
    }

    @Test
    fun `a healthy phone gets no trial - a run starts passive and only a measured failure turns it into a repair`() {
        val dataset = source("OwnerDataset.kt")
        assertTrue(dataset.contains("?: REPAIR_ONLY.takeUnless { tap }"))
        assertTrue(dataset.contains("if (passive == REPAIR_ONLY && verdict.outcome == TrialOutcome.MEASURED_FAIL) passive = null"))
        // An automatic trial is judged by registration and data, and dials nothing at all.
        assertTrue(dataset.contains("trial(if (!tap) effective.copy(classes = effective.classes - setOf(TestClass.VOICE, TestClass.WIFI_CALLING, TestClass.SMS)) else effective, before, explain)"))
        assertFalse(dataset.contains("MailboxCaller(") || dataset.contains("OwnerSelfSms.send("))
        // The hold split and the MMS candidates wait for a tap.
        assertTrue(dataset.contains("if (!testNow) \"the split needs a tap on Test now\" else null"))
        assertTrue(dataset.contains("if (testNow) mmsFailure(store, sim, now)?.let"))
    }

    @Test
    fun `an automatic trial never applies an SM7 kind`() {
        for ((key, off) in Groups.X5_OFF) {
            assertTrue(key, Sm7Kinds.held(key, JsonPrimitive(off)))
            assertFalse(key, Sm7Kinds.held(key, JsonPrimitive(!off)))
        }
        assertTrue(Sm7Kinds.held("carrier_default_wfc_ims_mode_int", JsonPrimitive(0)))
        assertFalse(Sm7Kinds.held("carrier_default_wfc_ims_mode_int", JsonPrimitive(1)))
        assertTrue(Sm7Kinds.held("carrier_default_wfc_ims_roaming_mode_int", JsonPrimitive(0)))
        assertTrue(Sm7Kinds.held("carrier_allow_turnoff_ims_bool", JsonPrimitive(false)))
        assertTrue(Sm7Kinds.held("allow_adding_apns_bool", JsonPrimitive(false)))
        assertTrue(Sm7Kinds.held("editable_enhanced_4g_lte_bool", JsonPrimitive(false)))
        assertTrue(Sm7Kinds.held("hide_enhanced_4g_lte_bool", JsonPrimitive(true)))
        assertTrue(Sm7Kinds.held("read_only_apn_types_string_array", JsonArray(listOf(JsonPrimitive("default")))))
        assertFalse(Sm7Kinds.held("read_only_apn_types_string_array", JsonArray(emptyList())))
        assertFalse(Sm7Kinds.held("some_other_bool", JsonPrimitive(false)))
        val dataset = source("OwnerDataset.kt")
        assertTrue(dataset.contains("if (!tap && plan.overrides.any { (key, cand) -> Sm7Kinds.held(key, cand.value) }) {"))
        assertTrue(dataset.contains(".filterNot { Sm7Kinds.held(ch.key, it.value) }"))
    }

    @Test
    fun `this list matches CarrierSettings's own`() {
        val provider = File("../carriersettings/ocd/src/com/android/carriersettings/ocd/Serving.kt").readText()
        for (key in listOf("carrier_allow_turnoff_ims_bool", "allow_adding_apns_bool", "read_only_apn_types_string_array", "read_only_apn_fields_string_array", "carrier_default_wfc_ims_mode_int", "carrier_default_wfc_ims_roaming_mode_int")) {
            assertTrue(key, key in provider && key in source("core/Tester.kt"))
        }
    }

    @Test
    fun `an MMSC probe failure alone opens nothing by itself`() {
        val code = File("$root/core/ApnCandidates.kt").readText()
        assertTrue(code.contains("i.failure == LeadFailure.MMSC_PROBE && !i.tap ->"))
        assertTrue(ApnCandidateGate.REREGISTRATIONS > 0)
    }

    @Test
    fun `an automatic run shows nothing, and voice-loss and stop notices stay`() {
        val receivers = source("OwnerReceivers.kt")
        assertFalse(receivers.contains("setOngoing"))
        assertFalse(receivers.contains("Stop test"))
        assertTrue(source("OwnerWork.kt").contains("Notices.of(before, after)?.takeIf { it.failed }?.let"))
        assertTrue(source("OwnerDataset.kt").contains("Notice(\"Calls stopped again\""))
    }

    @Test
    fun `the tap rows carry their words and the first screen its static line`() {
        val screen = source("OwnerScreen.kt")
        assertTrue(screen.contains("It may cost a call on your tariff and may mark a new message as heard"))
        assertTrue(screen.contains("One hidden SMS to your own number; it may cost an SMS"))
        assertTrue(screen.contains("The last one did not come back, so the stored number may be wrong and this SMS could reach someone else"))
        assertTrue(screen.contains("OnItsOwn.LINE"))
        assertTrue("no calls" in OnItsOwn.LINE && "tap" in OnItsOwn.LINE)
        assertFalse("works" in OnItsOwn.LINE)
    }

    @Test
    fun `no call log access and no sound changes in the manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (gone in listOf("READ_CALL_LOG", "WRITE_CALL_LOG", "MODIFY_AUDIO_SETTINGS", "NoticeActionReceiver")) assertFalse(gone, gone in manifest)
    }
}
