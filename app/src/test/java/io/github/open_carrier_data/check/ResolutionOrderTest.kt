package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.core.OcdMatch
import io.github.open_carrier_data.check.core.OcdProfile
import io.github.open_carrier_data.check.core.ResolutionOrder
import io.github.open_carrier_data.check.core.SimIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 45, item 3: the golden vectors of the resolution order (vectors/resolution-order.json
 * at the repository root, a byte copy of OCD's tests/vectors/resolution-order.json), which OCD's
 * resolver and OcdCarrierSettings also pass. The app takes Android's weight from lookup.json; here it
 * computes it from the file's carrier ID rows, as OCD's resolver computes lookup.json's.
 */
class ResolutionOrderTest {
    private val doc: JsonObject by lazy {
        Json.parseToJsonElement(File("../vectors/resolution-order.json").readText()).jsonObject
    }

    private val rules: Map<Pair<Long, String>, List<Int>> by lazy {
        val weights = mapOf(
            "mccmnc" to 256, "imsi_prefix_xpattern" to 128, "iccid_prefix" to 64, "gid1" to 32, "gid2" to 16,
            "plmn" to 8, "privilege_access_rule" to 4, "spn" to 2, "apn" to 1,
        )
        doc.getValue("carrier_id_table").jsonObject.getValue("rows").jsonArray.map { it.jsonObject }
            .groupBy({ it.getValue("carrier_id").jsonPrimitive.long to it.getValue("mccmnc").jsonPrimitive.content }) { row ->
                row.entries.filter { it.value !is JsonNull }.sumOf { weights[it.key] ?: 0 }
            }
    }

    private fun strings(o: JsonObject, key: String): List<String> = o[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun profileOf(p: JsonObject): OcdProfile {
        val m = p.getValue("match").jsonObject
        val match = OcdMatch(
            mccmnc = m.getValue("mccmnc").jsonArray.map { it.jsonObject.let { n -> n.getValue("mcc").jsonPrimitive.content + n.getValue("mnc").jsonPrimitive.content } },
            spn = strings(m, "spn"),
            gid1Prefixes = strings(m, "gid1_prefixes"),
            gid2Prefixes = strings(m, "gid2_prefixes"),
            iccidPrefixes = strings(m, "iccid_prefixes"),
            imsiPrefixPatterns = strings(m, "imsi_prefix_patterns"),
            androidCarrierIds = m["android_carrier_ids"]?.jsonArray?.map { it.jsonPrimitive.long }.orEmpty(),
        )
        val weight = match.mccmnc.associateWith { code -> ResolutionOrder.weight(match, code) { id, net -> rules[id to net].orEmpty() } }
        return OcdProfile(profileId = p.getValue("id").jsonPrimitive.content, displayName = "", match = match, weight = weight)
    }

    private class Case(val set: String, val vector: JsonObject, val profiles: List<OcdProfile>)

    private val cases: List<Case> by lazy {
        doc.getValue("sets").jsonArray.map { it.jsonObject }.flatMap { set ->
            val networks = set.getValue("networks").jsonObject.mapValues { (_, v) -> v.jsonArray.map { profileOf(it.jsonObject) } }
            set.getValue("vectors").jsonArray.map { v ->
                val sim = v.jsonObject.getValue("sim").jsonObject
                val key = sim.getValue("mcc").jsonPrimitive.content + "-" + sim.getValue("mnc").jsonPrimitive.content
                Case(set.getValue("name").jsonPrimitive.content, v.jsonObject, networks[key].orEmpty())
            }
        }
    }

    private fun JsonElement.ids(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `the comparator puts every vector's stack in order with its reason`() {
        val failures = ArrayList<String>()
        for (case in cases) {
            val v = case.vector
            val sim = v.getValue("sim").jsonObject
            val mccmnc = sim.getValue("mcc").jsonPrimitive.content + sim.getValue("mnc").jsonPrimitive.content
            val want = v.getValue("stack").ids()
            val byId = case.profiles.associateBy { it.profileId }
            val sorted = want.reversed().map { byId.getValue(it) }.sortedWith(ResolutionOrder.comparator(mccmnc))
            val reason = ResolutionOrder.reason(sorted, mccmnc)
            if (sorted.map { it.profileId } != want || reason != v.getValue("reason").jsonPrimitive.content) {
                failures += "${case.set} ${v.getValue("id").jsonPrimitive.content}: ${sorted.map { it.profileId }} $reason"
            }
        }
        assertTrue("${failures.size} of ${cases.size}: ${failures.take(10)}", failures.isEmpty())
        assertTrue(cases.size > 9000)
    }

    @Test
    fun `the app's resolve gives every vector's stack, less what it cannot read`() {
        val failures = ArrayList<String>()
        for (case in cases) {
            val v = case.vector
            val id = v.getValue("id").jsonPrimitive.content
            if (id == "M23") continue // the app folds SPNs as Python's casefold does, the phone as Java's equalsIgnoreCase
            val sim = v.getValue("sim").jsonObject
            val mccmnc = sim.getValue("mcc").jsonPrimitive.content + sim.getValue("mnc").jsonPrimitive.content
            val ids = listOfNotNull(sim["carrier_id"]?.jsonPrimitive?.long, sim["specific_carrier_id"]?.jsonPrimitive?.long).filter { it >= 0 }.toSet()
            val identity = SimIdentity(mccmnc, sim["spn"]?.jsonPrimitive?.content ?: "", sim["gid1"]?.jsonPrimitive?.content ?: "", ids)
            val byId = case.profiles.associateBy { it.profileId }
            val readable = v.getValue("stack").ids().filter { p ->
                byId.getValue(p).match.let { it.imsiPrefixPatterns.isEmpty() && it.iccidPrefixes.isEmpty() && it.gid2Prefixes.isEmpty() }
            }
            val got = Matcher.resolve(case.profiles, identity).stack.map { it.profileId }
            if (got != readable) failures += "${case.set} $id: $got, want $readable"
        }
        assertTrue("${failures.size}: ${failures.take(10)}", failures.isEmpty())
    }

    @Test
    fun `without lookup's weight a carrier-ID selector counts at the MCC-MNC weight`() {
        val match = OcdMatch(mccmnc = listOf("26207"), androidCarrierIds = listOf(1454), spn = listOf("X"))
        assertEquals(258, ResolutionOrder.weight(OcdProfile("open.26207.000000000001", "", match), "26207"))
        assertEquals(258, ResolutionOrder.weight(OcdProfile("open.26207.000000000001", "", match, weight = mapOf("26207" to 258)), "26207"))
        assertEquals(290, ResolutionOrder.weight(match, "26207") { _, _ -> listOf(288) })
        assertEquals(258, ResolutionOrder.weight(match, "26207") { _, _ -> emptyList() })
        // The file pins the AOSP carrier list of the ROM's Android version.
        assertEquals(134217778, doc.getValue("carrier_id_table").jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(ResolutionOrder.STEPS, (doc.getValue("order") as JsonArray).map { it.jsonPrimitive.content })
    }
}
