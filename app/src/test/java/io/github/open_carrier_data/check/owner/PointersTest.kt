package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.owner.core.ApnResult
import io.github.open_carrier_data.check.owner.core.FeatureResult
import io.github.open_carrier_data.check.owner.core.OcdAnswer
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.PointerLedger
import io.github.open_carrier_data.check.owner.core.Pointers
import io.github.open_carrier_data.check.owner.core.RunOptions
import io.github.open_carrier_data.check.owner.core.TestBasis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pointers, not values (the anti-fake research of 2026-10-20), and the owner's private pointer ledger. */
class PointersTest {
    private val ocdOn = OcdAnswer(true, "carrier_profile", "open.26202.8cebd7550c67", capability = "supported")
    private val s20 = DeviceInfo("samsung", "SM-G981B", "x1s", 16, RomFamily.LINEAGEOS)
    private val sim = OwnerFixtures.SIM

    private fun of(f: FeatureResult, t2: Boolean = false) = Pointers.entries(f, t2).associateBy { it.entry }

    @Test
    fun `a feature gives its on and off entries, the phone's own observed and the other tested`() {
        val e = of(FeatureResult(OwnerFeature.VOWIFI, true, true, ocdOn, offWorks = true, offReason = "service_gone", restored = true, best = true))
        assertEquals(setOf("vowifi:on", "vowifi:off"), e.keys)
        val on = e.getValue("vowifi:on")
        assertTrue(on.ocd && on.phone && !on.tested)
        assertEquals("signed_in", on.result)
        val off = e.getValue("vowifi:off")
        assertTrue(!off.ocd && !off.phone && off.tested)
        assertEquals("service_off", off.result)
        assertEquals(true, off.restored)
        assertEquals(emptyList<Any>(), Pointers.entries(FeatureResult(OwnerFeature.VOLTE, null, null, ocdOn, refused = "in_call"), false))
    }

    @Test
    fun `works needs a call measured, signed in alone does not`() {
        assertEquals("works", of(FeatureResult(OwnerFeature.VOLTE, true, true, ocdOn), t2 = true).getValue("volte:on").result)
        assertEquals(listOf("t2" to "lte"), of(FeatureResult(OwnerFeature.VOLTE, true, true, ocdOn), t2 = true).getValue("volte:on").calls)
        assertEquals("signed_in", of(FeatureResult(OwnerFeature.SMS_OVER_IMS, true, true, ocdOn), t2 = true).getValue("sms_over_ims:on").result)
        val off = of(FeatureResult(OwnerFeature.VOLTE, true, true, ocdOn, offWorks = true, offReason = "service_gone", offVoiceMeasured = true, offCallNetwork = "gsm"))
        assertEquals("works", off.getValue("volte:off").result)
        assertEquals(listOf("with_feature_off" to "gsm"), off.getValue("volte:off").calls)
        val noCall = of(FeatureResult(OwnerFeature.VOLTE, true, true, ocdOn, offWorks = true, offReason = "service_gone", offVoiceMeasured = true))
        assertEquals("service_off", noCall.getValue("volte:off").result)
    }

    @Test
    fun `the result classes keep a stopped test apart from a measured failure`() {
        fun on(reason: String?, works: Boolean?) = of(FeatureResult(OwnerFeature.VOLTE, false, false, ocdOn, onWorks = works, onReason = reason, restored = true)).getValue("volte:on")
        assertEquals("not_possible" to "blocked_at_network_offers", on("blocked_at_network_offers", false).let { it.result to it.reason })
        assertEquals("not_tested" to "blocked_at_user_switch", on("blocked_at_user_switch", false).let { it.result to it.reason })
        assertEquals("failed" to "settle_timeout", on("settle_timeout", false).let { it.result to it.reason })
        assertTrue(on("settle_timeout", false).cleanFailure)
        assertEquals("not_tested" to OwnerRunner.FIRST_USE_PENDING, on(OwnerRunner.FIRST_USE_PENDING, null).let { it.result to it.reason })
        val broke = of(FeatureResult(OwnerFeature.VOLTE, false, false, ocdOn, onWorks = true, onReason = "ims_registered_with_service", refused = "revert_not_proven")).getValue("volte:on")
        assertEquals("failed" to "revert_not_proven", broke.result to broke.reason)
        assertFalse("a test that broke says nothing about the entry", broke.cleanFailure)
        val lost = of(FeatureResult(OwnerFeature.VOLTE, true, true, ocdOn, offWorks = true, offVoiceMeasured = false, refused = "lost_voice")).getValue("volte:off")
        assertEquals("failed" to "lost_voice", lost.result to lost.reason)
        assertTrue(lost.cleanFailure)
        val own = of(FeatureResult(OwnerFeature.VOLTE, true, false, ocdOn)).getValue("volte:on")
        assertEquals("failed" to "on_not_signed_in", own.result to own.reason)
        assertFalse(own.tested)
    }

    @Test
    fun `an APN row is a pointer to OCD's row key`() {
        val present = Pointers.apn(ApnResult("web.vodafone.de [default,supl] IPV4V6", "web.vodafone.de|default,supl|IPV4V6", "present"))!!
        assertEquals("apn:web.vodafone.de|default,supl|IPV4V6", present.entry)
        assertTrue(present.ocd && present.phone)
        assertEquals("not_tested", present.result)
        val works = Pointers.apn(ApnResult("x", "internet.example|default|IPV4V6", "inserted", dataWorks = true, kept = true))!!
        assertEquals("works" to true, works.result to works.kept)
        assertEquals("failed", Pointers.apn(ApnResult("x", "internet.example|default|IPV4V6", "inserted", dataWorks = false))!!.result)
        assertNull("no key: the whole-table refusals", Pointers.apn(ApnResult("all rows", "", "refused_in_call")))
        assertNull("outside the schema's form", Pointers.apn(ApnResult("x", "web vodafone|default|IPV4V6", "present")))
    }

    @Test
    fun `the ledger counts passes and clean failures by what pointed at the entry`() {
        val cell = PointerLedger.cell(s20, sim.key)
        assertEquals("SM-G981B|lineageos|16|26202|2397", cell)
        val first = Pointers.entries(FeatureResult(OwnerFeature.VOLTE, false, false, ocdOn, onWorks = true, onReason = "ims_registered_with_service", restored = true), false)
        var rows = PointerLedger.add(emptyList(), cell, first, 1_000)
        val second = Pointers.entries(FeatureResult(OwnerFeature.VOLTE, false, false, ocdOn, onWorks = false, onReason = "settle_timeout", restored = true), false)
        rows = PointerLedger.add(rows, cell, second, 2_000)
        val broke = Pointers.entries(FeatureResult(OwnerFeature.VOLTE, false, false, ocdOn, onWorks = true, refused = "revert_not_proven"), false)
        rows = PointerLedger.add(rows, cell, broke, 3_000)
        val ocd = rows.single { it.entry == "volte:on" && it.pointer == PointerLedger.OCD }
        assertEquals(1 to 1, ocd.passed to ocd.failed)
        assertEquals("an unclean failure is not counted", 1_000L to 2_000L, ocd.firstMs to ocd.lastMs)
        val phone = rows.single { it.entry == "volte:off" && it.pointer == PointerLedger.PHONE }
        assertEquals(3, phone.passed)
        assertEquals("Open Carrier Data's values passed 1 of 2 tests; your phone's own 3 of 3", PointerLedger.summary(rows))
        assertNull(PointerLedger.summary(emptyList()))
    }

    @Test
    fun `every test the runner makes goes into the ledger`() = runBlocking {
        val phone = FakePhone()
        val basis = TestBasis("data-2026.10@sha256:36d5f7ebb084", "lineage/x1s@1", "0.3.0-owner(3)/1/1")
        val fast = RunOptions(settleMs = 30_000, restoreMs = 30_000, pollMs = 10_000, apnSettleMs = 30_000, basis = basis)
        OwnerRunner(phone, phone.store, s20).testFeature(sim, OwnerFeature.VOLTE, ocdOn, fast)
        val rows = phone.store.state.ledger
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.cell == "SM-G981B|lineageos|16|26202|2397" })
        assertTrue(rows.any { it.entry == "volte:on" && it.pointer == PointerLedger.OCD && it.passed == 1 })
    }
}
