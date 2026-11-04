package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.owner.core.ActivationMoment
import io.github.open_carrier_data.check.owner.core.CallSeen
import io.github.open_carrier_data.check.owner.core.CallsSeen
import io.github.open_carrier_data.check.owner.core.ChangesHalted
import io.github.open_carrier_data.check.owner.core.DatasetRef
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.HeldSet
import io.github.open_carrier_data.check.owner.core.IdleBlock
import io.github.open_carrier_data.check.owner.core.IdleInputs
import io.github.open_carrier_data.check.owner.core.IdleMoment
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.InstallView
import io.github.open_carrier_data.check.owner.core.MainCheck
import io.github.open_carrier_data.check.owner.core.MainCheckState
import io.github.open_carrier_data.check.owner.core.MainNet
import io.github.open_carrier_data.check.owner.core.MainRetry
import io.github.open_carrier_data.check.owner.core.MainWait
import io.github.open_carrier_data.check.owner.core.ManifestHint
import io.github.open_carrier_data.check.owner.core.ManifestMark
import io.github.open_carrier_data.check.owner.core.ManifestRead
import io.github.open_carrier_data.check.owner.core.PulledDataset
import io.github.open_carrier_data.check.owner.core.VoiceLossState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Round 47 item 3: the 15-minute check of main, its limits, its waits and the moment a new dataset goes in. */
class MainCheckTest {
    private val now = 1_800_000_000_000L
    private val min = TimeUnit.MINUTES.toMillis(1)
    private val hour = TimeUnit.HOURS.toMillis(1)

    private fun digest(c: Char) = "sha256:" + c.toString().repeat(64)
    private val a = ManifestHint(digest('a'), digest('1'))
    private val b = ManifestHint(digest('b'), digest('2'))

    private val wifi = MainNet(unmetered = true, roaming = false, dataSaver = false)
    private val metered = MainNet(unmetered = false, roaming = false, dataSaver = false)
    private val heldA = HeldSet(pinned = true, pulled = a, installed = a)

    private fun due(read: ManifestRead, m: MainCheckState = MainCheckState(), held: HeldSet = heldA, net: MainNet = wifi, pullAgain: Boolean = false, lastPull: Long? = now - hour, lastMetered: Long? = null) =
        MainCheck.pullDue(read, m, held, net, pullAgain, lastPull, lastMetered, now)

    private fun fresh(hint: ManifestHint?, etag: String? = "\"e\"") = ManifestRead.Fresh(etag, hint)

    @Test
    fun `only a difference starts a pull, by either digest`() {
        assertFalse(due(fresh(a)))
        assertTrue(due(fresh(b)))
        assertTrue("the same dataset digest, another data digest", due(fresh(a.copy(dataDigest = b.dataDigest))))
        assertTrue("the same data digest, another dataset digest", due(fresh(a.copy(datasetDigest = b.datasetDigest))))
    }

    @Test
    fun `the set pulled and waiting to go in counts, the installed one only when none was pulled`() {
        // Pulled and waiting for its moment: no second pull each run.
        assertFalse(due(fresh(b), held = HeldSet(true, pulled = b, installed = a)))
        // Nothing pulled yet: the installed set.
        assertFalse(due(fresh(a), held = HeldSet(true, pulled = null, installed = a)))
        // main went back to the installed values before the pulled set went in: pull, or the bad set would go in.
        assertTrue(due(fresh(a), held = HeldSet(true, pulled = b, installed = a)))
        // Without a main pin the app has no APN rows of its own: the first pull is due whatever is installed.
        assertTrue(due(fresh(a), held = HeldSet(false, pulled = null, installed = a)))
    }

    @Test
    fun `the ETag is not stored before a pull for its digests finished`() {
        val read = fresh(b, "\"new\"")
        // The pull found the commit still in validate: the phone still holds a.
        val pending = MainCheck.settled(MainCheckState(), read, heldA, pulled = true, nowMs = now)
        assertNull(pending.mark)
        // A metered phone over its daily limit did not pull at all.
        assertNull(MainCheck.settled(MainCheckState(), read, heldA, pulled = false, nowMs = now).mark)
        // The older mark stays as it was: a 200 follows, as the ETag differs.
        val old = ManifestMark("\"old\"", a)
        assertEquals(old, MainCheck.settled(MainCheckState(mark = old), read, heldA, pulled = true, nowMs = now).mark)
        // Finished: the phone holds the digests, and only then the ETag is kept.
        val done = MainCheck.settled(pending, read, HeldSet(true, pulled = b, installed = a), pulled = true, nowMs = now)
        assertEquals(ManifestMark("\"new\"", b), done.mark)
        assertNull(done.retry)
    }

    @Test
    fun `an answer that needs no pull stores its ETag, and one without an ETag stores nothing`() {
        assertEquals(ManifestMark("\"e\"", a), MainCheck.settled(MainCheckState(), fresh(a), heldA, pulled = false, nowMs = now).mark)
        assertNull(MainCheck.settled(MainCheckState(), fresh(a, etag = null), heldA, pulled = false, nowMs = now).mark)
    }

    @Test
    fun `a 304 with the stored digests held means nothing to do`() {
        assertFalse(due(ManifestRead.Same, MainCheckState(mark = ManifestMark("\"e\"", a))))
    }

    @Test
    fun `a 304 with no stored digest match does not stop a retry`() {
        // The phone no longer holds what the ETag was stored for.
        assertTrue(due(ManifestRead.Same, MainCheckState(mark = ManifestMark("\"e\"", b))))
        // No mark at all: the answer says nothing about the phone's set.
        assertTrue(due(ManifestRead.Same, MainCheckState()))
        val after = MainCheck.settled(MainCheckState(mark = ManifestMark("\"e\"", b)), ManifestRead.Same, heldA, pulled = true, nowMs = now)
        assertEquals(b.datasetDigest, after.retry?.datasetDigest)
    }

    @Test
    fun `a commit still in validate is tried again on the next run`() {
        val first = MainCheck.settled(MainCheckState(), fresh(b), heldA, pulled = true, nowMs = now)
        assertFalse(MainCheck.BACKOFF_FROM_MS >= TimeUnit.MINUTES.toMillis(MainCheck.PERIOD_MINUTES - MainCheck.FLEX_MINUTES + 1))
        assertTrue(MainCheck.pullDue(fresh(b), first, heldA, wifi, false, now, null, now + MainCheck.BACKOFF_FROM_MS))
    }

    @Test
    fun `a pull that does not bring the digest waits longer each time, and a new digest starts over`() {
        var m = MainCheckState()
        val waits = mutableListOf<Long>()
        repeat(8) {
            m = MainCheck.settled(m, fresh(b), heldA, pulled = true, nowMs = now)
            waits += m.retry!!.wait.untilMs - now
        }
        assertEquals(listOf(10, 20, 40, 80, 160, 320, 360, 360).map { it * min }, waits)
        // A refused commit costs no full download each run.
        assertFalse(MainCheck.pullDue(fresh(b), m, heldA, wifi, false, now, null, now + hour))
        val c = ManifestHint(digest('c'), digest('3'))
        assertTrue(MainCheck.pullDue(fresh(c), m, heldA, wifi, false, now, null, now + hour))
        assertEquals(1, MainCheck.settled(m, fresh(c), heldA, pulled = true, nowMs = now).retry?.wait?.failures)
    }

    @Test
    fun `a manifest that cannot be read keeps the old daily pull as the floor`() {
        assertFalse(due(fresh(null), lastPull = now - hour))
        assertTrue(due(fresh(null), lastPull = now - 21 * hour))
        assertNull(MainCheck.settled(MainCheckState(), fresh(null), heldA, pulled = true, nowMs = now).mark)
    }

    @Test
    fun `the hint reads two digests and refuses nothing a pull would judge`() {
        val text = """{"schema_version":99,"new_member":1,"dataset_digest":"${a.datasetDigest}","data_digest":"${a.dataDigest}"}"""
        assertEquals(a, MainCheck.hint(text))
        for (bad in listOf("", "[]", "{", """{"dataset_digest":"${a.datasetDigest}"}""", """{"dataset_digest":"x","data_digest":"${a.dataDigest}"}""")) assertNull(bad, MainCheck.hint(bad))
    }

    @Test
    fun `the branch read names the manifest on the raw host and nothing else`() {
        assertEquals("https://raw.githubusercontent.com/open-carrier-data/open-carrier-data/main/generated/ocd-dataset/manifest.json", MainCheck.MANIFEST_URL)
    }

    @Test
    fun `unmetered reads each run, a hotspot counts as metered`() {
        assertTrue(MainCheck.mayRead(MainCheckState(lastMeteredCheckAtMs = now - min), wifi, now))
        // A phone's hotspot is Wi-Fi without NET_CAPABILITY_NOT_METERED, so only the capability is an input.
        val hotspot = MainNet(unmetered = false, roaming = false, dataSaver = false)
        assertFalse(MainCheck.mayRead(MainCheckState(lastMeteredCheckAtMs = now - min), hotspot, now))
        assertFalse(due(fresh(b), net = hotspot, lastMetered = now - hour))
        assertFalse("no network: nothing is read", MainCheck.mayRead(MainCheckState(), MainNet(null, false, false), now))
        assertFalse(due(fresh(b), net = MainNet(null, false, false)))
    }

    @Test
    fun `a roaming phone on an unmetered network may read and pull, it installs at home`() {
        val abroad = wifi.copy(roaming = true, dataSaver = true)
        assertTrue(MainCheck.mayRead(MainCheckState(), abroad, now))
        assertTrue(due(fresh(b), net = abroad))
    }

    @Test
    fun `a metered phone checks every 6 hours`() {
        assertTrue(MainCheck.mayRead(MainCheckState(), metered, now))
        assertFalse(MainCheck.mayRead(MainCheckState(lastMeteredCheckAtMs = now - 5 * hour), metered, now))
        assertTrue(MainCheck.mayRead(MainCheckState(lastMeteredCheckAtMs = now - 6 * hour), metered, now))
        assertTrue("the clock went back", MainCheck.mayRead(MainCheckState(lastMeteredCheckAtMs = now + hour), metered, now))
    }

    @Test
    fun `a metered phone is limited to one pull a day`() {
        assertTrue(due(fresh(b), net = metered, lastMetered = null))
        assertFalse(due(fresh(b), net = metered, lastMetered = now - 23 * hour))
        assertTrue(due(fresh(b), net = metered, lastMetered = now - 24 * hour))
        assertFalse("no difference, no pull", due(fresh(a), net = metered, lastMetered = null))
    }

    @Test
    fun `a metered phone neither checks nor pulls while roaming or with Data Saver on`() {
        for (net in listOf(metered.copy(roaming = true), metered.copy(dataSaver = true), metered.copy(roaming = null), metered.copy(dataSaver = null))) {
            assertFalse(net.toString(), MainCheck.mayRead(MainCheckState(), net, now))
            assertFalse(net.toString(), due(fresh(b), net = net))
        }
    }

    @Test
    fun `pullAgain lifts the daily limit and nothing else`() {
        assertTrue(due(fresh(b), net = metered, pullAgain = true, lastMetered = now - hour))
        for (net in listOf(metered.copy(roaming = true), metered.copy(dataSaver = true), metered.copy(roaming = null), metered.copy(dataSaver = null))) {
            assertFalse(net.toString(), due(fresh(b), net = net, pullAgain = true, lastMetered = null))
            assertFalse(net.toString(), MainCheck.mayRead(MainCheckState(), net, now))
        }
        // Checks stay at 6 hours, and the job's read has no pullAgain to ask.
        assertFalse(MainCheck.mayRead(MainCheckState(lastMeteredCheckAtMs = now - hour), metered, now))
        // Asked again with nothing new on main: not each run, as CarrierSettings keeps asking until a new set is proven.
        assertTrue(due(fresh(a), pullAgain = true, lastPull = now - 6 * hour))
        assertFalse(due(fresh(a), pullAgain = true, lastPull = now - 5 * hour))
    }

    @Test
    fun `backoff doubles and stops at 6 hours`() {
        var wait: MainWait? = null
        val waits = mutableListOf<Long>()
        repeat(9) {
            wait = MainCheck.next(wait, null, now)
            waits += wait!!.untilMs - now
        }
        assertEquals(listOf(10, 20, 40, 80, 160, 320, 360, 360, 360).map { it * min }, waits)
        assertEquals(360 * min, MainCheck.next(MainWait(10_000, 0), null, now).untilMs - now)
    }

    @Test
    fun `Retry-After is honoured, in both forms`() {
        assertEquals(120_000L, MainCheck.retryAfterMs(" 120 ", now))
        assertEquals(0L, MainCheck.retryAfterMs("-5", now))
        // 2026-11-04 12:00:00 UTC is the clock of this case.
        val noon = java.time.Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()
        assertEquals(90_000L, MainCheck.retryAfterMs("Wed, 04 Nov 2026 12:01:30 GMT", noon))
        assertEquals(0L, MainCheck.retryAfterMs("Wed, 04 Nov 2026 11:00:00 GMT", noon))
        assertNull(MainCheck.retryAfterMs(null, now))
        assertNull(MainCheck.retryAfterMs("soon", now))
        // Longer than the doubled wait: it wins, past 6 hours too.
        assertEquals(hour, MainCheck.next(null, hour, now).untilMs - now)
        assertEquals(8 * hour, MainCheck.next(null, 8 * hour, now).untilMs - now)
        // Shorter: the doubled wait wins.
        assertEquals(10 * min, MainCheck.next(null, min, now).untilMs - now)
        // Nothing stops the updates for longer than a day.
        assertEquals(24 * hour, MainCheck.next(null, 100 * 24 * hour, now).untilMs - now)
        assertEquals(24 * hour, MainCheck.retryAfterMs("99999999999999999", now))
    }

    @Test
    fun `while the wait runs nothing is read, on any network, and it ends by itself`() {
        val m = MainCheckState(limit = MainWait(1, now + hour))
        assertFalse(MainCheck.mayRead(m, wifi, now))
        assertFalse(MainCheck.mayRead(m, metered, now))
        assertTrue(MainCheck.mayRead(m, wifi, now + hour))
        assertTrue("a clock that went back ends it", MainCheck.mayRead(MainCheckState(limit = MainWait(1, now + 30 * 24 * hour)), wifi, now))
    }

    @Test
    fun `one pull's refusals double once`() {
        val first = MainCheck.limited(null, null, now)
        val second = MainCheck.limited(first, null, now + 1_000)
        assertEquals(first, second)
        assertEquals(now + 1_000 + hour, MainCheck.limited(first, hour, now + 1_000).untilMs)
        assertEquals(2, MainCheck.limited(first, null, first.untilMs).failures)
    }

    @Test
    fun `a good raw read does not end the wait while the API still refuses`() {
        // The wait is over, raw answers, the difference starts a pull, and the API refuses: the count goes on.
        val over = MainWait(3, now - 1)
        val refused = MainCheck.limited(over, null, now)
        assertEquals(4, refused.failures)
        assertEquals(refused, MainCheck.settled(MainCheckState(limit = refused), fresh(b), heldA, pulled = true, nowMs = now).limit)
        // The read alone says nothing about the API.
        assertEquals(over, MainCheck.settled(MainCheckState(limit = over), fresh(b), heldA, pulled = false, nowMs = now).limit)
        // The pull went through, or none was needed: it ends.
        assertNull(MainCheck.settled(MainCheckState(limit = over), fresh(b), HeldSet(true, b, a), pulled = true, nowMs = now).limit)
        assertNull(MainCheck.settled(MainCheckState(limit = over), fresh(a), heldA, pulled = false, nowMs = now).limit)
    }

    @Test
    fun `the protocol 3 path keeps its daily pull`() {
        val p3 = DatasetStatus(available = true, protocol = 3)
        val p4 = DatasetStatus(available = true, protocol = 4)
        assertTrue(MainCheck.quietJobPulls(p3))
        assertTrue(MainCheck.quietJobPulls(null))
        assertTrue(MainCheck.quietJobPulls(DatasetStatus.absent("not on this ROM build")))
        assertFalse(MainCheck.quietJobPulls(p4))
        val work = source("OwnerWork.kt")
        // The quiet job's gates are today's, behind one condition; the new job stops before any read without skip-notify.
        assertTrue(work.contains("if (MainCheck.quietJobPulls(state.datasetStatus) && (look.wifiConnected == true || meteredOk) && (pullDue || state.datasetStatus?.pullAgain == true)) {"))
        assertTrue(work.contains("val PULL_EVERY_MS: Long = TimeUnit.HOURS.toMillis(20)"))
        val job = work.substringAfter("private suspend fun mainCheck(").substringBefore("     * The hourly quiet job")
        assertTrue(job.indexOf("if (!status.skipsUnchanged) return Result.success()") in 0 until job.indexOf("data.mainManifest("))
        val dataset = source("OwnerDataset.kt")
        assertTrue(dataset.contains("if (status.pullAgain && !status.skipsUnchanged) OwnerScheduler.pull(context, force = true)"))
        assertTrue(dataset.contains("val activatesFirst = !testNow && status.skipsUnchanged && !status.voiceLoss.hold"))
    }

    @Test
    fun `the job has a network constraint and jitter, and no idle or charging constraint`() {
        val work = source("OwnerWork.kt")
        val job = work.substringAfter("private fun ensureMainCheck(").substringBefore("    /** Whether a test chain is queued or running. */")
        assertTrue(job.contains("PeriodicWorkRequestBuilder<OwnerWorker>(MainCheck.PERIOD_MINUTES, TimeUnit.MINUTES, MainCheck.FLEX_MINUTES, TimeUnit.MINUTES)"))
        assertTrue(job.contains("setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(job.contains("setInitialDelay(Random.nextLong("))
        assertFalse(job.contains("Idle"))
        assertFalse(job.contains("Charging"))
        assertEquals(15L, MainCheck.PERIOD_MINUTES)
        assertTrue(work.substringBefore("private fun ensureMainCheck(").contains("wm.cancelUniqueWork(MAIN_CHECK)"))
    }

    @Test
    fun `metered is read from the capability, not from Wi-Fi being connected`() {
        val quiet = source("OwnerQuiet.kt").substringAfter("fun unmetered(").substringBefore("fun dataSaver(")
        assertTrue(quiet.contains("NET_CAPABILITY_NOT_METERED"))
        assertFalse(quiet.contains("Wifi"))
        assertFalse(source("OwnerWork.kt").substringAfter("private suspend fun mainCheck(").substringBefore("     * The hourly quiet job").contains("wifiConnected"))
    }

    private val idle = IdleInputs(
        interactive = false, screenOffForMs = hour, inCall = false, lastCallSeenAtMs = null, mediaActive = false, recording = false,
        ecbm = false, emergencySmsMode = false, emergencySeenAtMs = null, upMs = 10 * hour, batteryPercent = 80, charging = false, roaming = false, nowMs = now,
    )

    @Test
    fun `an activation needs no 2 minutes after the screen went off, no battery and no charger`() {
        assertNull(ActivationMoment.blocker(idle))
        assertNull(ActivationMoment.blocker(idle.copy(screenOffForMs = 0)))
        assertNull(ActivationMoment.blocker(idle.copy(screenOffForMs = null)))
        assertNull(ActivationMoment.blocker(idle.copy(batteryPercent = 3, charging = false)))
        assertNull(ActivationMoment.blocker(idle.copy(batteryPercent = null, charging = null)))
        // Tests keep the whole idle moment.
        assertEquals(IdleBlock.SCREEN_ON_RECENTLY, IdleMoment.blocker(idle.copy(screenOffForMs = 0)))
        assertEquals(IdleBlock.BATTERY_LOW, IdleMoment.blocker(idle.copy(batteryPercent = 3)))
    }

    @Test
    fun `every other condition stops an activation, an unreadable one too`() {
        val cases = mapOf(
            idle.copy(interactive = true) to IdleBlock.SCREEN_ON,
            idle.copy(interactive = null) to IdleBlock.SCREEN_ON,
            idle.copy(inCall = true) to IdleBlock.IN_CALL,
            idle.copy(inCall = null) to IdleBlock.IN_CALL,
            idle.copy(lastCallSeenAtMs = now - 9 * min) to IdleBlock.CALL_RECENT,
            idle.copy(mediaActive = true) to IdleBlock.MEDIA,
            idle.copy(mediaActive = null) to IdleBlock.MEDIA,
            idle.copy(recording = true) to IdleBlock.MEDIA,
            idle.copy(ecbm = true) to IdleBlock.EMERGENCY_MODE,
            idle.copy(ecbm = null) to IdleBlock.EMERGENCY_MODE,
            idle.copy(emergencySmsMode = true) to IdleBlock.EMERGENCY_MODE,
            idle.copy(emergencySmsMode = null) to IdleBlock.EMERGENCY_MODE,
            // 60 minutes after an emergency call, by the sighting and by the call watch.
            idle.copy(emergencySeenAtMs = now - 59 * min) to IdleBlock.EMERGENCY_RECENT,
            idle.copy(callsSeen = CallsSeen(0, listOf(CallSeen(now - 70 * min, now - 59 * min, emergency = true)))) to IdleBlock.EMERGENCY_RECENT,
            // The first hour after a restart, unless the call watch rules a call out.
            idle.copy(upMs = 30 * min) to IdleBlock.EMERGENCY_RECENT,
            idle.copy(roaming = true) to IdleBlock.ROAMING,
            idle.copy(roaming = null) to IdleBlock.ROAMING,
        )
        for ((inputs, block) in cases) assertEquals(inputs.toString(), block, ActivationMoment.blocker(inputs))
        assertNull(ActivationMoment.blocker(idle.copy(emergencySeenAtMs = now - 61 * min)))
        assertNull(ActivationMoment.blocker(idle.copy(lastCallSeenAtMs = now - 11 * min)))
        assertNull(ActivationMoment.blocker(idle.copy(upMs = 30 * min, callsSeen = CallsSeen(0))))
        assertEquals(setOf(IdleBlock.SCREEN_ON_RECENTLY, IdleBlock.BATTERY_LOW), ActivationMoment.DROPPED)
    }

    @Test
    fun `the activation goes first, under every SIM's refusals, and the tests keep the idle moment`() {
        val run = source("OwnerDataset.kt").substringAfter("suspend fun run(").substringBefore("     * Round 31 (R31.5)")
        val gate = run.indexOf("activationBlock(context, store, phone, present)")
        val activate = run.indexOf("activate(context, store, phone, client, present)")
        val idleStep = run.indexOf("idleBlock(context, store, phone, present)")
        assertTrue(gate in 0 until activate)
        assertTrue(activate < idleStep)
        assertTrue(idleStep < run.indexOf("campaigns(context, store, phone, client, present, testNow, deadline)"))
        assertTrue(source("OwnerDataset.kt").contains("ownerBlock(store, phone, sims) ?: ActivationMoment.blocker(idleInputs(context, store, phone, sims))?.plain"))
    }

    private fun pulled(hint: ManifestHint) = PulledDataset("c".repeat(40), hint.dataDigest, hint.datasetDigest, "2026-11-04T00:00:00Z", "absent", emptyList(), 0, 0, "x", "")

    private val status = DatasetStatus(available = true, protocol = 4, installed = DatasetRef("c", a.dataDigest, a.datasetDigest))

    private fun waits(p: PulledDataset? = pulled(b), install: InstallView? = null, s: DatasetStatus = status, stopped: Boolean = false, screenOn: Boolean? = false, roaming: Boolean? = false) =
        MainCheck.activationWaits(p, install, s, stopped, screenOn, roaming)

    @Test
    fun `the job queues the dataset step only when a pulled set can go in`() {
        assertTrue(waits())
        assertFalse("nothing pulled", waits(p = null))
        assertFalse("already in", waits(p = pulled(a)))
        assertFalse("protocol 3 keeps the quiet job's timing", waits(s = status.copy(protocol = 3)))
        assertFalse("screen on", waits(screenOn = true))
        assertFalse("screen unknown", waits(screenOn = null))
        assertFalse("roaming", waits(roaming = true))
        assertFalse("roaming unknown", waits(roaming = null))
        assertFalse("stop flag", waits(stopped = true))
        assertFalse("halted", waits(s = status.copy(changesHalted = ChangesHalted(true))))
        assertFalse("hold", waits(s = status.copy(voiceLoss = VoiceLossState(hold = true))))
        assertFalse("blocked", waits(s = status.copy(voiceLoss = VoiceLossState(blocked = listOf(b.datasetDigest)))))
    }

    @Test
    fun `an install that ended is left to the quiet job, a verified or superseded one is not`() {
        fun install(state: String) = InstallView("i", state, datasetDigest = b.datasetDigest)
        assertFalse(waits(install = install(InstallState.REFUSED)))
        assertFalse(waits(install = install(InstallState.RELOAD_NOT_PROVEN)))
        assertTrue(waits(install = install(InstallState.VERIFIED)))
        assertTrue(waits(install = install(InstallState.CANCELLED)))
        assertTrue(waits(install = install(InstallState.SUPERSEDED)))
        assertTrue("an older set's end says nothing", waits(install = InstallView("i", InstallState.REFUSED, datasetDigest = a.datasetDigest)))
    }

    @Test
    fun `the state keeps the job's marks`() {
        val state = io.github.open_carrier_data.check.owner.core.OwnerState(mainCheck = MainCheckState(ManifestMark("\"e\"", a), MainWait(2, now), MainRetry(b.datasetDigest, MainWait(1, now)), now))
        assertEquals(state.mainCheck, io.github.open_carrier_data.check.owner.core.OwnerState.decode(state.encode()).mainCheck)
        // A state file from before round 47.
        assertEquals(MainCheckState(), io.github.open_carrier_data.check.owner.core.OwnerState.decode("{}").mainCheck)
    }

    private fun source(name: String) = File("src/owner/java/io/github/open_carrier_data/check/owner/$name").readText()
}
