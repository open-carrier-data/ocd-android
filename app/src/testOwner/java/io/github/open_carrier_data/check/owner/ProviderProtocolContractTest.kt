package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ApnCandidateProtocol
import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.OcdDataProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The app's half of the writer-to-provider contract (OCD rule round 37, item 6): the protocol spec
 * in protocol/ (ocd-provider-protocol.json and its status vectors), which the provider's
 * carriersettings/ocd/test/run.sh reads too. A change to the app's names, the provider errors it
 * branches on or the status members it reads fails here until the spec says the same, and the
 * provider's test then fails until the provider matches.
 */
class ProviderProtocolContractTest {
    private val protocolDir = File("../protocol")
    private val spec = Json.parseToJsonElement(File(protocolDir, "ocd-provider-protocol.json").readText()).jsonObject
    private val methods = spec.getValue("methods").jsonArray.map { it.jsonObject }

    private fun strings(key: String): List<String> = spec.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun names(flag: String): Set<String> =
        methods.filter { (it[flag] as? JsonPrimitive)?.boolean == true }.map { it.getValue("name").jsonPrimitive.content }.toSet()

    /** OcdDataProtocol's string constants, name to value. */
    private val constants: Map<String, String> = OcdDataProtocol::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .associate { it.name to (it.get(null) as String) }

    private fun vector(path: String): JsonObject =
        JsonObject(Json.parseToJsonElement(File(protocolDir, path).readText()).jsonObject.filterKeys { !it.startsWith("_") })

    @Test
    fun versionAuthorityAndPermission() {
        assertEquals(spec.getValue("protocol").jsonPrimitive.int, OcdDataProtocol.PROTOCOL)
        assertEquals(spec.getValue("app_reads_protocols").jsonArray.map { it.jsonPrimitive.int }.toSet(), OcdDataProtocol.PROTOCOLS)
        assertEquals(spec.getValue("authority").jsonPrimitive.content, OcdDataProtocol.AUTHORITY)
        assertEquals("content://" + spec.getValue("authority").jsonPrimitive.content, OcdDataProtocol.URI)
        assertEquals(spec.getValue("permission").jsonPrimitive.content, OcdDataProtocol.PERMISSION)
    }

    /** Every method the app calls is in the spec, with app true, and every one the spec gives the app is called. */
    @Test
    fun methodsTheAppCalls() {
        val methodNames = methods.map { it.getValue("name").jsonPrimitive.content }.toSet()
        val called = constants.values.filter { it in methodNames }.toSet()
        assertEquals("the methods the app calls", names("app"), called)
    }

    @Test
    fun everyConstantIsInTheSpec() {
        val vocabulary = methods.map { it.getValue("name").jsonPrimitive.content } + strings("extras") + strings("outcomes") + strings("errors") +
            listOf(spec.getValue("authority").jsonPrimitive.content, spec.getValue("permission").jsonPrimitive.content, OcdDataProtocol.URI)
        val unknown = constants.filterValues { it !in vocabulary }
        assertTrue("OcdDataProtocol constants the spec does not name: $unknown", unknown.isEmpty())
    }

    /** A method the app calls and the provider lacks is a declared skew, answered unknown_method, never a silent one. */
    @Test
    fun skewIsDeclared() {
        for (m in methods.filter { (it["app"] as? JsonPrimitive)?.boolean == true && (it["owner"] as? JsonPrimitive)?.boolean != true }) {
            val name = m.getValue("name").jsonPrimitive.content
            assertTrue("the app calls $name, which the provider lacks, and the spec declares no pending reason", !(m["pending"] as? JsonPrimitive)?.content.isNullOrBlank())
        }
        // The app's own answer for a pending method is unknown_method, which every caller treats as "not run".
        assertEquals(OcdDataProtocol.UNKNOWN_METHOD, ApnCandidateProtocol.unknown(OcdDataProtocol.APN_CANDIDATE_SET).error)
        assertTrue("unknown_method is a provider error in the spec", OcdDataProtocol.UNKNOWN_METHOD in strings("errors"))
    }

    @Test
    fun installStatesAreTheProvidersStates() {
        val states = strings("install_states").toSet()
        val app = InstallState::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }.toSet() +
            InstallState.ACTIVATION + InstallState.FINAL + InstallState.SET_FINAL + InstallState.RESTORE_FINAL
        assertTrue("job states the provider never writes: ${app - states}", (app - states).isEmpty())
    }

    /** Every provider error code the app's code branches on is one the spec gives the provider, or the app's own. */
    @Test
    fun errorsTheAppBranchesOn() {
        val known = strings("errors").toSet() + spec.getValue("app_local_errors").jsonObject.keys + spec.getValue("pending_errors").jsonObject.keys
        val codes = sortedSetOf<String>()
        File("src/owner/java").walkTopDown().filter { it.name.endsWith(".kt") }.forEach { f ->
            val s = f.readText()
            Regex("error\\s*(?:==|!=)\\s*\"([a-z_]+)\"").findAll(s).forEach { codes += it.groupValues[1] }
            Regex("error\\s+in\\s+setOf\\(([^)]*)\\)").findAll(s).forEach { m -> Regex("\"([a-z_]+)\"").findAll(m.groupValues[1]).forEach { codes += it.groupValues[1] } }
            Regex("when \\((?:[a-z]+\\.)?error\\) \\{").findAll(s).forEach { m ->
                var depth = 1
                var j = m.range.last + 1
                while (depth > 0 && j < s.length) {
                    if (s[j] == '{') depth++ else if (s[j] == '}') depth--
                    j++
                }
                s.substring(m.range.last + 1, j).lines().forEach { line ->
                    Regex("^\\s*((?:\"[a-z_]+\"\\s*,\\s*)*\"[a-z_]+\")\\s*->").find(line)?.let { b -> Regex("\"([a-z_]+)\"").findAll(b.groupValues[1]).forEach { codes += it.groupValues[1] } }
                }
            }
        }
        assertTrue("the scan found the app's error branches", "refused_now" in codes && "changes_halted" in codes)
        assertTrue("error codes the app branches on that the spec does not name: ${codes - known}", (codes - known).isEmpty())
    }

    /** The provider's status answer, as the vectors give it, read by this app: every member the app reads arrives. */
    @Test
    fun statusInstalledVector() {
        val s = DatasetStatus.parse(DataAnswer(vector("vectors/status-3-installed.json")), 1L)
        assertTrue(s.available)
        assertEquals(3, s.protocol)
        assertTrue(s.round31)
        assertEquals("eng.ocd.20261029.000000", s.build.incremental)
        assertEquals("x1s", s.build.device)
        assertEquals("SM-G981B", s.build.model)
        assertEquals("lineageos", s.build.romFamily)
        assertEquals("24.0", s.build.romLine)
        assertEquals("aosp_imsstack", s.build.imsStackId)
        assertEquals("ocd", s.build.imsStackFork)
        assertEquals(36L, s.build.imsStackVersion)
        assertTrue(s.build.complete)
        assertEquals("b".repeat(40), s.buildPin?.commit)
        assertEquals("sha256:" + "2".repeat(64), s.buildPin?.datasetDigest)
        assertEquals("2026-10-20T00:00:00Z", s.buildPin?.generatedAt)
        assertEquals("a".repeat(40), s.installed?.commit)
        assertEquals("sha256:" + "3".repeat(64), s.installed?.dataDigest)
        assertEquals("2026-10-25T12:00:00Z", s.installed?.installedAt)
        assertTrue(s.servingInstalled)
        assertEquals("installed_newer", s.servingReason)
        assertFalse(s.changesHalted.on)
        assertFalse(s.pullAgain)
        assertNull(s.stagedId)
        assertNull(s.waitingFor)
        assertEquals("enabled", s.appState)
        assertEquals(true, s.tester.active)
        assertEquals(true, s.tester.declared)
        assertEquals("2026-10-25T12:00:00Z", s.tester.since)
        assertEquals("owner build", s.tester.reason)
        assertEquals(false, s.sm7)
        assertEquals("narrow", s.emergencyGate)
        assertEquals("build", s.emergencyGateSource)
        assertTrue(s.emergencyTestOn)
        assertEquals(true, s.deviceOverride.readable)
        assertTrue("imsvoice.voice_on_default_bearer_supported_bool" in s.deviceOverride.keys)
        assertEquals(4, s.deviceOverride.keys.size)
        assertFalse(s.voiceLoss.hold)
        assertFalse(s.voiceLoss.stop)
        assertFalse(s.apnTestSet)
    }

    @Test
    fun statusHeldVector() {
        val s = DatasetStatus.parse(DataAnswer(vector("vectors/status-3-held.json")), 1L)
        assertTrue(s.available)
        assertFalse(s.servingInstalled)
        assertEquals("voice_loss_hold", s.servingReason)
        assertTrue(s.changesHalted.on)
        assertEquals("2026-10-27T08:00:00Z", s.changesHalted.since)
        assertEquals("stop event", s.changesHalted.reason)
        assertTrue(s.pullAgain)
        assertEquals("0123456789abcdef", s.stagedId)
        assertEquals("refused", s.stagedState)
        assertEquals("changes_halted", s.stagedError)
        assertEquals("idle", s.waitingFor)
        assertEquals("disabled", s.appState)
        assertEquals(false, s.tester.active)
        assertEquals(true, s.sm7)
        assertEquals("full", s.emergencyGate)
        assertEquals("none", s.emergencyGateSource)
        assertFalse(s.emergencyTestOn)
        assertTrue(s.voiceLoss.hold)
        assertEquals("2026-10-26T08:00:00Z", s.voiceLoss.holdSince)
        assertEquals("IMS voice lost after the install", s.voiceLoss.holdReason)
        assertEquals("sha256:" + "4".repeat(64), s.voiceLoss.holdDatasetDigest)
        assertEquals("sha256:" + "5".repeat(64), s.voiceLoss.valuesDigest)
        assertTrue(s.voiceLoss.stop)
        assertEquals("voice lost twice under the same values", s.voiceLoss.stopReason)
        assertEquals(listOf("sha256:" + "4".repeat(64)), s.voiceLoss.blocked)
        assertTrue(s.apnTestSet)
        assertEquals("emergency default test set", s.apnTestSetKind)
    }

    /** Both vectors carry the same members, so the provider's test can hold each to status()'s. */
    @Test
    fun vectorsHaveTheSameMembers() {
        val paths = spec.getValue("status_vectors").jsonArray.map { it.jsonPrimitive.content }
        val shapes = paths.map { path ->
            vector(path).mapValues { (_, v) -> (v as? JsonObject)?.keys }
        }
        for (shape in shapes.drop(1)) assertEquals(shapes.first().keys, shape.keys)
        val first = vector(paths.first())
        assertTrue(first.getValue("ok").jsonPrimitive.boolean)
        assertTrue(first.getValue("device_override").jsonObject.getValue("keys") is JsonArray)
    }
}
