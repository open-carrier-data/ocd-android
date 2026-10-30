/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant

/**
 * A stand-in for Android: TelephonyProvider's APN receiver (VERSION, REQUIRED_HASH, the file
 * read through the APN provider, new row IDs and every preferred APN dropped on a rewrite),
 * Telephony's reload on notify (it calls the engine's load), the call, roaming and APN-trial
 * readings, the writer app's state, the device override, IMS and data readings, and a worker
 * that runs when the test drains it.
 */
class FakePlatform(root: File, baked: File) : Platform {
    var now: Long = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
    override val store = Store(File(root, "ocd"))
    override var bakedDir: File = baked
    var cellValue: BuildCell? = Fixture.CELL
    var apnVersion: Int? = 8
    var sims: List<ActiveSim>? = listOf(ActiveSim(1, 0, EngineTest.O2))
    var phone = PhoneState(false, false, false, false)
    var roamingValue: Boolean? = false
    var trial: Boolean? = false
    var engine: Engine? = null
    var writerValue = Writer(Protocol.OWNER_PACKAGE, "enabled")
    var override: Map<String, OverrideValue>? = linkedMapOf(
        "imsvoice.audio_evs_support_bool" to OverrideValue(KeyType.BOOL, false, "device_override"),
        "imsvoice.voice_on_default_bearer_supported_bool" to OverrideValue(KeyType.BOOL, true, "device_override"),
        "imsvoice.voice_on_default_bearer_in_roaming_without_qos_bool" to OverrideValue(KeyType.BOOL, true, "device_override"),
        "gps.longterm_psds_server_1" to OverrideValue(KeyType.STRING, "", "device_override"),
    )
    var ims: String? = "registration=1;sms@lte,voice@lte"
    var data: Boolean? = true
    var emergencyRecent = false
    val notifications = ArrayList<String>()
    /** TelephonyProvider's preferred APN per subscription (row ID), dropped by every table rewrite. */
    val preferred = HashMap<Int, Long>()
    var tpIds: List<Long> = emptyList()
    private var nextId = 1L

    // TelephonyProvider.
    var tpRows: List<ApnLogic.DbRow> = emptyList()
    var userRows: List<ApnLogic.DbRow> = emptyList()
    var tpFileSha512: String? = null
    var tpVersion: Long = 0
    var refuseSha: Set<String> = emptySet()
    var refuseAll = false
    val broadcasts = ArrayList<Triple<String, Long, String>>()

    // Telephony.
    val notified = ArrayList<Int>()
    val effective = HashMap<Int, String>()
    var retries = 0
    val logs = ArrayList<String>()
    private val queue = ArrayDeque<() -> Unit>()

    override fun nowMs() = now
    override fun sleep(ms: Long) { now += ms }
    override fun cell() = cellValue
    override fun incremental() = "eng.test.1"
    override fun buildInfo(): Map<String, Any?> = mapOf("incremental" to incremental())
    override fun platformApnVersion() = apnVersion
    override fun androidDefault(key: String) = Fixture.defaults(key)
    override fun activeSims() = sims
    override fun phoneState() = phone
    override fun recentCall(): String? = null
    override fun recentEmergency(): Boolean = emergencyRecent
    override fun roaming(subId: Int, simMcc: String) = roamingValue
    override fun apnTrialInPlace() = trial
    override fun queryCarriers(): List<ApnLogic.DbRow> = tpRows + userRows

    override fun sendApnUpdate(name: String, version: Long, requiredHash: String): Boolean {
        broadcasts += Triple(name, version, requiredHash)
        if (refuseAll || version > Int.MAX_VALUE || version <= tpVersion) return true
        if (requiredHash != "NONE" && requiredHash != (tpFileSha512 ?: "0")) return true
        val bytes = File(store.apnOut, name).readBytes()
        if (Files.sha512(bytes) in refuseSha) return true
        val parsed = ApnXml.parse(bytes).rows
        tpRows = parsed.map { r ->
            ApnLogic.DbRow(r.attrs["mcc"], r.attrs["mnc"], r.attrs["apn"], r.attrs["type"], r.attrs["mvno_type"] ?: "", r.attrs["mvno_match_data"] ?: "", r.attrs["protocol"] ?: "IP", r.attrs["roaming_protocol"] ?: "IP", r.attrs["carrier_id"]?.toInt() ?: -1, 0)
        }
        tpFields = parsed.map { r -> nextId++ to fieldsOf(r) }
        tpIds = tpFields.map { it.first }
        preferred.clear()
        tpFileSha512 = Files.sha512(bytes)
        tpVersion = version
        return true
    }

    private var tpFields: List<Pair<Long, Map<String, String>>> = emptyList()

    private fun fieldsOf(r: ApnXml.Row): Map<String, String> = linkedMapOf(
        "name" to (r.attrs["carrier"] ?: ""), "apn" to (r.attrs["apn"] ?: ""), "type" to (r.attrs["type"] ?: ""), "mcc" to (r.attrs["mcc"] ?: ""),
        "mnc" to (r.attrs["mnc"] ?: ""), "mvno_type" to (r.attrs["mvno_type"] ?: ""), "mvno_match_data" to (r.attrs["mvno_match_data"] ?: ""),
        "protocol" to (r.attrs["protocol"] ?: "IP"), "roaming_protocol" to (r.attrs["roaming_protocol"] ?: "IP"), "user" to "u", "password" to "p",
    )

    /** The table's row ID of the first row with [apn] for [mnc]. */
    fun idOf(apn: String, mnc: String = "07"): Long = tpFields.first { it.second["apn"] == apn && it.second["mnc"] == mnc }.first

    override fun writer() = writerValue
    override fun deviceOverride(sim: SimId?) = override
    override fun preferredApn(subId: Int): PreferredRead = preferred[subId]?.let { id -> PreferredRead.Row(id, tpFields.first { it.first == id }.second.filterKeys { it != "user" && it != "password" }) } ?: PreferredRead.None
    override fun setPreferredApn(subId: Int, rowId: Long): Boolean {
        preferred[subId] = rowId
        return true
    }
    override fun carrierRows() = tpFields.map { (id, f) -> id to f.filterKeys { it != "user" && it != "password" } }
    override fun imsSnapshot(subId: Int) = ims
    override fun mobileDataValidated(subId: Int) = data
    override fun notifyOwner(title: String, text: String) { notifications += text }

    override fun notifyConfigChanged(subId: Int): Boolean {
        notified += subId
        val sim = sims?.firstOrNull { it.subId == subId }?.sim ?: return true
        effective[subId] = engine!!.load(subId, sim).version
        return true
    }

    override fun effectiveVersion(subId: Int): String? = effective[subId] ?: ""
    override fun armRetry(delayMs: Long) { retries++ }
    override fun armDaily() {}
    override fun runOnWorker(task: () -> Unit) { queue.addLast(task) }
    override fun log(message: String) { logs += message }

    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
    }
}

class EngineTest {
    companion object {
        const val BAKED = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val APP = "cccccccccccccccccccccccccccccccccccccccc"
        const val APP2 = "dddddddddddddddddddddddddddddddddddddddd"
        const val IMSI = "262070000000001"
        const val ICCID = "89490700000000000001"
        val O2 = SimId("262", "07", spn = "Example", imsi = IMSI, iccid = ICCID)
        val FILLER = (1..40).map { "open.99950.000000000000" to """  <apn apn="filler$it" carrier="F" mcc="999" mnc="50" type="default" />""" }
        val ROWS = Fixture.ROWS + FILLER
    }

    private var n = 0

    private fun bakedFiles(rows: List<Pair<String, String>> = ROWS, generatedAt: String = "2026-10-20T00:00:00Z", commit: String = BAKED): Map<String, ByteArray> {
        val files = Fixture.files(lane = Fixture.laneJson(state = "private_lane_at_build_pin"), rows = rows, generatedAt = generatedAt, commit = commit, role = "build")
        return Fixture.seal(files, generatedAt, commit, "build", laneAsExtra = true)
    }

    private fun appFiles(
        rows: List<Pair<String, String>> = ROWS,
        generatedAt: String = "2026-10-25T09:57:41Z",
        commit: String = APP,
        records: List<Map<String, Any?>> = Fixture.s20Records(),
        index: ((String) -> String)? = null,
    ): MutableMap<String, ByteArray> {
        val files = Fixture.files(rows = rows, generatedAt = generatedAt, commit = commit, records = Fixture.recordsJson(records))
        if (index != null) {
            files["apn-index.json"] = index(String(files.getValue("apn-index.json"))).toByteArray()
            return Fixture.seal(files, generatedAt, commit)
        }
        return files
    }

    private fun setup(baked: Map<String, ByteArray> = bakedFiles()): Pair<Engine, FakePlatform> {
        val root = Fixture.tempDir("engine-${n++}")
        val bakedDir = Fixture.write(File(root, "baked"), baked)
        val p = FakePlatform(root, bakedDir)
        val e = Engine(p)
        p.engine = e
        return e to p
    }

    private fun boot(e: Engine, p: FakePlatform): String = e.bootCheck("boot").also { p.drain() }

    private fun stage(e: Engine, files: Map<String, ByteArray>, f5: String? = null): Map<String, Any?> {
        val names = files.keys.filter { it != "pin.json" }
        return e.stage(String(files.getValue("pin.json")), names, names.associateWith { ByteArrayInputStream(files.getValue(it)) }, f5)
    }

    private fun refusal(code: String, block: () -> Unit): Refusal {
        try {
            block()
            fail("expected $code")
        } catch (r: Refusal) {
            assertEquals(r.reason, code, r.code)
            return r
        }
        throw AssertionError()
    }

    private fun installed(e: Engine, p: FakePlatform, files: Map<String, ByteArray> = appFiles()): String {
        val id = stage(e, files)[Protocol.INSTALL_ID] as String
        p.drain()
        assertEquals(e.installStatus(id).toString(), "verified", e.installStatus(id)[Protocol.STATE])
        e.activate(id)
        p.drain()
        return id
    }

    // --- 8.1 boot ---------------------------------------------------------------------------

    @Test
    fun firstBootInstallsTheBuildsApnFile() {
        val (e, p) = setup()
        val result = boot(e, p)
        assertTrue(result, result.startsWith("target build (no_dataset)"))
        assertEquals(1, p.broadcasts.size)
        assertEquals("NONE", p.broadcasts[0].third)
        val baked = File(p.bakedDir, "apns-conf.xml").readBytes()
        assertEquals(Files.sha512(baked), p.tpFileSha512)
        val s = e.state()
        assertEquals(Files.sha512(baked), s.apn.sha512)
        assertTrue(s.apn.confirmedAtMs > 0)
        assertEquals("build", e.status()["serving"])
        assertEquals("no_dataset", e.status()["serving_reason"])
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", e.load(1, O2).version)
        // Nothing to do the second time.
        assertTrue(boot(e, p).contains("nothing to install"))
        assertEquals(1, p.broadcasts.size)
    }

    @Test
    fun aStaleFileFromAnOlderBuildIsReplaced() {
        val (e, p) = setup()
        p.tpFileSha512 = "a".repeat(128)
        p.tpVersion = 5
        boot(e, p)
        assertEquals(Files.sha512(File(p.bakedDir, "apns-conf.xml").readBytes()), p.tpFileSha512)
    }

    // --- 6.3 stage, verify, activate --------------------------------------------------------

    @Test
    fun stageVerifyActivate() {
        val (e, p) = setup()
        boot(e, p)
        val answer = stage(e, appFiles())
        assertEquals("verifying", answer[Protocol.STATE])
        val id = answer[Protocol.INSTALL_ID] as String
        p.drain()
        val st = e.installStatus(id)
        assertEquals("verified", st[Protocol.STATE])
        val checks = Json.parse(st[Protocol.CHECKS] as String) as List<*>
        assertEquals((1..17).toSet(), checks.map { ((it as Map<*, *>)["check"] as Json.Num).longOrNull()!!.toInt() }.toSet())
        assertTrue(checks.all { (it as Map<*, *>)["ok"] == true })
        val changes = Json.parse(st[Protocol.CHANGES] as String) as List<*>
        assertEquals(1, changes.size)
        assertEquals("open.26207.c07dcc040b58", (changes[0] as Map<*, *>)["profile_after"])
        assertEquals("activating", e.activate(id)[Protocol.STATE])
        p.drain()
        assertEquals("installed", e.installStatus(id)[Protocol.STATE])
        assertEquals("installed", e.status()["serving"])
        assertEquals("open.26207.c07dcc040b58+ocd${APP.take(7)}", p.effective[1])
        assertEquals(Files.sha512(appFiles().getValue("apns-conf.xml")), p.tpFileSha512)
        assertEquals("already_installed", stage(e, appFiles())[Protocol.STATE])
    }

    @Test
    fun stageRefusals() {
        val (e, p) = setup()
        boot(e, p)
        refusal("bad_request") { e.stage(String(appFiles().getValue("pin.json")), listOf("other.json"), mapOf("other.json" to ByteArrayInputStream(ByteArray(1))), null) }
        val broken = appFiles()
        broken["profiles.json"] = String(broken.getValue("profiles.json")).replace("\"Example\"", "\"Example\"").plus(" ").toByteArray()
        refusal("blob_mismatch") { stage(e, broken) }
        e.stop("a stop event")
        refusal("changes_halted") { stage(e, appFiles()) }
        e.resume()
        assertEquals("verifying", stage(e, appFiles())[Protocol.STATE])
    }

    @Test
    fun activateRefusedNow() {
        val (e, p) = setup()
        boot(e, p)
        val id = stage(e, appFiles())[Protocol.INSTALL_ID] as String
        p.drain()
        p.phone = PhoneState(true, false, false, false)
        assertEquals("call", refusal("refused_now") { e.activate(id) }.waitingFor)
        p.phone = PhoneState(null, false, false, false)
        assertEquals("state_unreadable", refusal("refused_now") { e.activate(id) }.waitingFor)
        p.phone = PhoneState(false, false, true, false)
        assertEquals("ecbm", refusal("refused_now") { e.activate(id) }.waitingFor)
        p.phone = PhoneState(false, false, false, false)
        p.roamingValue = true
        assertEquals("roaming", refusal("refused_now") { e.activate(id) }.waitingFor)
        p.roamingValue = false
        p.trial = true
        assertEquals("apn_trial_in_place", refusal("refused_now") { e.activate(id) }.waitingFor)
        p.trial = false
        e.activate(id)
        p.drain()
        assertEquals("installed", e.installStatus(id)[Protocol.STATE])
    }

    @Test
    fun anApnFileThatDoesNotShowIsRolledBack() {
        val (e, p) = setup()
        boot(e, p)
        val files = appFiles(rows = ROWS.dropLast(1))
        p.refuseSha = setOf(Files.sha512(files.getValue("apns-conf.xml")))
        val id = stage(e, files)[Protocol.INSTALL_ID] as String
        p.drain()
        e.activate(id)
        p.drain()
        val st = e.installStatus(id)
        assertEquals("apn_not_confirmed", st[Protocol.STATE])
        assertEquals("build", e.status()["serving"])
        assertNull("the CarrierConfig part stays", e.state().installed)
        // The new file with the installed hash, once more with NONE, then the rollback with NONE.
        assertEquals(listOf("NONE", "NONE"), p.broadcasts.drop(2).map { it.third })
        assertEquals(Files.sha512(File(p.bakedDir, "apns-conf.xml").readBytes()), p.tpFileSha512)
    }

    @Test
    fun aRollbackThatDoesNotShowEither() {
        val (e, p) = setup()
        boot(e, p)
        val id = stage(e, appFiles(rows = ROWS.dropLast(1)))[Protocol.INSTALL_ID] as String
        p.drain()
        p.refuseAll = true
        p.tpRows = emptyList()
        e.activate(id)
        p.drain()
        assertEquals("apn_rollback_failed", e.installStatus(id)[Protocol.STATE])
    }

    // --- checks 14 to 17 ----------------------------------------------------------------------

    private fun verifiedOrCode(e: Engine, p: FakePlatform, files: Map<String, ByteArray>, f5: String? = null): String {
        val id = stage(e, files, f5)[Protocol.INSTALL_ID] as String
        p.drain()
        val st = e.installStatus(id)
        return (st[Protocol.ERROR] ?: st[Protocol.STATE]) as String
    }

    @Test
    fun apnShrink() {
        val (e, p) = setup()
        boot(e, p)
        assertEquals("apn_shrink", verifiedOrCode(e, p, appFiles(rows = Fixture.ROWS)))
    }

    @Test
    fun aSimWouldLoseItsInternetRow() {
        val (e, p) = setup()
        boot(e, p)
        assertEquals("sim_loses_internet", verifiedOrCode(e, p, appFiles(rows = ROWS.filter { !it.second.contains("o2 Internet") })))
    }

    @Test
    fun aSimWouldLoseItsImsRow() {
        val (e, p) = setup()
        boot(e, p)
        assertEquals("sim_loses_ims", verifiedOrCode(e, p, appFiles(rows = ROWS.filter { !it.second.contains("o2 IMS") })))
    }

    @Test
    fun aSimWouldLoseItsCoupling() {
        val (e, p) = setup()
        boot(e, p)
        val files = appFiles(index = { it.replaceFirst("\"profiles\":[\"open.26207.c07dcc040b58\"]", "\"profiles\":[\"open.26207.394d5585811d\"]") })
        assertEquals("apn_coupling_lost", verifiedOrCode(e, p, files))
    }

    @Test
    fun theEmergencyGateComesFromTheBuildsDeclaration() {
        val (e, p) = setup()
        // A build that does not declare the first use: the gate is full.
        p.cellValue = Fixture.CELL.copy(firstUsePassedOn = null)
        boot(e, p)
        assertEquals("full", e.status()["emergency_gate"])
        assertEquals("none", e.status()["emergency_gate_source"])
        val changed = ROWS.map { it.first to it.second.replace("apn=\"sos\"", "apn=\"sos.new\"") }
        assertEquals("emergency_gate", verifiedOrCode(e, p, appFiles(rows = changed)))
        val read = Json.write(linkedMapOf("schema" to "x1s-ocd-f5-read.v1", "base_commit" to BAKED, "head_commit" to APP, "lines_digest" to "x", "reader" to "a second agent", "read_at" to "2026-10-25T13:00:00Z", "verdict" to "proceed", "notes" to "read the sos row"))
        assertEquals("verified", verifiedOrCode(e, p, appFiles(rows = changed), read))
        val foreign = ROWS.map { it.first to it.second.replace("emergency.volte.ims.cnt.net.ec", "emergency.cnt.example") }
        assertEquals("emergency_gate", verifiedOrCode(e, p, appFiles(rows = foreign)))
        // The build declares the device family's first use passed (the S20 build): narrow, also after a clear.
        p.cellValue = Fixture.CELL
        e.clearDataset()
        p.drain()
        assertEquals("narrow", e.status()["emergency_gate"])
        assertEquals("build", e.status()["emergency_gate_source"])
        assertEquals("verified", verifiedOrCode(e, p, appFiles(rows = foreign)))
        assertEquals("emergency_gate", verifiedOrCode(e, p, appFiles(rows = changed)))
        // The app's first-use record cannot narrow, but one that did not pass on this phone keeps a stage full.
        fun firstUse(result: String, codename: String = "x1s") = Json.write(linkedMapOf("schema" to "ocd-emergency-first-use/1", "device" to linkedMapOf("codename" to codename, "model" to "SM-G981B"), "ims_stack" to linkedMapOf("id" to "aosp_imsstack", "fork" to "ocd"), "result" to result, "date" to "2026-10-26"))
        fun withFirstUse(fu: String): String {
            val f = appFiles(rows = foreign)
            val names = f.keys.filter { it != "pin.json" }
            val id = e.stage(String(f.getValue("pin.json")), names, names.associateWith { ByteArrayInputStream(f.getValue(it)) }, null, fu)[Protocol.INSTALL_ID] as String
            p.drain()
            return (e.installStatus(id)[Protocol.ERROR] ?: e.installStatus(id)[Protocol.STATE]) as String
        }
        assertEquals("verified", withFirstUse(firstUse("passed")))
        assertEquals("emergency_gate", withFirstUse(firstUse("not_meaningful")))
        assertEquals("emergency_gate", withFirstUse(firstUse("passed", codename = "a50")))
    }

    // --- 8.1 newer build pin, 8.2 safe mode, 8.3 crash guard ---------------------------------

    @Test
    fun aNewerBuildPinReplacesTheInstalledDataset() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        assertEquals("installed", e.status()["serving"])
        // A new build with a newer pin, the same storage.
        val newer = Fixture.write(File(p.bakedDir.parentFile, "baked2"), bakedFiles(generatedAt = "2026-10-26T00:00:00Z", commit = APP2))
        p.bakedDir = newer
        p.roamingValue = true
        assertTrue(boot(e, p).contains("waits until home"))
        assertEquals("installed", e.status()["serving"])
        p.roamingValue = false
        val result = boot(e, p)
        assertTrue(result, result.startsWith("target build (build_pin_newer)"))
        assertNull(e.state().installed)
        assertEquals("open.26207.c07dcc040b58+ocd${APP2.take(7)}", p.effective[1])
        assertEquals(Files.sha512(File(newer, "apns-conf.xml").readBytes()), p.tpFileSha512)
    }

    // --- R31.4 the app disabled is the way back -------------------------------------------------

    @Test
    fun disablingTheAppServesTheBuildsDatasetBothParts() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        assertEquals("open.26207.c07dcc040b58+ocd${APP.take(7)}", p.effective[1])
        p.writerValue = Writer(Protocol.OWNER_PACKAGE, "disabled")
        // Already the next load serves the build, and the check it queues installs both parts.
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", e.load(1, O2).version)
        p.drain()
        assertNull("the installed dataset goes", e.state().installed)
        assertEquals("app_disabled", e.status()["serving_reason"])
        assertEquals("build", e.status()["serving"])
        assertEquals(Files.sha512(File(p.bakedDir, "apns-conf.xml").readBytes()), p.tpFileSha512)
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", p.effective[1])
        assertEquals(true, e.status()["sm7"])
        // Enabled again: the build keeps serving until the app installs the newest dataset.
        p.writerValue = Writer(Protocol.OWNER_PACKAGE, "enabled")
        assertNotNull(e.appCheck("test"))
        p.drain()
        assertEquals("no_dataset", e.status()["serving_reason"])
        assertEquals(false, e.status()["sm7"])
        installed(e, p)
        assertEquals("installed", e.status()["serving"])
    }

    @Test
    fun disablingTheAppWorksWhileRoamingButWaitsForACall() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        p.roamingValue = true
        p.phone = PhoneState(true, false, false, false)
        p.writerValue = Writer(Protocol.OWNER_PACKAGE, "disabled")
        e.appCheck("test")
        assertEquals("call", e.state().ownInstall)
        p.phone = PhoneState(false, false, false, false)
        e.processPending("retry")
        p.drain()
        assertNull(e.state().ownInstall)
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", p.effective[1])
    }

    @Test
    fun onlyAnExplicitDisableCounts() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        p.writerValue = Writer(Protocol.OWNER_PACKAGE, "unknown")
        assertNull(e.appCheck("test"))
        assertEquals("open.26207.c07dcc040b58+ocd${APP.take(7)}", e.load(1, O2).version)
        assertNotNull(e.state().installed)
    }

    @Test
    fun theCrashGuard() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        val digest = e.state().installed!!.datasetDigest
        p.effective[1] = ""
        e.onConfigChanged(1, O2)
        assertNull("the first stub reloads", e.state().crashGuard)
        p.effective[1] = ""
        e.onConfigChanged(1, O2)
        p.drain()
        val s = e.state()
        assertEquals(digest, s.crashGuard?.datasetDigest)
        assertTrue(s.pullAgain)
        assertEquals("build", e.status()["serving"])
        assertEquals("crash_guard", e.status()["serving_reason"])
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", e.load(1, O2).version)
        // The same dataset is refused (check 13); another dataset_digest clears the guard once proven.
        refusal("crash_guard_dataset") { stage(e, appFiles()) }
        installed(e, p, appFiles(rows = ROWS.dropLast(1), commit = APP2, generatedAt = "2026-10-25T11:00:00Z"))
        assertNull(e.state().crashGuard)
        assertEquals("installed", e.status()["serving"])
    }

    @Test
    fun theCrashGuardOnTheBuildItself() {
        val (e, p) = setup()
        boot(e, p)
        e.load(1, O2)
        repeat(2) {
            p.effective[1] = ""
            e.onConfigChanged(1, O2)
        }
        p.drain()
        assertEquals(true, e.state().crashGuard?.onBuild)
        assertEquals("crash_guard_build+ocd${BAKED.take(7)}", e.load(1, O2).version)
    }

    @Test
    fun aStaleVersionStringIsReloadedSpaced() {
        val (e, p) = setup()
        boot(e, p)
        e.load(1, O2)
        p.now += 3 * 60_000
        p.effective[1] = "open.26207.c07dcc040b58+ocd0000000"
        val before = p.notified.size
        e.onConfigChanged(1, O2)
        assertEquals(before + 1, p.notified.size)
        p.effective[1] = "open.26207.c07dcc040b58+ocd0000000"
        e.onConfigChanged(1, O2)
        assertTrue("within 2 minutes the reload waits", 1 in e.state().pending)
    }

    @Test
    fun clearDataset() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        e.clearDataset()
        p.drain()
        assertNull(e.state().installed)
        assertEquals("no_dataset", e.status()["serving_reason"])
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", p.effective[1])
    }

    @Test
    fun aLoadDuringACallServesWhatWasServed() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        val before = e.load(1, O2)
        p.phone = PhoneState(true, false, false, false)
        e.clearDataset()
        val during = e.load(1, O2)
        assertEquals(before.version, during.version)
        assertEquals(before.values, during.values)
    }

    // --- 6.4 records --------------------------------------------------------------------------

    private fun recordJson(key: String, value: Any?, profile: String = "open.26207.c07dcc040b58", kind: String = "measured", model: String = "SM-G981B", validUntil: String? = null, target: Map<String, Any?>? = null, valueKind: String = "value"): String =
        Json.write(
            Fixture.record(
                kind = kind, key = key, value = value, carrier = mapOf("scope" to "profile", "profile_id" to profile), model = model,
                validUntil = validUntil, lastMeasured = if (validUntil != null) "2026-07-01" else "2026-10-25", valueKind = valueKind,
                target = target ?: linkedMapOf("type" to "config", "key" to key),
                result = if (target?.get("type") == "emergency_data") "emergency_default_won" else null,
            ),
        )

    @Test
    fun aMeasuredRecordIsServedAtOnce() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        val answer = e.putRecord(recordJson("carrier_vt_available_bool", false))
        assertEquals("reload_sent", answer[Protocol.EFFECT])
        val b = e.load(1, O2)
        assertEquals(false, b.values["carrier_vt_available_bool"])
        assertEquals("measured_record:${answer[Protocol.RECORD_KEY]}", b.sources["carrier_vt_available_bool"])
        val listed = e.listRecords(1)
        assertTrue(listed.any { it["origin"] == "local" && it["state"] == "served" })
        e.removeRecord(answer[Protocol.RECORD_KEY] as String)
        assertEquals(true, e.load(1, O2).values["carrier_vt_available_bool"])
    }

    @Test
    fun aLocalTesterRecordStaysOnThePhone() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        fun local(value: Boolean) = Json.write((Json.parse(recordJson("carrier_vt_available_bool", value)) as Map<*, *>).toMutableMap().apply { put("tester", "local") })
        assertEquals("reload_sent", e.putRecord(local(false))[Protocol.EFFECT])
        assertEquals(false, e.load(1, O2).values["carrier_vt_available_bool"])
        assertTrue(e.listRecords(1).any { it["origin"] == "local" && it["state"] == "served" })
        // The ROM flavor (another writer) writes local records only.
        p.writerValue = Writer(Protocol.PLAIN_PACKAGE, "enabled")
        refusal("record_invalid") { e.putRecord(recordJson("carrier_vt_available_bool", true)) }
        assertEquals("reload_sent", e.putRecord(local(true))[Protocol.EFFECT])
    }

    @Test
    fun recordRefusals() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        refusal("record_group_a") { e.putRecord(recordJson("imsemergency.emergency_domain_preference_int_array", listOf(2))) }
        refusal("record_group_b") { e.putRecord(recordJson("carrier_metered_apn_types_strings", listOf("default"))) }
        refusal("record_wrong_cell") { e.putRecord(recordJson("carrier_vt_available_bool", false, model = "SM-G980F")) }
        refusal("record_invalid") { e.putRecord(recordJson("carrier_vt_available_bool", false, kind = "configured_device")) }
        refusal("record_unknown_profile") { e.putRecord(recordJson("carrier_vt_available_bool", false, profile = "open.26207.000000000000")) }
        refusal("record_expired") { e.putRecord(recordJson("carrier_vt_available_bool", false, validUntil = "2026-10-01")) }
        refusal("record_not_candidate") { e.putRecord(recordJson("maxImageHeight", 1000)) }
        refusal("record_device_override") { e.putRecord(recordJson("imsvoice.voice_on_default_bearer_supported_bool", false)) }
        refusal("record_invalid") { e.putRecord("{\"record_key\": 1}") }
        val doc = ApnXml.parse(appFiles().getValue("apns-conf.xml"))
        val internet = doc.rows.first { it.attrs["apn"] == "internet" && it.attrs["mnc"] == "07" }.rowKey
        val cnt = doc.rows.first { it.attrs["mnc"] == "37" }.rowKey
        val sos = doc.rows.first { it.attrs["apn"] == "sos" }.rowKey
        refusal("record_apn_would_remove_internet") { e.putRecord(recordJson("", null, valueKind = "none", target = linkedMapOf("type" to "apn", "row_key" to internet, "op" to "omit"))) }
        refusal("record_apn_row_unknown") { e.putRecord(recordJson("", null, valueKind = "none", target = linkedMapOf("type" to "apn", "row_key" to cnt, "op" to "omit"))) }
        assertEquals("apn_recompose_needed", e.putRecord(recordJson("", null, valueKind = "none", target = linkedMapOf("type" to "emergency_data", "row_key" to sos)))[Protocol.EFFECT])
        // apply_apn_records leaves the SIM's emergency row out (Android's default won).
        val job = e.applyApnRecords()
        p.drain()
        assertEquals("installed", e.installStatus(job)[Protocol.STATE])
        assertFalse(p.tpRows.any { it.apn == "sos" })
        e.stop("stop")
        refusal("changes_halted") { e.putRecord(recordJson("carrier_vt_available_bool", false)) }
    }

    // --- the names the owner app parses (spec 6.3; app-ocd-check core/OcdData.kt) ----------------

    @Test
    fun theAnswerNamesTheAppParses() {
        val (e, p) = setup()
        boot(e, p)
        val id = stage(e, appFiles(rows = ROWS.dropLast(1)))[Protocol.INSTALL_ID] as String
        p.drain()
        val st = e.installStatus(id)
        for (k in listOf("install_id", "state", "commit", "dataset_digest", "generated_at", "checks", "changes")) assertTrue(k, st.containsKey(k))
        val sub = (Json.parse(st[Protocol.CHANGES] as String) as List<*>)[0] as Map<*, *>
        assertEquals(
            setOf("subscription", "slot", "network", "profile_before", "profile_after", "keys", "apn_added", "apn_removed", "coupling_before", "coupling_after", "apn_profile_after", "x5_held"),
            sub.keys,
        )
        assertEquals("rows are per SIM: the dropped filler row is not in the O2 SIM's selection", emptyList<String>(), sub["apn_removed"])
        val explain = Json.parse(e.explain(1, null)) as Map<*, *>
        assertEquals(
            setOf("subscription", "slot", "network", "stack", "serving", "fill", "fill_missing", "dataset", "commit", "dataset_digest", "version_string", "keys", "x5_held", "sm7", "sm7_held", "records_applied", "apn_test_set", "apn"),
            explain.keys,
        )
        // The device override's values, with what OCD's bundle had under them.
        val keys = explain["keys"] as Map<*, *>
        val voice = keys["imsvoice.voice_on_default_bearer_supported_bool"] as Map<*, *>
        assertEquals("device_override", voice["source"])
        assertEquals(true, voice["value"])
        val psds = keys["gps.longterm_psds_server_1"] as Map<*, *>
        assertEquals("", psds["value"])
        assertEquals("default", (psds["under_override"] as Map<*, *>)["source"])
        assertEquals("build", explain["dataset"])
        assertEquals(BAKED, explain["commit"])
        assertEquals(setOf("apn_profile", "coupling", "rows"), (explain["apn"] as Map<*, *>).keys)
        val row = ((explain["apn"] as Map<*, *>)["rows"] as List<*>)[0] as Map<*, *>
        assertEquals(setOf("row_key", "apn", "types", "protocol", "roaming_protocol", "mvno_type", "emergency"), row.keys)
        val status = e.status()
        for (k in listOf(
            "protocol", "build", "build_pin", "installed", "serving", "serving_reason", "app", "tester", "sm7", "voice_loss", "changes_halted", "pull_again", "staged",
            "pending_subs", "waiting_for", "emergency_gate", "emergency_gate_source", "device_override", "apn_test_set", "notice", "apn", "last_check",
        )) assertTrue(k, status.containsKey(k))
        assertFalse(status.containsKey("safe_mode"))
        assertEquals(3, status["protocol"])
    }

    // --- privacy (4.1, 8.6) ---------------------------------------------------------------------

    @Test
    fun noSimIdentifierIsStoredLoggedOrAnswered() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        e.putRecord(recordJson("carrier_vt_available_bool", false))
        val explain = e.explain(1, null)
        assertTrue(explain.contains("\"version_string\""))
        val everything = StringBuilder(explain).append(e.status().toString()).append(e.listRecords(1).toString()).append(p.logs.joinToString("\n"))
        p.store.root.walkTopDown().filter { it.isFile }.forEach { everything.append(String(it.readBytes(), Charsets.ISO_8859_1)) }
        assertFalse("no IMSI", everything.contains(IMSI))
        assertFalse("no ICCID", everything.contains(ICCID))
        assertFalse("no hash of the ICCID", everything.contains(Files.sha256(ICCID)))
        assertNotNull(e.explain(-1, "build"))
    }

    @Test
    fun theRound29FilesGoAtFirstStart() {
        val dir = Fixture.tempDir("r29")
        for (name in listOf("ocd-layer", "ocd-layer-state.json", "ocd-baseline-1.json", "ocd-layer-salt", "keep.txt")) File(dir, name).writeText("x")
        Store(File(dir, "ocd")).deleteRound29(dir)
        assertEquals(listOf("keep.txt"), dir.list()!!.sorted())
    }

    // --- the pure guard and state --------------------------------------------------------------

    @Test
    fun callRefusals() {
        val now = 10_000_000L
        assertEquals("state_unreadable", Guard.callRefusal(PhoneState(null, false, false, false), 0, 0, now))
        assertEquals("call", Guard.callRefusal(PhoneState(false, true, false, false), 0, 0, now))
        assertEquals("emergency_sms_mode", Guard.callRefusal(PhoneState(false, false, false, true), 0, 0, now))
        assertEquals("call_ended_10min", Guard.callRefusal(PhoneState(false, false, false, false), 0, now - 60_000, now))
        assertNull(Guard.callRefusal(PhoneState(false, false, false, false), 0, now - 11 * 60_000, now))
    }

    @Test
    fun theBootTable() {
        val inst = InstalledRef("sha256:x", APP, "d", "2026-10-25T00:00:00Z", 0, "dataset")
        Guard.bootTarget(State(installed = inst), true, false, false, writerOff = true).let {
            assertEquals("app_disabled", it.reason)
            assertTrue(it.dropInstalled)
        }
        assertEquals("voice_loss_hold", Guard.bootTarget(State(installed = inst, hold = VoiceHold(0, "r", "sha256:x", "v")), true, false, false).reason)
        assertFalse(Guard.bootTarget(State(installed = inst, hold = VoiceHold(0, "r", "sha256:x", "v")), true, false, false).dropInstalled)
        assertTrue(Guard.bootTarget(State(installed = inst, voiceStop = VoiceStop(0, "r", "sha256:x", "i")), true, false, false).dropInstalled)
        assertEquals("crash_guard", Guard.bootTarget(State(installed = inst, crashGuard = CrashGuard("x", 0, false)), true, false, false).reason)
        assertEquals("no_dataset", Guard.bootTarget(State(), true, false, false).reason)
        assertEquals("corrupt", Guard.bootTarget(State(installed = inst), false, false, false).reason)
        assertTrue(Guard.bootTarget(State(installed = inst), true, true, false).dropInstalled)
        assertTrue(Guard.bootTarget(State(installed = inst), true, true, null).waitsForHome)
        assertEquals("installed", Guard.bootTarget(State(installed = inst), true, false, false).target)
        assertTrue(Guard.newer("a", "2026-10-25T00:00:00Z", "b", "2026-10-25T00:00:00Z", xIsBaked = true))
        assertFalse(Guard.newer("a", "2026-10-25T00:00:00Z", "a", "2026-10-26T00:00:00Z", xIsBaked = true))
    }

    @Test
    fun theStateRoundTripsAndFailsSafe() {
        val s = State(
            installed = InstalledRef("sha256:x", APP, "d", "t", 5, "baked"), active = "installed", servingReason = "installed",
            halted = Halted(2, "h"), crashGuard = CrashGuard("c", 3, true), pending = setOf(1, 2),
            served = mapOf(1 to ServedRef("v", "d", 4, true)), x5Served = mapOf(1 to mapOf("carrier_volte_available_bool" to true, "carrier_vt_available_bool" to null)),
            x5TestedHere = mapOf("r:1" to "2027-01-01"), proving = mapOf(1 to "v"), ownInstall = "call", apn = ApnState(7, "s", "d", 8, 9, 10, null),
            writerState = "enabled", tester = TesterDecl(true, 6, "t"), hold = VoiceHold(7, "r", "sha256:x", "sha256:v"), lastHold = VoiceHold(8, "r2", "sha256:y", "sha256:w"),
            voiceStop = VoiceStop(9, "r3", "sha256:z", "eng.1"), blocked = setOf("sha256:z"), notice = "n",
            apnTest = ApnTestSet(1, 10, "in_place", listOf("a:1"), "s1", "s2", "sha256:x", 40, listOf(PreferredApn(1, mapOf("apn" to "internet"), 5)), "registration=1;"),
        )
        assertEquals(s, State.decode(State.encode(s)))
        // A round 30 state with safe mode on keeps the build serving.
        val old = State.encode(State(installed = InstalledRef("sha256:x", APP, "d", "t", 5, "baked"), active = "installed", servingReason = "safe_mode"))
            .replace("\"halted\":null", "\"safe_mode\":{\"since_ms\":1,\"by\":\"adb\",\"reason\":\"r\"},\"gate\":\"narrow\",\"cleared\":true,\"halted\":null")
        val migrated = State.decode(old)!!
        assertNull(migrated.installed)
        assertEquals("no_dataset", migrated.servingReason)
        val dir = Fixture.tempDir("state")
        val store = Store(dir)
        store.stateFile.parentFile.mkdirs()
        store.stateFile.writeText("{not json")
        val read = store.readState(1000)
        assertEquals("corrupt", read.servingReason)
        assertEquals(1000L, read.emergencySeenAtMs)
    }

    // --- round 31: the voice-loss hold ----------------------------------------------------------

    /** An app dataset whose O2 profile differs from the build's (VT off), so the hold has something to hold. */
    private fun differingApp(commit: String = APP, generatedAt: String = "2026-10-25T09:57:41Z"): MutableMap<String, ByteArray> {
        val values = Fixture.VALUES + ("open.26207.c07dcc040b58" to (Fixture.VALUES.getValue("open.26207.c07dcc040b58") + ("carrier_vt_available_bool" to false)))
        return Fixture.files(profiles = Fixture.profilesJson(values = values), rows = ROWS, commit = commit, generatedAt = generatedAt)
    }

    @Test
    fun theVoiceLossHoldServesTheBuildUntilTheSplitReleasesIt() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p, differingApp())
        assertEquals(false, e.load(1, O2).values["carrier_vt_available_bool"])
        val hold = e.voiceLossHold("voice lost; only the build's bundle restored it")
        assertEquals("hold", hold["voice_loss"])
        p.drain()
        assertEquals("voice_loss_hold", e.status()["serving_reason"])
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", p.effective[1])
        // The split reads the held dataset by its install ID, or as "installed".
        val heldId = (e.state().lastJob!!["install_id"] as String)
        for (sel in listOf(heldId, "installed")) {
            val held = Json.parse(e.explain(1, sel)) as Map<*, *>
            assertEquals(sel, "installed", held["dataset"])
            assertEquals(sel, false, ((held["keys"] as Map<*, *>)["carrier_vt_available_bool"] as Map<*, *>)["value"])
        }
        assertEquals("build", (Json.parse(e.explain(1, null)) as Map<*, *>)["dataset"])
        assertEquals(true, e.load(1, O2).values["carrier_vt_available_bool"])
        assertNotNull("the installed dataset is kept", e.state().installed)
        // No install while held; records are stored, served after the release.
        val id = stage(e, appFiles(commit = APP2, generatedAt = "2026-10-25T11:00:00Z"))[Protocol.INSTALL_ID] as String
        p.drain()
        refusal("voice_loss_hold") { e.activate(id) }
        val winner = e.putRecord(recordJson("carrier_vt_available_bool", true))
        assertEquals("stored_not_served:voice_loss_hold", winner[Protocol.EFFECT])
        e.voiceLossRelease("winners", "VT on passed")
        p.drain()
        assertEquals("installed", e.status()["serving"])
        assertEquals("open.26207.c07dcc040b58+ocd${APP.take(7)}", p.effective[1])
        assertEquals(true, e.load(1, O2).values["carrier_vt_available_bool"])
        // Lost again, but under other values now (the winner): a new hold, not a stop.
        assertEquals("hold", e.voiceLossHold("again")["voice_loss"])
        assertNull(e.state().voiceStop)
    }

    @Test
    fun aSecondLossUnderTheSameValuesIsAStop() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p, differingApp())
        val digest = e.state().installed!!.datasetDigest
        assertEquals("hold", e.voiceLossHold("first loss")["voice_loss"])
        p.drain()
        e.voiceLossRelease("no_failing_value", "the split found nothing")
        p.drain()
        assertEquals("installed", e.status()["serving"])
        val stop = e.voiceLossHold("second loss")
        assertEquals("stop", stop["voice_loss"])
        p.drain()
        assertNull(e.state().installed)
        assertEquals("voice_loss_stop", e.status()["serving_reason"])
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", p.effective[1])
        assertTrue(p.notifications.isNotEmpty())
        refusal("voice_loss_stop") { stage(e, appFiles(commit = APP2, generatedAt = "2026-10-25T11:00:00Z")) }
        refusal("voice_loss_stop") { e.putRecord(recordJson("carrier_vt_available_bool", true)) }
        // Resume: that commit is never installed again, a newer one is.
        e.resume()
        refusal("dataset_blocked") { stage(e, differingApp()) }
        assertTrue(digest in e.state().blocked)
        installed(e, p, appFiles(commit = APP2, generatedAt = "2026-10-25T11:00:00Z"))
        assertEquals("installed", e.status()["serving"])
    }

    @Test
    fun aNewerBuildEndsAVoiceLossStop() {
        val (e, p) = setup()
        boot(e, p)
        e.update { it.copy(voiceStop = VoiceStop(p.now, "stop", "sha256:x", "an.older.build")) }
        boot(e, p)
        assertNull(e.state().voiceStop)
    }

    // --- round 31: the default emergency APN test -------------------------------------------------

    private fun sosRow(): ApnXml.Row = ApnXml.parse(Fixture.xml(ROWS)).rows.first { it.attrs["apn"] == "sos" }

    @Test
    fun theEmergencyDefaultSetAndItsRestore() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        p.setPreferredApn(1, p.idOf("internet"))
        val fullRows = p.tpRows.size
        val id = e.emergencyDefaultSet(1)
        p.drain()
        val st = e.installStatus(id)
        assertEquals(st.toString(), "in_place", st[Protocol.STATE])
        assertEquals(listOf(sosRow().rowKey), st["removed_rows"])
        assertEquals(fullRows - 1, p.tpRows.size)
        assertFalse(p.tpRows.any { it.apn == "sos" })
        assertTrue("every other row stays", p.tpRows.any { it.apn == "emergency.volte.ims.cnt.net.ec" })
        assertEquals("the preferred APN is set again after the test set", p.idOf("internet"), p.preferred[1])
        assertEquals("emergency default test set", (e.status()["apn_test_set"] as Map<*, *>)["status"])
        assertEquals("emergency default test set", (Json.parse(e.explain(1, null)) as Map<*, *>)["apn_test_set"])
        assertEquals("open.26207.c07dcc040b58+ocd${APP.take(7)}", p.effective[1])
        // Nothing else installs or reloads while it is in place.
        assertEquals("apn_test_set_in_place", e.reload(setOf(1), "test").second)
        assertEquals("apn_test_set_in_place", refusal("refused_now") { e.applyApnRecords() }.waitingFor)
        // The restore: the full set, the row and the preferred APN back, data and IMS as before.
        val r = e.emergencyDefaultRestore(false)
        p.drain()
        val rs = e.installStatus(r)
        assertEquals(rs.toString(), "restored", rs[Protocol.STATE])
        val checks = Json.parse(rs[Protocol.CHECKS] as String) as List<*>
        assertEquals("the restore's checks for the app", 5, checks.size)
        assertTrue(checks.all { (it as Map<*, *>)["ok"] == true })
        assertEquals(fullRows, p.tpRows.size)
        assertTrue(p.tpRows.any { it.apn == "sos" })
        assertEquals(p.idOf("internet"), p.preferred[1])
        assertNull(e.state().apnTest)
        assertNull(e.state().halted)
        assertEquals(Files.sha512(appFiles().getValue("apns-conf.xml")), p.tpFileSha512)
    }

    @Test
    fun theDefaultWonKeepsTheSetWithoutTheRow() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        e.emergencyDefaultSet(1)
        p.drain()
        refusal("record_invalid") { e.emergencyDefaultRestore(true) }
        e.putRecord(recordJson("", null, valueKind = "none", target = linkedMapOf("type" to "emergency_data", "row_key" to sosRow().rowKey)))
        val r = e.emergencyDefaultRestore(true)
        p.drain()
        assertEquals("kept", e.installStatus(r)[Protocol.STATE])
        assertFalse(p.tpRows.any { it.apn == "sos" })
        assertNull(e.state().apnTest)
    }

    @Test
    fun theRestoreWaitsForACallButNotForTheTenMinutes() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        e.emergencyDefaultSet(1)
        p.drain()
        p.phone = PhoneState(true, false, false, false)
        val r = e.emergencyDefaultRestore(false)
        p.drain()
        assertEquals("waiting", e.installStatus(r)[Protocol.STATE])
        assertEquals("call", e.installStatus(r)[Protocol.WAITING_FOR])
        // The call ended a minute ago: the 10-minute rule does not apply to the restore.
        p.phone = PhoneState(false, false, false, false)
        e.onCallState(idle = true, emergency = null)
        p.now += 60_000
        e.processPending("retry")
        p.drain()
        assertEquals("restored", e.installStatus(r)[Protocol.STATE])
        // An emergency call after the set went in: the restore waits 60 minutes.
        p.now += 11 * 60_000
        val again = e.emergencyDefaultSet(1)
        p.drain()
        assertEquals("in_place", e.installStatus(again)[Protocol.STATE])
        p.emergencyRecent = true
        val r2 = e.emergencyDefaultRestore(false)
        p.drain()
        assertEquals("emergency_call_60min", e.installStatus(r2)[Protocol.WAITING_FOR])
    }

    @Test
    fun theTestSetRefusals() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        refusal("apn_test_set_not_in_place") { e.emergencyDefaultRestore(false) }
        p.roamingValue = true
        assertEquals("roaming", refusal("refused_now") { e.emergencyDefaultSet(1) }.waitingFor)
        p.roamingValue = false
        p.userRows = listOf(ApnLogic.DbRow("262", "07", "sos", "emergency", "", "", "IPV4V6", "IPV4V6", -1, 1))
        val id = e.emergencyDefaultSet(1)
        p.drain()
        assertEquals("user_edited_row", e.installStatus(id)[Protocol.ERROR])
        p.userRows = emptyList()
        // A SIM without an emergency row of its own.
        p.sims = listOf(ActiveSim(2, 1, SimId("262", "02", spn = "Lidl Connect", imsi = "262020000000002")))
        val none = e.emergencyDefaultSet(2)
        p.drain()
        assertEquals("no_emergency_row", e.installStatus(none)[Protocol.ERROR])
        assertNull(e.state().apnTest)
    }

    @Test
    fun aRebootWithTheTestSetInPlaceRestoresAndStops() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        e.emergencyDefaultSet(1)
        p.drain()
        assertFalse(p.tpRows.any { it.apn == "sos" })
        val after = e.bootCheck("locked_boot_completed")
        p.drain()
        assertTrue(after, after.contains("restored"))
        assertTrue(p.tpRows.any { it.apn == "sos" })
        assertNotNull("a stop event", e.state().halted)
        assertTrue(e.state().halted!!.reason.contains("rebooted"))
        assertTrue(p.notifications.isNotEmpty())
    }

    @Test
    fun aRestoreWithoutImsIsAStopEvent() {
        val (e, p) = setup()
        boot(e, p)
        installed(e, p)
        e.emergencyDefaultSet(1)
        p.drain()
        p.ims = "registration=2;"
        val r = e.emergencyDefaultRestore(false)
        p.drain()
        assertEquals("restore_failed", e.installStatus(r)[Protocol.STATE])
        assertEquals("restore_failed", e.installStatus(r)[Protocol.ERROR])
        val failed = (Json.parse(e.installStatus(r)[Protocol.CHECKS] as String) as List<*>).filter { (it as Map<*, *>)["ok"] == false }
        assertEquals(1, failed.size)
        assertNotNull(e.state().halted)
        assertTrue(p.tpRows.any { it.apn == "sos" })
    }

    @Test
    fun aRestoreThatDoesNotShowFallsBackToTheBuildsApnFile() {
        val (e, p) = setup()
        boot(e, p)
        val app = appFiles(rows = ROWS.dropLast(1))
        installed(e, p, app)
        e.emergencyDefaultSet(1)
        p.drain()
        p.refuseSha = setOf(Files.sha512(app.getValue("apns-conf.xml")))
        val r = e.emergencyDefaultRestore(false)
        p.drain()
        assertEquals("restore_failed", e.installStatus(r)[Protocol.STATE])
        assertNotNull(e.state().halted)
        assertEquals(Files.sha512(File(p.bakedDir, "apns-conf.xml").readBytes()), p.tpFileSha512)
        assertNull("both parts from the build", e.state().installed)
        assertEquals("open.26207.c07dcc040b58+ocd${BAKED.take(7)}", p.effective[1])
    }

    @Test
    fun everyApnInstallSetsThePreferredApnAgain() {
        val (e, p) = setup()
        boot(e, p)
        p.setPreferredApn(1, p.idOf("ims"))
        installed(e, p, appFiles(rows = ROWS.dropLast(1)))
        assertEquals("the app's dataset install", p.idOf("ims"), p.preferred[1])
        p.writerValue = Writer(Protocol.OWNER_PACKAGE, "disabled")
        e.appCheck("test")
        p.drain()
        assertEquals("the build's dataset with the app disabled", p.idOf("ims"), p.preferred[1])
    }

    // --- round 31: SM7, the tester, timing ---------------------------------------------------------

    @Test
    fun theTesterDeclarationAndSm7() {
        val (e, p) = setup()
        boot(e, p)
        assertEquals("the owner flavor counts as a tester", false, e.status()["sm7"])
        e.setTester(false, "the person did not opt in")
        p.drain()
        assertEquals(true, e.status()["sm7"])
        assertEquals(true, (Json.parse(e.explain(1, null)) as Map<*, *>)["sm7"])
        p.writerValue = Writer(Protocol.PLAIN_PACKAGE, "enabled")
        e.setTester(true, "opted in")
        assertEquals(false, e.status()["sm7"])
        e.update { it.copy(tester = null) }
        assertEquals("another writer needs its declaration", true, e.status()["sm7"])
        p.writerValue = Writer(Protocol.OWNER_PACKAGE, "enabled")
        p.cellValue = Fixture.CELL.copy(stackId = "phh_ims")
        assertEquals("an uncalibrated stack (SM8)", true, e.status()["sm7"])
    }

    @Test
    fun noInstallWithinAnHourOfAnEmergencyCall() {
        val (e, p) = setup()
        boot(e, p)
        val id = stage(e, appFiles())[Protocol.INSTALL_ID] as String
        p.drain()
        e.onCallState(idle = false, emergency = true)
        e.onCallState(idle = true, emergency = null)
        p.now += 30 * 60_000
        assertEquals("emergency_call_60min", refusal("refused_now") { e.activate(id) }.waitingFor)
        p.now += 31 * 60_000
        e.activate(id)
        p.drain()
        assertEquals("installed", e.installStatus(id)[Protocol.STATE])
    }
}
