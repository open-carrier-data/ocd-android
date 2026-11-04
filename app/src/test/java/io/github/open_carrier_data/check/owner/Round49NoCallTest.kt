package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.BearerSeen
import io.github.open_carrier_data.check.owner.core.CrashCount
import io.github.open_carrier_data.check.owner.core.NoCallChecks
import io.github.open_carrier_data.check.owner.core.NoCallState
import io.github.open_carrier_data.check.owner.core.NoCallWords
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.QosCheck
import io.github.open_carrier_data.check.owner.core.QosDump
import io.github.open_carrier_data.check.owner.core.QosFact
import io.github.open_carrier_data.check.owner.core.QosRadio
import io.github.open_carrier_data.check.owner.core.QosState
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.SimQos
import io.github.open_carrier_data.check.owner.core.StackDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** OCD rule round 49, item 2: the checks that need no call. Each one only reads, and nothing but the entry screen reads them. */
class Round49NoCallTest {
    private val root = "src/main/java/io/github/open_carrier_data/check/owner"
    private val sim = SimKey(3, "26203", 2397)
    private val onDefault = QosCheck.VOICE_ON_DEFAULT
    private val precondition = QosCheck.PRECONDITION
    private val wait = QosCheck.WAIT_QOS
    private val roaming = QosCheck.ROAMING_WITHOUT_QOS

    /** A dump in ConfigAgent.dump's shape, with a line the parser must never keep. */
    private fun dump(defaults: String = "", internal: String = "", publicLayer: String = "", override: String = "", test: String = "", slot: Int = 0) = """
        Slot0:
            someAgentLine=true
        ### Carrier Configs
        DefaultInternalConfig:
                $defaults
                ims.something_else_bool=true

        DefaultPublicConfig:

        Slot$slot:
          configLoaded=true
          carrierId=masked
          cachedConfig=none

          CarrierInternalConfig:
                  $internal

          CarrierPublicConfig:
                  $publicLayer

          CarrierInternalOverrideConfig:
                  $override

          TestOverrideConfig:
                  $test

        ### System Interface
        $onDefault=true
    """.trimIndent()

    private fun at(text: String, ccm: Map<String, Boolean>? = emptyMap(), radio: QosRadio? = null, slot: Int = 0): SimQos =
        QosCheck.of(sim, StackDump.parse(text, slot), ccm, radio)

    @Test
    fun `the layers resolve in the stack's own order`() {
        val config = StackDump.parse(dump("$onDefault=false", "$onDefault=true"), 0)!!
        assertEquals(true, QosCheck.resolved(config, emptyMap(), onDefault))
        val ccm = mapOf(onDefault to false)
        assertEquals("CarrierConfigManager sits above the carrier's internal file", false, QosCheck.resolved(config, ccm, onDefault))
        val over = StackDump.parse(dump("$onDefault=false", override = "$onDefault=true"), 0)!!
        assertEquals("the internal override sits above it", true, QosCheck.resolved(over, ccm, onDefault))
        val public = StackDump.parse(dump(override = "$onDefault=true", publicLayer = "$onDefault=false", defaults = "$wait=false"), 0)!!
        assertEquals("the public layer sits above that", false, QosCheck.resolved(public, ccm, onDefault))
        val off = StackDump.parse(dump("${QosCheck.OVERRIDE_PUBLIC}=false", override = "$onDefault=true", publicLayer = "$onDefault=false"), 0)!!
        assertEquals("unless the stack turned that layer off", true, QosCheck.resolved(off, ccm, onDefault))
        val tested = StackDump.parse(dump(publicLayer = "$onDefault=false", test = "$onDefault=true"), 0)!!
        assertEquals("the test override is on top", true, QosCheck.resolved(tested, ccm, onDefault))
    }

    @Test
    fun `a missing key, a slot not there or an unread layer stay unknown, never false`() {
        assertNull(QosCheck.resolved(StackDump.parse(dump(), 0)!!, emptyMap(), onDefault))
        assertNull("CarrierConfigManager not read", QosCheck.resolved(StackDump.parse(dump("$onDefault=true"), 0)!!, null, onDefault))
        assertNull(StackDump.parse(null, 0))
        assertNull(StackDump.parse("no such section", 0))
        assertNull("the other slot's layers are not this slot's", StackDump.parse(dump(slot = 1), 0))
        assertEquals(SimQos(sim, QosState.UNKNOWN, QosState.UNKNOWN), at("nothing", radio = QosRadio.EMPTY))
        assertEquals("an unread chain is never fine, even with QoS reported", SimQos(sim, QosState.UNKNOWN, QosState.UNKNOWN), at("nothing", radio = QosRadio.REPORTED))
    }

    @Test
    fun `only the named keys are kept, and text after the section is not read`() {
        val config = StackDump.parse(dump("$onDefault=false"), 0)!!
        assertEquals(setOf(onDefault), config.layers.values.flatMap { it.keys }.toSet())
        assertEquals(false, config.layers.getValue(StackDump.DEFAULT_INTERNAL)[onDefault])
    }

    @Test
    fun `voice on the default bearer arms no timer at home, and the roaming key decides abroad`() {
        val keys = mapOf(onDefault to true)
        assertEquals(false, QosCheck.arms(keys::get, roaming = false))
        assertNull("the roaming key was not read", QosCheck.arms(keys::get, roaming = true))
        assertEquals(false, QosCheck.arms((keys + (roaming to true))::get, roaming = true))
        val abroad = keys + mapOf(roaming to false, precondition to false, wait to false)
        assertEquals(false, QosCheck.arms(abroad::get, roaming = true))
        assertEquals(true, QosCheck.arms((abroad + (wait to true))::get, roaming = true))
    }

    @Test
    fun `without the default bearer the precondition or wait-QoS arms it`() {
        assertEquals(true, QosCheck.arms(mapOf(onDefault to false, precondition to true)::get, roaming = false))
        assertEquals(true, QosCheck.arms(mapOf(onDefault to false, precondition to false, wait to true)::get, roaming = false))
        assertEquals("the S20's override of 2026-10-30, where calls worked", false, QosCheck.arms(mapOf(onDefault to false, precondition to false, wait to false)::get, roaming = false))
        assertNull(QosCheck.arms(mapOf(onDefault to false, precondition to false)::get, roaming = false))
        assertNull(QosCheck.arms(emptyMap<String, Boolean>()::get, roaming = false))
    }

    @Test
    fun `three states, and at risk needs both the armed timer and an empty radio`() {
        assertEquals(QosState.FINE, QosCheck.state(false, null))
        assertEquals(QosState.FINE, QosCheck.state(true, QosRadio.REPORTED))
        assertEquals(QosState.AT_RISK, QosCheck.state(true, QosRadio.EMPTY))
        assertEquals("an unknown radio", QosState.UNKNOWN, QosCheck.state(true, null))
        assertEquals(QosState.UNKNOWN, QosCheck.state(null, QosRadio.EMPTY))
        val armed = dump("$onDefault=false", "$precondition=true")
        assertEquals(SimQos(sim, QosState.AT_RISK, QosState.AT_RISK), at(armed, radio = QosRadio.EMPTY))
    }

    @Test
    fun `the radio fact reads the cellular IMS network only, and a new build forgets it`() {
        fun block(transport: String, caps: String, sessions: String) = """
            DataNetwork:
              mTransport=$transport
              mNetworkCapabilities=[ Transports: CELLULAR Capabilities: $caps LinkUpBandwidth>=1Kbps]
              mQosBearerSessions=$sessions
        """.trimIndent()
        val internet = block("WWAN", "SUPL&INTERNET&NOT_RESTRICTED", "[session]")
        assertEquals(QosRadio.EMPTY, QosDump.radio(block("WWAN", "IMS&NOT_METERED&TRUSTED", "[]") + "\n" + internet))
        assertEquals(QosRadio.REPORTED, QosDump.radio(block("WWAN", "MMTEL&IMS&TRUSTED", "[QosBearerSession {}]")))
        assertNull("Wi-Fi says nothing about the radio", QosDump.radio(block("WLAN", "IMS&TRUSTED", "[]")))
        assertNull(QosDump.radio(internet))
        assertNull(QosDump.radio(null))
        val fact = QosFact("rom-a", QosRadio.EMPTY)
        assertEquals(QosRadio.EMPTY, NoCallChecks.radioOf(fact, "rom-a"))
        assertNull(NoCallChecks.radioOf(fact, "rom-b"))
        assertFalse(NoCallChecks.radioWanted(fact, "rom-a"))
        assertTrue(NoCallChecks.radioWanted(fact, "rom-b"))
        assertTrue(NoCallChecks.radioWanted(null, "rom-a"))
    }

    @Test
    fun `an unknown result shows no warning, and a known one says what it is`() {
        val none = NoCallState(qos = listOf(SimQos(sim, QosState.UNKNOWN, QosState.UNKNOWN)), crashes = listOf(CrashCount("Iwlan", null)))
        assertEquals(emptyList<String>(), NoCallWords.lines(none, { null }) { "" })
        val risk = NoCallWords.lines(NoCallState(qos = listOf(SimQos(sim, QosState.AT_RISK, QosState.FINE))), { "o2" }) { "" }
        assertEquals(1, risk.size)
        assertTrue(risk.single(), risk.single().startsWith("o2: Calls on LTE may drop"))
        val fine = NoCallWords.lines(NoCallState(qos = listOf(SimQos(sim, QosState.FINE, QosState.FINE))), { null }) { "" }
        assertFalse("a check never says calls work", fine.single().contains("work"))
        assertFalse(NoCallWords.NOTE.isEmpty())
        val crashes = NoCallWords.lines(NoCallState(crashes = listOf(CrashCount("Iwlan", 0), CrashCount("ImsStack", 2))), { null }) { "" }
        assertEquals(listOf("Crashes in the last 7 days: Iwlan 0, ImsStack 2"), crashes)
    }

    @Test
    fun `registration per bearer keeps the last time each transport was reported`() {
        val one = NoCallChecks.seen(emptyList(), sim, NoCallChecks.WIFI, 1)
        val two = NoCallChecks.seen(one, sim, NoCallChecks.MOBILE, 2)
        assertEquals(listOf(BearerSeen(sim, NoCallChecks.WIFI, 3), BearerSeen(sim, NoCallChecks.MOBILE, 2)).toSet(), NoCallChecks.seen(two, sim, NoCallChecks.WIFI, 3).toSet())
        val lines = NoCallWords.lines(NoCallState(signedIn = two), { null }) { "today" }
        assertEquals(listOf("Last signed in over the mobile network: today", "Last signed in over Wi-Fi: today"), lines)
    }

    @Test
    fun `nothing but the entry reads these results, and they feed no report, notice, watch, hold or trial`() {
        val readers = File(root).walkTopDown().filter { it.extension == "kt" && it.name !in setOf("NoCallChecks.kt", "OwnerNoCall.kt") }
            .filter { Regex("\\.noCall\\b.*\\.(qos|crashes|signedIn)|noCall\\.(qos|crashes|signedIn)\\b").containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("OwnerWork.kt"), readers) // it writes signedIn; the screen hands the whole state to NoCallWords
        for (name in listOf("core/WeekReport.kt", "OwnerDataset.kt", "core/RealCallWatch.kt", "OwnerApnCandidates.kt", "core/Background.kt", "OwnerReceivers.kt")) {
            val text = File("$root/$name").readText()
            for (never in listOf("noCall", "QosCheck", "QosState", "CrashCount", "NoCallChecks")) assertFalse("$name: $never", text.contains(never))
        }
    }

    @Test
    fun `the checks read and never write, log or send`() {
        val code = File("$root/core/NoCallChecks.kt").readText() + File("$root/OwnerNoCall.kt").readText()
        for (never in listOf("logcat", "READ_LOGS", "getDescription", "getTraceInputStream", "openConnection", "requestNetwork", "overrideConfig", "notice(", "setRadio", "setWifi", "reload")) {
            assertFalse(never, code.contains(never))
        }
        assertTrue(File("$root/OwnerNoCall.kt").readText().contains("getHistoricalProcessExitReasons"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("READ_LOGS\""))
    }

    @Test
    fun `an old state file reads`() {
        assertEquals(NoCallState(), OwnerState.decode("{}").noCall)
        val state = OwnerState(noCall = NoCallState(QosFact("r", QosRadio.EMPTY), listOf(SimQos(sim, QosState.FINE, QosState.UNKNOWN)), listOf(CrashCount("Iwlan", 1)), listOf(BearerSeen(sim, "wifi", 1))))
        assertEquals(state.noCall, OwnerState.decode(state.encode()).noCall)
        assertNotNull(state.encode())
    }
}
