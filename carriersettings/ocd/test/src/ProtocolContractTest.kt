/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The provider's half of the writer-to-provider contract (OCD rule round 37, item 6): the protocol
 * spec in ocd-android's protocol/ (ocd-provider-protocol.json and its status vectors), which OCD
 * Check's tests read too. A change to the provider's names, method sets or status answer fails
 * here until the spec says the same, and the app's test then fails until the app matches.
 *
 * run.sh passes the repository root as the system property `ocd.repo`.
 */
class ProtocolContractTest {
    private val repo = File(System.getProperty("ocd.repo") ?: error("run through run.sh: the system property ocd.repo names the repository root"))
    private val spec = obj(Json.parse(File(repo, "protocol/ocd-provider-protocol.json").readText()))
    private val src = File(repo, "carriersettings/ocd/src/com/android/carriersettings")
    private val sources = (File(src, "ocd").listFiles()!!.filter { it.name.endsWith(".kt") } + File(src, "CarrierConfigService.kt"))
        .associate { it.name to it.readText() }
    private val methods = list(spec["methods"]).map { obj(it) }

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: error("not a JSON object: $v")
    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?): List<Any?> = v as? List<Any?> ?: error("not a JSON array: $v")
    private fun strings(v: Any?): List<String> = list(v).map { it as String }
    private fun int(v: Any?): Int = (v as Json.Num).longOrNull()!!.toInt()
    private fun names(flag: String): Set<String> = methods.filter { it[flag] == true }.map { it["name"] as String }.toSet()

    /** Protocol's string constants, name to value. */
    private val constants: Map<String, String> = Protocol::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .associate { it.name to (it.get(null) as String) }

    @Test
    fun versionAuthorityPermissionAndWriters() {
        assertEquals("protocol", int(spec["protocol"]), Protocol.VERSION)
        assertEquals(spec["authority"], Protocol.AUTHORITY)
        assertEquals(spec["apn_authority"], Protocol.APN_AUTHORITY)
        assertEquals(spec["permission"], Protocol.PERMISSION)
        val writers = obj(spec["writer_packages"])
        assertEquals(writers["owner"], Protocol.OWNER_PACKAGE)
        assertEquals(writers["plain"], Protocol.PLAIN_PACKAGE)
        assertEquals(int(spec["max_answer_chars"]), Protocol.MAX_ANSWER_CHARS)
    }

    @Test
    fun methodSets() {
        assertEquals("the owner app's methods", names("owner"), Protocol.OWNER_METHODS)
        assertEquals("adb's methods", names("adb"), Protocol.ADB_METHODS)
    }

    @Test
    fun everyConstantIsInTheSpec() {
        val writers = obj(spec["writer_packages"]).values.map { it as String }
        val vocabulary = methods.map { it["name"] as String } + strings(spec["extras"]) + writers +
            listOf(spec["authority"] as String, spec["apn_authority"] as String, spec["permission"] as String)
        val unknown = constants.filterValues { it !in vocabulary }
        assertTrue("Protocol constants the spec does not name: $unknown", unknown.isEmpty())
    }

    @Test
    fun everyMethodAndExtraIsHandled() {
        val provider = sources.getValue("OcdDataProvider.kt")
        val byValue = constants.entries.groupBy({ it.value }, { it.key })
        for (m in methods.filter { it["owner"] == true || it["adb"] == true }) {
            val name = m["name"] as String
            val const = byValue[name]?.firstOrNull { "Protocol.$it ->" in provider }
            assertTrue("OcdDataProvider handles no branch Protocol.<$name> ->", const != null)
            for (extra in strings(m["extras"])) {
                val consts = byValue[extra] ?: emptyList()
                assertTrue("$name's extra $extra is no Protocol constant", consts.isNotEmpty())
                assertTrue("OcdDataProvider never reads $name's extra $extra", consts.any { "Protocol.$it" in provider })
            }
        }
    }

    @Test
    fun errorsStatesAndOutcomesExist() {
        val all = sources.values.joinToString("\n")
        val appOnly = obj(spec["app_local_errors"]).keys - setOf("forbidden_for_caller", "bad_request")
        for (code in strings(spec["errors"]) - appOnly) assertTrue("the provider never answers error $code", "\"$code\"" in all)
        val engine = sources.getValue("Engine.kt")
        for (state in strings(spec["install_states"])) assertTrue("the engine has no job state $state", "\"$state\"" in engine)
        for (outcome in strings(spec["outcomes"])) assertTrue("the engine has no outcome $outcome", "\"$outcome\"" in engine)
    }

    @Test
    fun skewIsDeclared() {
        for (m in methods.filter { it["app"] == true && it["owner"] != true }) {
            val pending = m["pending"] as? String
            assertTrue("the app calls ${m["name"]}, which the provider lacks, and the spec declares no pending reason", !pending.isNullOrBlank())
            println("::warning::protocol skew declared in protocol/ocd-provider-protocol.json: the app calls ${m["name"]}, the provider answers unknown_method")
        }
        for (m in methods.filter { it["pending"] != null }) {
            assertTrue("${m["name"]} is pending in the spec but the provider has it: set owner to true and drop pending", (m["name"] as String) !in Protocol.OWNER_METHODS)
        }
    }

    /** The status answer: the engine writes exactly the vectors' members, and the nested ones with the same members. */
    @Test
    fun statusMatchesTheVectors() {
        val root = Fixture.tempDir("contract")
        val files = Fixture.seal(
            Fixture.files(lane = Fixture.laneJson(state = "private_lane_at_build_pin"), rows = EngineTest.ROWS, generatedAt = "2026-10-20T00:00:00Z", commit = EngineTest.BAKED, role = "build"),
            "2026-10-20T00:00:00Z", EngineTest.BAKED, "build", laneAsExtra = true,
        )
        val p = FakePlatform(root, Fixture.write(File(root, "baked"), files))
        val engine = Engine(p)
        p.engine = engine
        engine.bootCheck("boot")
        p.drain()
        val status = engine.status()
        val body = section(sources.getValue("Engine.kt"), "    fun status(): Map<String, Any?> {", "\n    }\n")
        val buildKeys = Regex("\"([a-z_]+)\" to").findAll(section(sources.getValue("OcdAndroid.kt"), "override fun buildInfo(): Map<String, Any?> {", "\n        }\n"))
            .map { it.groupValues[1] }.toSet()
        for (path in strings(spec["status_vectors"])) {
            val vector = obj(Json.parse(File(repo, "protocol/$path").readText())).filterKeys { !it.startsWith("_") }
            assertEquals("$path: the members of status (with the Bundle's ok)", vector.keys, status.keys + Protocol.OK)
            for ((key, value) in vector) {
                if (value !is Map<*, *> || key == "build") continue
                val members = obj(value).keys
                when (val live = status[key]) {
                    is Map<*, *> -> assertEquals("$path: the members of $key", members, live.keys)
                    else -> for (member in members) assertTrue("$path: status() writes no member $member of $key", "\"$member\" to" in body)
                }
                if (key == "voice_loss") {
                    for ((inner, v) in obj(value)) {
                        if (v is Map<*, *>) for (member in v.keys) assertTrue("$path: status() writes no member $member of voice_loss.$inner", "\"$member\" to" in body)
                    }
                }
            }
            assertEquals("$path: the members of build (OcdAndroid.buildInfo)", obj(vector["build"]).keys, buildKeys)
        }
    }

    private fun section(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) fail("no \"$start\" in the source")
        val to = text.indexOf(end, from)
        return text.substring(from, if (to < 0) text.length else to)
    }
}
