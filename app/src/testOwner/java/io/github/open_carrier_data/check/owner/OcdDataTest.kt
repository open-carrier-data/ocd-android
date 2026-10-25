package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.DatasetWords
import io.github.open_carrier_data.check.owner.core.ExplainView
import io.github.open_carrier_data.check.owner.core.InstallRefusal
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.InstallView
import io.github.open_carrier_data.check.owner.core.OcdDataProtocol
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.StageRequest
import io.github.open_carrier_data.check.owner.core.SubChanges
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 30's install interface (spec 6): the answers parsed, the absent provider, and the screen's words. */
class OcdDataTest {
    private fun answer(json: String) = DataAnswer(Json.parseToJsonElement(json).jsonObject)

    /** A Bundle as the Android side turns it into JSON: complex values arrive as JSON strings and are opened there. */
    private fun bundle(vararg pairs: Pair<String, Any>): DataAnswer = DataAnswer(
        JsonObject(
            pairs.associate { (k, v) ->
                k to when (v) {
                    is String -> DataAnswer.open(v)
                    is Boolean -> JsonPrimitive(v)
                    is Int -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            },
        ),
    )

    private val status = bundle(
        "ok" to true, "protocol" to 2,
        "build" to """{"incremental":"eng.20261026","device":"x1s","model":"SM-G981B","rom_family":"lineageos","rom_line":"24.0","ims_stack_id":"aosp_imsstack","ims_stack_fork":"ocd","ims_stack_version":34}""",
        "build_pin" to """{"commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","data_digest":"sha256:x","dataset_digest":"sha256:b","generated_at":"2026-10-24T10:00:00Z"}""",
        "installed" to """{"commit":"5cb3c43fc536be51b9ee663ad04d05f07d546006","data_digest":"sha256:x","dataset_digest":"sha256:i","generated_at":"2026-10-25T09:57:41Z","installed_at":"2026-10-26T03:00:00Z","google_lane_source":"baked"}""",
        "serving" to "installed", "serving_reason" to "installed",
        "safe_mode" to """{"on":false}""", "changes_halted" to """{"on":false}""", "pull_again" to false, "emergency_gate" to "full",
    )

    @Test
    fun `status - protocol 2, the cell, both datasets, serving, safe mode and the pause`() {
        val s = DatasetStatus.parse(status, 1)
        assertTrue(s.available)
        assertEquals("x1s", s.build.device)
        assertEquals("ocd", s.build.imsStackFork)
        assertEquals(34L, s.build.imsStackVersion)
        assertEquals("5cb3c43fc536be51b9ee663ad04d05f07d546006", s.installed!!.commit)
        assertTrue(s.servingInstalled)
        assertFalse(s.safeMode.on)
        // A nested object and a JSON string read the same; safe mode as CarrierSettings reports it.
        val on = DatasetStatus.parse(answer("""{"ok":true,"protocol":2,"serving":"build","serving_reason":"safe_mode","safe_mode":{"on":true,"since":"2026-10-26T03:00:00Z","by":"voice_loss","reason":"x"}}"""), 1)
        assertTrue(on.safeMode.on)
        assertEquals("voice_loss", on.safeMode.by)
        assertFalse(on.servingInstalled)
    }

    @Test
    fun `degrades cleanly - no provider, another protocol or a refusal of this app is not available, and nothing else counts`() {
        assertFalse(DatasetStatus.parse(DatasetClient.Absent.status(), 1).available)
        assertFalse(DatasetStatus.parse(answer("""{"ok":true,"protocol":1}"""), 1).available)
        assertFalse(DatasetStatus.parse(answer("""{"ok":false,"error":"forbidden_for_caller","reason":"x"}"""), 1).available)
        // Every call of the absent client answers absent, so nothing is staged, activated or recorded.
        val absent = DatasetClient.Absent
        for (a in listOf(absent.stage(StageRequest("{}", emptyList(), emptyMap())), absent.activate("i"), absent.putRecord("{}"), absent.setSafeMode(true, OcdDataProtocol.BY_OWNER, "x"), absent.applyApnRecords())) {
            assertFalse(a.ok)
            assertEquals(DataAnswer.ABSENT, a.error)
        }
        val words = DatasetWords.of(DatasetStatus.parse(absent.status(), 1), OwnerState())
        assertFalse(words.available)
        assertTrue(words.lines.single().contains("changes nothing"))
    }

    @Test
    fun `install_status - the states, failed checks and the changes per subscription, in list or map form`() {
        val listForm = answer(
            """{"ok":true,"install_id":"i1","state":"verified","commit":"c","dataset_digest":"sha256:d","checks":[{"check":"apn_version","ok":true,"detail":""},{"check":"sim_loses_ims","ok":false,"detail":"slot 1"}],
               "changes":[{"sub_id":3,"profile_before":"open.26207.c07dcc040b58","profile_after":"open.26207.78040a3afcc4","keys":[{"key":"carrier_volte_available_bool","old":true,"new":false,"source":"ocd:open.26207.78040a3afcc4"},{"key":"imsvoice.x_int","old":null,"new":5,"source":"google_fill:tchibo_de"}],
                 "apn_added":["a:1"],"apn_removed":[],"coupling_before":"same","coupling_after":"same","x5_held":{"carrier_volte_available_bool":{"data_value":false,"served_value":true}}}]}""",
        )
        val v = InstallView.parse("i1", listForm)
        assertEquals(InstallState.VERIFIED, v.state)
        assertFalse(v.final)
        assertEquals(listOf("sim_loses_ims: slot 1"), v.failedChecks)
        val c = v.changes.single()
        assertEquals(3, c.subId)
        assertTrue(c.touchesSim)
        assertEquals(listOf("carrier_volte_available_bool", "imsvoice.x_int"), c.keys.map { it.key })
        // A JSON null is Android's default, not a value.
        assertNull(c.keys[1].old)
        assertEquals(JsonPrimitive(false), c.x5Held.single().dataValue)
        val mapForm = SubChanges.parse(Json.parseToJsonElement("""{"4":{"keys":{"carrier_wfc_ims_available_bool":{"old":false,"new":true,"source":"ocd:p"}},"x5_held":[]}}"""))
        assertEquals(4, mapForm.single().subId)
        assertEquals("carrier_wfc_ims_available_bool", mapForm.single().keys.single().key)
        // A refusal without a state is refused with its code.
        assertEquals(InstallState.REFUSED, InstallView.parse("i2", answer("""{"ok":false,"error":"busy","reason":"another install job runs"}""")).state)
    }

    @Test
    fun `explain - the stack, the serving profile, the fill, keys with sources, X5 holds and the APN rows`() {
        val e = ExplainView.parse(
            3,
            answer(
                """{"ok":true,"explain":"{\"subscription\":3,\"slot\":0,\"network\":\"262-07\",\"stack\":{\"ids\":[\"open.26207.c07dcc040b58\",\"open.26207.78040a3afcc4\"],\"serving\":\"open.26207.78040a3afcc4\"},\"fill\":{\"entry\":\"tchibo_de\",\"when\":null},\"dataset\":{\"dataset\":\"installed\",\"commit\":\"c\"},\"version_string\":\"open.26207.78040a3afcc4+ocd5cb3c43\",\"keys\":{\"carrier_volte_available_bool\":{\"value\":true,\"source\":\"x5_hold\"},\"imsemergency.emergency_domain_preference_int_array\":{\"value\":[2,1],\"source\":\"group_a:google\"}},\"x5_held\":{\"carrier_volte_available_bool\":{\"data_value\":false,\"served_value\":true}},\"records_applied\":[\"r:40ee9931e862c76c\"],\"apn\":{\"apn_profile\":\"open.26207.78040a3afcc4\",\"coupling\":\"same\",\"rows\":[{\"row_key\":\"a:1\",\"apn\":\"internet\",\"types\":[\"default\"],\"protocol\":\"IPV4V6\",\"roaming_protocol\":\"IP\",\"emergency\":false},{\"row_key\":\"a:2\",\"apn\":\"sos\",\"types\":\"emergency\",\"protocol\":\"IP\",\"roaming_protocol\":\"IP\",\"emergency\":true}]}}"}""",
            ),
        )!!
        assertEquals("open.26207.78040a3afcc4", e.serving)
        assertEquals(listOf("open.26207.c07dcc040b58", "open.26207.78040a3afcc4"), e.stack)
        assertEquals("tchibo_de", e.fillEntry)
        assertEquals("x5_hold", e.keys["carrier_volte_available_bool"]!!.source)
        assertEquals("carrier_volte_available_bool", e.x5Held.single().key)
        assertEquals("a:2", e.emergencyRow!!.rowKey)
        assertFalse(e.servedBySimKey)
        assertTrue(ExplainView(subId = 1, serving = "default").servedBySimKey)
        assertNull(ExplainView.parse(3, answer("""{"ok":false,"error":"not_found"}""")))
    }

    @Test
    fun `a state file written by the round 29 build still reads, its layer records left behind`() {
        val old = """{"overrides":[],"layer":[{"slot":0,"sim":{"subId":3,"mccmnc":"26207","carrierId":1911},"key":"carrier_volte_available_bool","value":true,"cls":"VOICE_IMS","level":"DURABLE","origin":"ocd","appliedAtMs":1,"putAtMs":1}],"layerStatus":{"available":true},"scheduleOn":true}"""
        val state = OwnerState.decode(old)
        assertTrue(state.scheduleOn)
        assertNull(state.trial30)
        assertFalse(state.keepsDropped)
        assertFalse("layer" in Json.parseToJsonElement(state.encode()).jsonObject)
    }

    @Test
    fun `the screen's words - what serves, safe mode and a pause, plainly`() {
        val s = DatasetStatus.parse(status, 1)
        assertEquals("The newest data serves: main 5cb3c43, from 2026-10-25", DatasetWords.of(s, OwnerState()).lines.first())
        val safe = s.copy(safeMode = s.safeMode.copy(on = true, by = OcdDataProtocol.BY_VOICE_LOSS), serving = "build", servingReason = "safe_mode")
        val words = DatasetWords.of(safe, OwnerState())
        assertTrue(words.safeModeOn)
        assertEquals("The ROM's own data serves: safe mode is on (calls came back only with the ROM's data)", words.lines.first())
        assertTrue("Tests are stopped while safe mode is on" in words.lines)
        assertEquals("voice_loss_not_restored", DatasetWords.of(s, OwnerState(stop = "voice_loss_not_restored")).halted)
        assertEquals("a call is up", InstallRefusal.waiting("call"))
        assertEquals("an APN test row is in place", InstallRefusal.waiting("apn_trial_in_place"))
    }
}
