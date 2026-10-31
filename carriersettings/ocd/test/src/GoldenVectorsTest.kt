/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OCD rule round 45, item 3: the golden vectors of the resolution order, vectors/resolution-order.json
 * at the repository root, a byte copy of OCD's tests/vectors/resolution-order.json. Every vector
 * (a synthetic SIM, its stack generic to specific, the serving profile, the step of the order that
 * made it serve and the Google fill it picks) runs through this matcher, with a carrier ID table of
 * the file's rows from the pinned AOSP carrier list. OCD's resolver and the OCD Check app run the
 * same file. run.sh passes the repository root as the system property `ocd.repo`.
 */
class GoldenVectorsTest {
    private val repo = File(System.getProperty("ocd.repo") ?: error("run through run.sh: the system property ocd.repo names the repository root"))

    @Suppress("UNCHECKED_CAST")
    private val doc: Map<String, Any?> by lazy {
        Json.parse(File(repo, "vectors/resolution-order.json").readText(), Json.Limits(maxArray = Int.MAX_VALUE)) as Map<String, Any?>
    }

    /** The file's carrier ID rows as the phone's table, recording every fallback. */
    private class Table(rows: List<Map<*, *>>) : CarrierIdTable {
        val weights = HashMap<Pair<Int, String>, MutableList<Int>>()
        val fallbacks = ArrayList<Triple<Int, String, Boolean>>()

        init {
            for (row in rows) {
                val id = (row["carrier_id"] as Json.Num).longOrNull()!!.toInt()
                val columns = row.keys.map { it as String }.filter { row[it] != null }.toSet()
                weights.getOrPut(id to (row["mccmnc"] as String)) { ArrayList() } += CarrierIdTable.weightOf(columns)
            }
        }

        override fun ruleWeights(carrierId: Int, mccmnc: String): List<Int> = weights[carrierId to mccmnc].orEmpty()

        override fun fallback(carrierId: Int, mccmnc: String, unreadable: Boolean) {
            fallbacks += Triple(carrierId, mccmnc, unreadable)
        }
    }

    private class P(val id: String, val fillOnly: Boolean, val match: Match, val fill: List<FillItem>)

    private fun int(v: Any?): Int = (v as Json.Num).longOrNull()!!.toInt()

    private fun table(): Table = Table(((doc["carrier_id_table"] as Map<*, *>)["rows"] as List<*>).map { it as Map<*, *> })

    @Test
    fun theFilePinsTheAospCarrierList() {
        val t = doc["carrier_id_table"] as Map<*, *>
        assertEquals(134217778, int(t["version"]))
        assertEquals("7a49c7eb2e351071a801e34c98eae5cf9537d220", (t["source"] as Map<*, *>)["commit"])
        assertEquals(Order.STEPS, (doc["order"] as List<*>).map { it as String })
    }

    @Test
    fun everyVector() {
        val table = table()
        val failures = ArrayList<String>()
        var count = 0
        for (set in (doc["sets"] as List<*>).map { it as Map<*, *> }) {
            val networks = (set["networks"] as Map<*, *>).entries.associate { (k, v) ->
                k as String to (v as List<*>).map { it as Map<*, *> }.map { p ->
                    val id = p["id"] as String
                    P(id, p["kind"] == "fill_only", Match.parse(p["match"], id), ProfilesFile.parseFill(p["fill"], id))
                }
            }
            for (v in (set["vectors"] as List<*>).map { it as Map<*, *> }) {
                count++
                val s = v["sim"] as Map<*, *>
                val sim = SimId(
                    s["mcc"] as String, s["mnc"] as String, s["spn"] as String?, s["imsi"] as String?, s["gid1"] as String?,
                    s["gid2"] as String?, s["iccid"] as String?, s["carrier_id"]?.let(::int) ?: -1, s["specific_carrier_id"]?.let(::int) ?: -1, table,
                )
                val candidates = networks[sim.network].orEmpty()
                val ordered = Matcher.ordered(candidates.filter { Matcher.matches(it.match, sim) }, sim, { it.match }, { it.id }, { it.fillOnly })
                val stack = ordered.map { it.first.id }
                val reason = Order.reason(ordered.map { it.second })
                val pick = ordered.lastOrNull()?.first?.let { Matcher.pickFill(it.fill, sim) { true }.item?.entry }
                val want = Triple((v["stack"] as List<*>).map { it as String }, v["reason"] as String, v["fill_pick"] as String?)
                if (Triple(stack, reason, pick) != want) failures += "${set["name"]} ${v["id"]}: $stack $reason $pick, want $want"
                if ((stack.lastOrNull() ?: "default") != v["serving"]) failures += "${set["name"]} ${v["id"]}: serving"
                (v["must_not_contain"] as List<*>?)?.forEach { bad -> if (bad in stack) failures += "${v["id"]} holds $bad" }
            }
        }
        assertTrue("${failures.size} of $count vectors fail: ${failures.take(10)}", failures.isEmpty())
        assertTrue("the file holds the dry run's cases", count > 9000)
    }

    @Test
    fun aCarrierIdWithoutARuleCountsAtTheMccMncWeightAndIsReported() {
        val table = table()
        val m = Match(listOf(Network("999", "03")), carrierIds = listOf(999999))
        assertEquals(256, Order.weight(m, "99903", table))
        assertEquals(listOf(Triple(999999, "99903", false)), table.fallbacks)
        // No table at all: the same weight.
        assertEquals(256, Order.weight(m, "99903", null))
        // An unreadable table reports so.
        val unreadable = object : CarrierIdTable {
            var seen: Boolean? = null
            override fun ruleWeights(carrierId: Int, mccmnc: String): List<Int>? = null
            override fun fallback(carrierId: Int, mccmnc: String, unreadable: Boolean) { seen = unreadable }
        }
        assertEquals(290, Order.weight(Match(listOf(Network("999", "03")), spn = listOf("x"), gid1 = listOf("01"), carrierIds = listOf(7)), "99903", unreadable))
        assertEquals(true, unreadable.seen)
    }

    @Test
    fun theTieStepsInOrder() {
        val n = listOf(Network("999", "02"))
        fun key(id: String, m: Match, fillOnly: Boolean = false) = Order.key(m, "99902", fillOnly, id, null)
        fun Order.Key.step(other: Order.Key) = compareStep(other)?.let { it.first to Integer.signum(it.second) }
        val imsi = key("open.99902.000000000001", Match(n, imsi = listOf("9990250")))
        val gidSpn = key("open.99902.000000000002", Match(n, spn = listOf("A"), gid1 = listOf("01")))
        assertEquals("weight" to 1, imsi.step(gidSpn))
        val longer = key("open.99902.000000000003", Match(n, imsi = listOf("99902500")))
        assertEquals("prefix" to -1, imsi.step(longer))
        val fill = key("fill.99902.000000000004", Match(n, imsi = listOf("9990250")), fillOnly = true)
        assertEquals("ocd_before_fill_only" to 1, imsi.step(fill))
        val two = key("open.99902.000000000005", Match(n, imsi = listOf("9990250", "9990251")))
        assertEquals("fewer_values" to 1, imsi.step(two))
        val nets = key("open.99902.000000000006", Match(n + Network("999", "03"), imsi = listOf("9990250")))
        assertEquals("fewer_networks" to 1, imsi.step(nets))
        val same = key("open.99902.000000000007", Match(n, imsi = listOf("9990250")))
        assertEquals("profile_id" to -1, imsi.step(same))
        val cid = key("open.99902.000000000000", Match(n, carrierIds = listOf(1)))
        assertEquals("carrier_id" to 1, cid.step(key("open.99902.00000000000f", Match(n))))
    }
}
