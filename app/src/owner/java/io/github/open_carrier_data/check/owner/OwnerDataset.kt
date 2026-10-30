package io.github.open_carrier_data.check.owner

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.SystemClock
import android.telephony.CarrierConfigManager
import android.util.Log
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.owner.core.CallEvidence
import io.github.open_carrier_data.check.owner.core.CallsOutDue
import io.github.open_carrier_data.check.owner.core.Campaign
import io.github.open_carrier_data.check.owner.core.CampaignEnd
import io.github.open_carrier_data.check.owner.core.ApnFailures
import io.github.open_carrier_data.check.owner.core.MmsProbe
import io.github.open_carrier_data.check.owner.core.sameCard
import io.github.open_carrier_data.check.owner.core.CampaignPlanner
import io.github.open_carrier_data.check.owner.core.Candidate
import io.github.open_carrier_data.check.owner.core.Candidates
import io.github.open_carrier_data.check.owner.core.CapEvent
import io.github.open_carrier_data.check.owner.core.CcDump
import io.github.open_carrier_data.check.owner.core.Changed
import io.github.open_carrier_data.check.owner.core.DataPin
import io.github.open_carrier_data.check.owner.core.DatasetCheck
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.DatasetFiles
import io.github.open_carrier_data.check.owner.core.DatasetPull
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.DeviceKeys
import io.github.open_carrier_data.check.owner.core.StackCalibration
import io.github.open_carrier_data.check.owner.core.TesterPolicy
import io.github.open_carrier_data.check.owner.core.ExplainView
import io.github.open_carrier_data.check.owner.core.Fetch
import io.github.open_carrier_data.check.owner.core.Groups
import io.github.open_carrier_data.check.owner.core.IdleBlock
import io.github.open_carrier_data.check.owner.core.IdleInputs
import io.github.open_carrier_data.check.owner.core.DatasetRetry
import io.github.open_carrier_data.check.owner.core.IdleMoment
import io.github.open_carrier_data.check.owner.core.RestartWindow
import io.github.open_carrier_data.check.owner.core.InstallRefusal
import io.github.open_carrier_data.check.owner.core.InstallState
import io.github.open_carrier_data.check.owner.core.InstallView
import io.github.open_carrier_data.check.owner.core.MailboxCaller
import io.github.open_carrier_data.check.owner.core.MeasuredRecord
import io.github.open_carrier_data.check.owner.core.Notice
import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.OcdDataProtocol
import io.github.open_carrier_data.check.owner.core.TrialOutcome
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.PulledDataset
import io.github.open_carrier_data.check.owner.core.QuietWindow
import io.github.open_carrier_data.check.owner.core.RecordCarrier
import io.github.open_carrier_data.check.owner.core.RecordCell
import io.github.open_carrier_data.check.owner.core.RecordCheck
import io.github.open_carrier_data.check.owner.core.RecordInput
import io.github.open_carrier_data.check.owner.core.RecordResult
import io.github.open_carrier_data.check.owner.core.RecordTarget
import io.github.open_carrier_data.check.owner.core.RecordValue
import io.github.open_carrier_data.check.owner.core.Refusal
import io.github.open_carrier_data.check.owner.core.SafetyException
import io.github.open_carrier_data.check.owner.core.SelfSms
import io.github.open_carrier_data.check.owner.core.Settled
import io.github.open_carrier_data.check.owner.core.Settlement
import io.github.open_carrier_data.check.owner.core.SourceReview
import io.github.open_carrier_data.check.owner.core.StageRequest
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.TestCaps
import io.github.open_carrier_data.check.owner.core.TestClass
import io.github.open_carrier_data.check.owner.core.TestsDue
import io.github.open_carrier_data.check.owner.core.Trial30
import io.github.open_carrier_data.check.owner.core.TrialGuard
import io.github.open_carrier_data.check.owner.core.TrialJudge
import io.github.open_carrier_data.check.owner.core.TrialObs
import io.github.open_carrier_data.check.owner.core.TrialPlan
import io.github.open_carrier_data.check.owner.core.TrialVerdict
import io.github.open_carrier_data.check.owner.core.TypedOverride
import io.github.open_carrier_data.check.owner.core.TypedValues
import io.github.open_carrier_data.check.owner.core.VoiceLoss
import io.github.open_carrier_data.check.owner.core.X5Test
import io.github.open_carrier_data.check.owner.core.update
import io.github.open_carrier_data.check.owner.core.voiceLost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A Bundle as JSON: what the installer answered, JSON strings opened, nested Bundles as objects. File descriptors are never read. */
object BundleJson {
    fun of(b: Bundle): JsonObject = JsonObject(
        b.keySet().associateWith { key ->
            @Suppress("DEPRECATION")
            value(b.get(key))
        },
    )

    private fun value(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Float -> JsonPrimitive(v.toDouble())
        is String -> DataAnswer.open(v)
        is Bundle -> of(v)
        is Array<*> -> JsonArray(v.map { value(it) })
        is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
        is LongArray -> JsonArray(v.map { JsonPrimitive(it) })
        is BooleanArray -> JsonArray(v.map { JsonPrimitive(it) })
        is ParcelFileDescriptor -> JsonNull
        else -> JsonPrimitive(v.toString().take(240))
    }
}

/**
 * The installer's provider in the ROM's CarrierSettings (`content://com.android.carriersettings.ocddata`,
 * OCD round 30), reached with ContentResolver.call. On a ROM without it every call answers absent;
 * a refusal of this caller answers `forbidden_for_caller`.
 */
class ProviderDataset(private val context: Context) : DatasetClient {
    private val uri = Uri.parse(OcdDataProtocol.URI)

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): DataAnswer {
        val client = runCatching { context.contentResolver.acquireUnstableContentProviderClient(uri) }.getOrNull()
            ?: return DataAnswer.absent("Open Carrier Data's dataset installer is not on this ROM build")
        return try {
            client.call(method, arg, extras)?.let { DataAnswer(BundleJson.of(it)) } ?: refusal("no_answer", "$method gave no answer")
        } catch (e: SecurityException) {
            Log.w(TAG, "the dataset installer refused this app: ${e.message?.take(120)}")
            refusal("forbidden_for_caller", "the installer refused this app")
        } catch (e: Exception) {
            Log.w(TAG, "the dataset installer did not answer $method: ${e.javaClass.simpleName}")
            refusal("no_answer", "$method: ${e.javaClass.simpleName}")
        } finally {
            client.close()
        }
    }

    private fun refusal(code: String, reason: String) = DataAnswer(JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive(code), "reason" to JsonPrimitive(reason))))

    override fun status() = call(OcdDataProtocol.STATUS)

    override fun stage(request: StageRequest): DataAnswer {
        val opened = mutableListOf<ParcelFileDescriptor>()
        return try {
            val extras = Bundle().apply {
                putString(OcdDataProtocol.PIN, request.pin)
                putString(OcdDataProtocol.FILES, JsonArray(request.names.map(::JsonPrimitive)).toString())
                request.firstUse?.let { putString(OcdDataProtocol.FIRST_USE, it) }
                for (name in request.names) {
                    val fd = ParcelFileDescriptor.open(File(request.paths.getValue(name)), ParcelFileDescriptor.MODE_READ_ONLY)
                    opened += fd
                    putParcelable(OcdDataProtocol.FD_PREFIX + name, fd)
                }
            }
            call(OcdDataProtocol.STAGE, extras = extras)
        } catch (e: java.io.IOException) {
            refusal("bad_request", "a dataset file could not be opened: ${e.javaClass.simpleName}")
        } finally {
            // CarrierSettings copies each file before it answers (spec 6.3); the app's copies of the descriptors go now.
            opened.forEach { runCatching { it.close() } }
        }
    }

    override fun installStatus(id: String) = call(OcdDataProtocol.INSTALL_STATUS, id)
    override fun activate(id: String) = call(OcdDataProtocol.ACTIVATE, id)
    override fun cancel(id: String) = call(OcdDataProtocol.CANCEL, id)

    override fun explain(subId: Int, dataset: String?) = call(
        OcdDataProtocol.EXPLAIN,
        extras = Bundle().apply {
            putInt(OcdDataProtocol.SUB_ID, subId)
            dataset?.let { putString(OcdDataProtocol.DATASET, it) }
        },
    )

    override fun putRecord(record: String) = call(OcdDataProtocol.PUT_RECORD, extras = Bundle().apply { putString(OcdDataProtocol.RECORD, record) })
    override fun removeRecord(key: String) = call(OcdDataProtocol.REMOVE_RECORD, extras = Bundle().apply { putString(OcdDataProtocol.RECORD_KEY, key) })
    override fun listRecords(subId: Int?) = call(OcdDataProtocol.LIST_RECORDS, extras = Bundle().apply { subId?.let { putInt(OcdDataProtocol.SUB_ID, it) } })
    override fun applyApnRecords() = call(OcdDataProtocol.APPLY_APN_RECORDS)

    override fun stop(reason: String) = call(OcdDataProtocol.STOP, extras = Bundle().apply { putString(OcdDataProtocol.REASON, reason.take(240)) })
    override fun resume() = call(OcdDataProtocol.RESUME)
    override fun check() = call(OcdDataProtocol.CHECK)

    override fun setTester(on: Boolean, reason: String) =
        call(OcdDataProtocol.SET_TESTER, if (on) "on" else "off", Bundle().apply { putString(OcdDataProtocol.REASON, reason.take(240)) })

    override fun voiceLossHold(reason: String): DataAnswer {
        val a = call(OcdDataProtocol.VOICE_LOSS_HOLD, extras = Bundle().apply { putString(OcdDataProtocol.REASON, reason.take(240)) })
        if (a.error != OcdDataProtocol.UNKNOWN_METHOD) return a
        // A round 30 ROM (protocol 2) has no hold. Its own way to serve the build's dataset, both parts, is its round 30 ROM
        // switch, which this app no longer shows anywhere; asked for here only so that calls stay up until that ROM is replaced.
        val legacy = call(ROUND30_ROM_HOLD, "on", Bundle().apply { putString("by", "voice_loss"); putString(OcdDataProtocol.REASON, reason.take(240)) })
        return if (legacy.ok) DataAnswer(JsonObject(legacy.o + mapOf("voice_loss" to JsonPrimitive("hold"), "round30_rom" to JsonPrimitive(true)))) else legacy
    }

    override fun voiceLossRelease(outcome: String, reason: String) = call(
        OcdDataProtocol.VOICE_LOSS_RELEASE,
        extras = Bundle().apply {
            putString(OcdDataProtocol.OUTCOME, outcome)
            putString(OcdDataProtocol.REASON, reason.take(240))
        },
    )

    override fun emergencyDefaultSet(subId: Int) = call(OcdDataProtocol.EMERGENCY_DEFAULT_SET, extras = Bundle().apply { putInt(OcdDataProtocol.SUB_ID, subId) })
    override fun emergencyDefaultRestore(keep: Boolean) = call(OcdDataProtocol.EMERGENCY_DEFAULT_RESTORE, extras = Bundle().apply { putBoolean(OcdDataProtocol.KEEP, keep) })

    override fun apnCandidateSet(subId: Int, candidate: String) = call(
        OcdDataProtocol.APN_CANDIDATE_SET,
        extras = Bundle().apply {
            putInt(OcdDataProtocol.SUB_ID, subId)
            putString(OcdDataProtocol.CANDIDATE, candidate)
        },
    )

    override fun apnCandidateRestore() = call(OcdDataProtocol.APN_CANDIDATE_RESTORE)

    companion object {
        const val TAG = "OcdCheckDataset"

        /** The round 30 ROM's (protocol 2) only way to serve the build's dataset; used by [voiceLossHold] on that old ROM only. */
        private const val ROUND30_ROM_HOLD = "set_safe_mode" // round 30 ROM only: removed in round 31
    }
}

/**
 * Rounds 30 and 31 in the tester builds: the whole-dataset pull at the main pin, the hand-off to
 * CarrierSettings (stage, then activate in the next idle moment), the tests of every changed value
 * together with splitting and candidates, X5's tests of held offs, measured records, the voice-loss
 * trigger with round 31's hold and split, the tester declaration (SM7), and the stop events with
 * `stop` and `resume`. Never throws but for a cancelled worker; each step returns a line for the log.
 */
object OwnerDatasetStep {
    private val json = Json { ignoreUnknownKeys = true }

    /** New trials start only within this of a run's start: a worker has about 10 minutes, and a trial takes up to about 4. */
    const val TRIALS_START_WITHIN_MS = 5 * 60_000L

    fun client(context: Context): DatasetClient = ProviderDataset(context)

    fun fetchedBy(): String = "${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}"

    private fun root(context: Context) = File(context.filesDir, "dataset")

    fun dir(context: Context, pulled: PulledDataset?): File? = pulled?.let { File(root(context), it.folder) }?.takeIf { File(it, DatasetFiles.PIN).isFile }

    /** The installer's status, stored for the screen. */
    fun status(context: Context, store: StateStore): DatasetStatus {
        val status = DatasetStatus.parse(client(context).status(), System.currentTimeMillis())
        // Written only when it changed (the screen asks every 15 s; the time of the read alone is no change).
        if (store.read().datasetStatus?.copy(readAtMs = 0) != status.copy(readAtMs = 0)) store.update { it.copy(datasetStatus = status) }
        return status
    }

    /**
     * The dataset of the main pin's commit (F1), when it is not here yet or CarrierSettings asked
     * for a pull again. [pin]: the main pin [OwnerData] took (validate passed). Returns the log line;
     * throws [io.github.open_carrier_data.check.owner.core.DataException] or IOException, and the last good set stays whole.
     */
    fun pullDataset(context: Context, store: StateStore, fetch: Fetch, pin: DataPin, force: Boolean): String {
        val state = store.read()
        val have = state.pulled?.takeIf { it.commit == pin.commit && dir(context, it) != null }
        if (have != null && !force) return "dataset up to date: ${have.label}"
        val now = System.currentTimeMillis()
        val target = File(root(context), pin.commit)
        val partial = File(root(context), "partial").apply { deleteRecursively(); mkdirs() }
        val result = try {
            DatasetCheck.pull(fetch, pin, partial, dir(context, state.pulled), fetchedBy(), now)
        } catch (e: Exception) {
            partial.deleteRecursively()
            throw e
        }
        return when (result) {
            is DatasetPull.NotPublished -> {
                partial.deleteRecursively()
                result.reason
            }
            is DatasetPull.Pulled -> {
                target.deleteRecursively()
                if (!partial.renameTo(target)) throw java.io.IOException("could not move the dataset into place")
                val pulled = result.dataset.copy(folder = target.name)
                val line = "dataset pulled: ${pulled.label}, ${pulled.names.size} files, ${pulled.bytes} bytes " +
                    "(${result.downloaded} downloaded, ${result.reused} unchanged), git blobs equal to the commit's tree, data digest ${pulled.dataDigest.take(19)} in every file, dataset digest ${pulled.datasetDigest.take(19)}"
                store.update { it.copy(pulled = pulled).logged(now, "dataset", line) }
                // Keep the newest set only, and the one installed, which a stage may reuse.
                root(context).listFiles()?.filter { it.name != target.name }?.forEach { it.deleteRecursively() }
                runCatching { Log.i(ProviderDataset.TAG, line) }
                line
            }
        }
    }

    /**
     * One pass (rounds 30 and 31). First the readiness after a boot, a SIM change or a restart ([OwnerReady]: the SIM
     * loaded, CarrierSettings's config served, IMS settled; a bounded wait on those events). Then the tester declaration
     * (round 31, SM7), voice loss (always on, also roaming), then what a stop event, the voice-loss hold and roaming
     * allow: the hold's split, or stage a newer dataset, activate it, and test. [testNow]: the owner's "Test now" (or its
     * adb trigger), which does all of it in this moment (2026-10-25, "what can be done now should be done now"): no
     * screen-off, charging or idle wait, only the refusals that name a harm (a call, emergency callback or SMS mode,
     * 10 minutes after an emergency call, roaming) and a halt. A background pass waits for the idle moment, as it must
     * not disturb the person using the phone.
     */
    suspend fun run(context: Context, store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>, testNow: Boolean = false): String {
        val client = client(context)
        var status = status(context, store)
        if (!status.available) return "no dataset installer (${status.why})"
        val notes = mutableListOf<String>()
        val (ready, readySims) = if (sims.isEmpty() && !testNow) Pair(null, sims) else OwnerReady.await(context, phone, store)
        if (ready != null && !ready.ok) return "not ready after ${ready.waitedMs / 1000} s: ${ready.line}; nothing installed or tested now"
        if (ready != null && ready.waitedMs >= 1_000) notes += "ready after ${ready.waitedMs / 1000} s"
        val present = readySims.ifEmpty { sims }
        val deadline = System.currentTimeMillis() + TRIALS_START_WITHIN_MS
        clearLeftoverTrial(context, store, phone, present)?.let { notes += it }
        // Round 32: a temporary APN candidate set a crash left in place goes back at once.
        OwnerApnCandidates.clearLeftover(context, store, client, status, present)?.let { notes += it; status = status(context, store) }
        declareTester(context, store, client, status)?.let { notes += it; status = status(context, store) }
        // A second loss under the same values (round 31): CarrierSettings serves the build's dataset and refuses changes until Resume.
        voiceLossStop(context, store, status)?.let { return (notes + it).joinToString("; ") }
        // The voice-loss trigger, always on (but not while a stop event halts tests and changes).
        if (store.read().stop == null) for (sim in present) voiceLoss(context, store, phone, client, status, sim)?.let { return (notes + it).joinToString("; ") }
        val state = store.read()
        val halt = state.stop ?: status.changesHalted.reason?.takeIf { status.changesHalted.on } ?: "changes halted".takeIf { status.changesHalted.on }
        if (halt != null) return (notes + "changes halted until you tap Resume: $halt").joinToString("; ")
        if (status.pullAgain) OwnerScheduler.pull(context, force = true)
        // While the voice-loss hold serves the build's dataset, nothing is activated: the idle moment at home is the split's.
        if (!status.voiceLoss.hold) stage(context, store, client, status, testNow)?.let { notes += it }
        if (testNow) {
            ownerBlock(store, phone, present)?.let { return (notes + "not now: $it").joinToString("; ") }
        } else {
            idleBlock(context, store, phone, present)?.let { return (notes + "waits for the idle moment: ${it.plain}").joinToString("; ") }
        }
        if (status.voiceLoss.hold) {
            notes += holdSplit(context, store, phone, client, status, present, testNow, deadline)
            publish(context, store)
            return notes.joinToString("; ")
        }
        activate(context, store, phone, client, present)?.let { notes += it }
        notes += campaigns(context, store, phone, client, present, testNow, deadline)
        publish(context, store)
        return notes.joinToString("; ").ifEmpty { "nothing to do" }
    }

    /**
     * Round 31 (R31.5): tells CarrierSettings whether dialled tests run on this phone: the owner build, or a ROM build
     * whose person turned them on, on an IMS stack the tests are calibrated on (SM8). Without a tester CarrierSettings
     * applies SM7. Only when the declaration differs; a protocol 2 ROM has none.
     */
    private fun declareTester(context: Context, store: StateStore, client: DatasetClient, status: DatasetStatus): String? {
        if (!status.round31) return null
        val calibrated = StackCalibration.counted(status.build.imsStackId)
        val want = OwnerTester.policy(store.read()).activeTests && calibrated
        // CarrierSettings already counts this phone as it should; and the same declaration goes at most once a day.
        if (status.tester.active == want) return null
        val last = store.read().testerDeclared
        if (last?.on == want && System.currentTimeMillis() - last.atMs in 0 until TimeUnit.DAYS.toMillis(1)) return null
        val reason = when {
            want -> if (OwnerTester.build == io.github.open_carrier_data.check.owner.core.TesterBuild.OWNER) "the owner build tests on the owner's phone" else "the person turned the test calls on"
            !calibrated -> "the tests are not calibrated on the IMS stack ${status.build.imsStackId.ifEmpty { "this phone runs" }}"
            else -> "the person has not turned the test calls on"
        }
        val a = client.setTester(want, reason)
        val line = "tester declared ${if (want) "on" else "off"} ($reason): ${if (a.ok) "SM7 ${if (a.bool("sm7") == true) "applies" else "does not apply"}" else a.line}"
        store.update { it.copy(testerDeclared = io.github.open_carrier_data.check.owner.core.TesterDeclaration(want, System.currentTimeMillis())).logged(System.currentTimeMillis(), "tester", line) }
        return line
    }

    /** Round 31 (R31.8): a voice-loss stop in CarrierSettings becomes the app's stop event, so the screen offers Resume. */
    private fun voiceLossStop(context: Context, store: StateStore, status: DatasetStatus): String? {
        if (!status.voiceLoss.stop) return null
        if (store.read().stop == null) {
            val reason = "calls stopped again under the same values: the ROM's own data stays until you tap Resume or a newer ROM build"
            store.update { it.copy(stop = "voice_loss_stop").logged(System.currentTimeMillis(), "stop", "${status.voiceLoss.stopReason ?: "voice-loss stop"}; $reason") }
            OwnerNotifications.notice(context, Notice("Calls stopped again", "Calls stopped working again with the same new values, so the ROM's own data stays. Tap Resume when you want Open Carrier Data to try again, or wait for a newer ROM build.", failed = true))
        }
        return "voice-loss stop: the build's dataset serves until Resume or a newer build"
    }

    /** What stops "Test now" before it installs: the refusals of every SIM (a call, an emergency state, roaming); null when none holds. */
    private suspend fun ownerBlock(store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>): String? {
        for (sim in sims) {
            val verdict = runCatching { OwnerSession(phone, store, sim).safety() }.getOrNull() ?: return "the refusals could not be read on slot ${sim.slot + 1}"
            verdict.refusal?.let { return "slot ${sim.slot + 1}: ${it.plain}" }
        }
        return null
    }

    /** The idle moment (testsDecide's conditions); null when it holds. */
    suspend fun idleBlock(context: Context, store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>): IdleBlock? = IdleMoment.blocker(idleInputs(context, store, phone, sims))

    /** What the idle moment reads, in one pass. The quiet after a call counts real calls, not the app's own mailbox calls. */
    suspend fun idleInputs(context: Context, store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>): IdleInputs {
        val look = QuietReader(context).read(phone, sampleMs = 0)
        val sim = sims.firstOrNull()
        val verdict = sim?.let { runCatching { OwnerSession(phone, store, it).safety() }.getOrNull() }
        val state = store.read()
        val audio = context.getSystemService(AudioManager::class.java)
        val now = System.currentTimeMillis()
        val serverUp = sim?.let { runCatching { OwnerSession(phone, store, it).serverUpMs() }.getOrNull() }
        val calls = runCatching { phone.callsSeen() }.getOrNull()
        return IdleInputs(
            interactive = look.interactive,
            screenOffForMs = look.screenOffForMs,
            inCall = look.inCall,
            lastCallSeenAtMs = listOfNotNull(state.lastCallSeenAtMs, RestartWindow.lastCallAtMs(calls, own = false)).maxOrNull(),
            mediaActive = look.audioIdle?.not(),
            recording = runCatching { audio?.activeRecordingConfigurations?.isNotEmpty() }.getOrNull(),
            ecbm = verdict?.emergency?.ecbm,
            emergencySmsMode = verdict?.emergency?.emergencySmsMode ?: verdict?.emergency?.let { false },
            emergencySeenAtMs = state.emergencySeen?.firstSeenAtMs,
            upMs = minOf(SystemClock.elapsedRealtime(), serverUp ?: Long.MAX_VALUE),
            batteryPercent = look.batteryPercent,
            charging = look.plugged,
            roaming = look.roaming,
            nowMs = now,
            callsSeen = calls,
        )
    }

    /**
     * Stage the pulled dataset when it is newer than the installed one and not staged yet; poll until verified or refused.
     * An install of the same dataset that ended without serving goes again once for each new CarrierSettings and on every
     * "Test now" ([DatasetRetry]).
     */
    private suspend fun stage(context: Context, store: StateStore, client: DatasetClient, status: DatasetStatus, testNow: Boolean): String? {
        val state = store.read()
        val pulled = state.pulled ?: return null
        val dir = dir(context, pulled) ?: return null
        if (status.installed?.datasetDigest == pulled.datasetDigest) return null
        // Round 31 (R31.8): a dataset a second voice loss blocked is never handed over again; a newer one is.
        if (pulled.datasetDigest in status.voiceLoss.blocked) return "main ${pulled.commit.take(7)} is blocked after a second voice loss; a newer dataset goes in"
        val last = state.install
        var again: String? = null
        if (last != null && last.datasetDigest == pulled.datasetDigest && last.state != InstallState.CANCELLED && last.state != InstallState.SUPERSEDED) {
            // Staged already: verified waits for its moment; an ended install goes again only for a new CarrierSettings or "Test now",
            // or when CarrierSettings no longer has it installed (round 31: the app was disabled and enabled again, R31.4).
            if (last.state == InstallState.VERIFIED) return null
            if (!last.final) return poll(store, client, last.installId)
            again = DatasetRetry.again(last, status, testNow) ?: DatasetRetry.dropped(last, status) ?: return null
        }
        val request = StageRequest(File(dir, DatasetFiles.PIN).readText(), pulled.names, pulled.names.associateWith { File(dir, it).path }, firstUse(state, status))
        val a = client.stage(request)
        val now = System.currentTimeMillis()
        if (!a.ok) {
            store.update { it.copy(datasetLine = "Not handed over: ${a.line}").logged(now, "dataset", "stage of ${pulled.label} ${a.line}") }
            if (a.error in setOf("changes_halted", "busy") || a.error in InstallRefusal.VOICE_LOSS) return "stage waits: ${a.error}"
            return "stage refused: ${a.error}"
        }
        val id = a.str(OcdDataProtocol.INSTALL_ID) ?: return "stage gave no install ID"
        val first = InstallView(id, a.str(OcdDataProtocol.STATE) ?: InstallState.VERIFYING, datasetDigest = pulled.datasetDigest, commit = pulled.commit, carrierSettings = DatasetRetry.key(status))
        store.update { it.copy(install = first).logged(now, "dataset", "staged ${pulled.label} as $id: ${first.state}" + (again?.let { " (handed over again: $it)" } ?: "")) }
        if (first.state == InstallState.ALREADY_INSTALLED) return "already installed"
        return poll(store, client, id)
    }

    /**
     * Round 31 (R31.6): the owner's first-use record of the emergency data test, re-sent with every hand-off, for this
     * phone's device and IMS stack. It cannot narrow a gate the build does not declare. Null when none is recorded.
     */
    fun firstUse(state: io.github.open_carrier_data.check.owner.core.OwnerState, status: DatasetStatus): String? {
        val f = state.emergencyFirstUse ?: return null
        if (!status.build.complete) return null
        val record = JsonObject(
            linkedMapOf(
                "schema" to JsonPrimitive("ocd-emergency-first-use/1"),
                "device" to JsonObject(linkedMapOf("codename" to JsonPrimitive(status.build.device), "model" to JsonPrimitive(status.build.model))),
                "ims_stack" to JsonObject(linkedMapOf("id" to JsonPrimitive(status.build.imsStackId), "fork" to (status.build.imsStackFork?.let(::JsonPrimitive) ?: JsonNull))),
                "result" to JsonPrimitive(f.result),
                "date" to JsonPrimitive(MeasuredRecord.day(f.atMs)),
            ),
        )
        return MeasuredRecord.text(record)
    }

    /** install_status every 2 s for up to 3 minutes until verified or final. */
    private suspend fun poll(store: StateStore, client: DatasetClient, id: String, until: (InstallView) -> Boolean = { it.final || it.state == InstallState.VERIFIED }, maxMs: Long = 180_000): String {
        var waited = 0L
        var view: InstallView
        while (true) {
            view = InstallView.parse(id, client.installStatus(id))
            if (until(view) || waited >= maxMs) break
            kotlinx.coroutines.delay(2_000)
            waited += 2_000
        }
        val before = store.read().install?.takeIf { it.installId == id }
        store.update { s -> s.copy(install = view.copy(datasetDigest = view.datasetDigest.ifEmpty { before?.datasetDigest.orEmpty() }, commit = view.commit.ifEmpty { before?.commit.orEmpty() }, carrierSettings = before?.carrierSettings.orEmpty())) }
        val line = "install $id: ${view.state}" + (view.error?.let { " ($it${view.reason?.let { r -> ": $r" } ?: ""})" } ?: "") +
            (if (view.failedChecks.isNotEmpty()) "; failed checks: ${view.failedChecks.joinToString()}" else "")
        store.update { it.logged(System.currentTimeMillis(), "dataset", line).copy(datasetLine = line) }
        // An APN shrink, a SIM that would lose rows or coupling: a source review (spec 12.3).
        if (view.state == InstallState.REFUSED && view.error in setOf("apn_shrink", "sim_loses_internet", "sim_loses_ims", "apn_coupling_lost")) {
            val review = SourceReview.install(view.error!!, view.reason ?: view.failedChecks.joinToString(), view.commit.ifEmpty { store.read().pulled?.commit.orEmpty() }, System.currentTimeMillis())
            store.update { s -> if (s.reviewsSent.contains(review.key) || s.pendingReviews.any { it.key == review.key }) s else s.copy(pendingReviews = (s.pendingReviews + review).takeLast(SourceReview.MAX)) }
        }
        return line
    }

    /** Activate a verified install in the idle moment, poll until installed, and open a campaign for each SIM it changed. */
    private suspend fun activate(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, sims: List<OwnerSim>): String? {
        val install = store.read().install?.takeIf { it.state == InstallState.VERIFIED } ?: return null
        // The baseline of every trial of this install: IMS and data as they are before it.
        val baselines = sims.associate { sim -> sim.subId to runCatching { TrialRunner.observe(phone, store, sim) }.getOrNull() }
        val a = client.activate(install.installId)
        val now = System.currentTimeMillis()
        if (a.error in setOf("not_verified", "not_found")) {
            store.update { it.copy(install = null).logged(now, "dataset", "install ${install.installId} is no longer staged (${a.error}); the next pass stages again") }
            return "staged install gone: ${a.error}"
        }
        if (!a.ok) {
            val why = if (a.error == "refused_now") InstallRefusal.waiting(a.str(OcdDataProtocol.WAITING_FOR)) else a.line
            store.update { it.logged(now, "dataset", "activate ${install.installId} waits: $why").copy(datasetLine = "Waiting to install: $why") }
            return "activate waits: $why"
        }
        for (sim in sims) store.update { it.copy(caps = TestCaps.add(it.caps, sim.key, CapEvent.REREGISTRATION, now)) }
        val line = poll(store, client, install.installId, until = { it.final }, maxMs = 300_000)
        val view = store.read().install ?: return line
        if (view.state != InstallState.INSTALLED) return line
        val status = status(context, store)
        val datasetA = DatasetContext.load(context, store.read().pulled)?.datasetA.orEmpty()
        val opened = mutableListOf<String>()
        for (sim in sims) {
            val ch = install.changes.firstOrNull { it.subId == sim.subId } ?: view.changes.firstOrNull { it.subId == sim.subId } ?: continue
            if (!ch.touchesSim && ch.x5Held.isEmpty()) continue
            // Round 31: the keys the ROM's device override sets are the device's; they are never tested or recorded.
            val device = DeviceKeys.of(ExplainView.parse(sim.subId, client.explain(sim.subId)), status)
            val changes = ch.keys.filterNot { DeviceKeys.owned(it.source) }.map { Changed(it.key, it.old, it.new, it.source) }
            val c = CampaignPlanner.start(UUID.randomUUID().toString().take(8), sim, view.commit, view.datasetDigest, changes, datasetA, now, device = device).copy(baseline = baselines[sim.subId])
            store.update { s -> s.copy(campaigns = s.campaigns.filterNot { it.subId == sim.subId } + c) }
            opened += "slot ${sim.slot + 1}: ${changes.size} changed value(s)${if (ch.profileBefore != ch.profileAfter) ", profile ${ch.profileBefore} -> ${ch.profileAfter}" else ""}${if (ch.x5Held.isNotEmpty()) ", ${ch.x5Held.size} off(s) held until tested" else ""}"
            if (ch.couplingAfter == "mismatch" && ch.couplingBefore != "mismatch") {
                val review = SourceReview.coupling(ch.profileAfter.orEmpty(), view.commit, now)
                store.update { s -> if (s.reviewsSent.contains(review.key)) s else s.copy(pendingReviews = (s.pendingReviews + review).takeLast(SourceReview.MAX)) }
            }
        }
        OwnerScheduler.voiceCheck(context)
        val text = "installed main ${view.commit.take(12)} (serving ${status.serving})" + if (opened.isEmpty()) "; no SIM in the phone changed" else "; tests: ${opened.joinToString()}"
        store.update { it.logged(System.currentTimeMillis(), "dataset", text).copy(datasetLine = "Installed main ${view.commit.take(7)}") }
        return text
    }

    /** The open campaigns and the ones due (a new SIM, build or stack, 14 days), run within the caps while the idle moment holds. */
    private suspend fun campaigns(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, sims: List<OwnerSim>, testNow: Boolean, deadline: Long): List<String> {
        val notes = mutableListOf<String>()
        val status = store.read().datasetStatus ?: return notes
        val ctx = DatasetContext.load(context, store.read().pulled)
        val basis = OwnerBasis.current(context, store.read()).let { "${it.rom}|${status.build.imsStackId}|${status.build.imsStackVersion ?: ""}" }
        for (sim in sims) {
            val now = System.currentTimeMillis()
            var c = store.read().campaigns.firstOrNull { it.subId == sim.subId && it.mccmnc == sim.identity.mccmnc && !it.done }
            if (c == null) {
                val why = if (testNow) "your request" else TestsDue.due(sim.key, basis, store.read().campaignEnds, now) ?: continue
                c = CampaignPlanner.start(UUID.randomUUID().toString().take(8), sim, status.installed?.commit.orEmpty(), status.installed?.datasetDigest.orEmpty(), emptyList(), ctx?.datasetA.orEmpty(), now, why)
                store.update { s -> s.copy(campaigns = s.campaigns.filterNot { it.subId == sim.subId } + c) }
            }
            notes += TrialRunner(context, store, phone, client, sim, ctx, status, if (testNow) TrialRunner.OWNER_INTERRUPTS else TrialRunner.INTERRUPTS, deadline).run(c, basis)
            // Round 32 (D8 rule 4, rounds 33 and 36): an MMSC probe failure measured on the installed data opens the MMS candidates in this idle moment.
            mmsFailure(store, sim, now)?.let { notes += OwnerApnCandidates.afterFailure(context, store, phone, client, status, sim, it) }
        }
        return notes
    }

    /** The SIM's last MMSC probe, when it failed within its 14 days (the free probe; no MMS is ever sent). */
    private fun mmsFailure(store: StateStore, sim: OwnerSim, nowMs: Long): io.github.open_carrier_data.check.owner.core.LeadFailure? {
        val last = store.read().mms.lastOrNull { it.sim.sameCard(sim.key) } ?: return null
        val failed = last.result == "no_answer" || last.result == "refused_no_mms_network"
        return io.github.open_carrier_data.check.owner.core.LeadFailure.MMSC_PROBE.takeIf { failed && nowMs - last.atMs in 0 until MmsProbe.EVERY_MS }
    }

    /**
     * Puts the records of a settlement into CarrierSettings, which serves them on this phone in their
     * exact cell, and (owner build only, SM11) queues them for main with the owner's token. Never a
     * record for a key the ROM's device override sets ([device], round 31), and none on an IMS stack
     * the tests are not calibrated on (SM8).
     */
    fun putRecord(store: StateStore, client: DatasetClient, record: JsonObject, candidates: List<JsonElement>, type: String?, datasetA: Set<String>, device: Set<String> = emptySet()): String {
        val now = System.currentTimeMillis()
        val state = store.read()
        val problem = StackCalibration.notCounted(state.datasetStatus?.build?.imsStackId)
            ?: RecordCheck.problem(record, candidates, type, datasetA, now, device)
        problem?.let { why ->
            store.update { it.logged(now, "record", "not written (${MeasuredRecord.targetKey(record) ?: "record"}): $why") }
            return "record not written: $why"
        }
        // A ROM build's record stays on this phone (SM11); it names no owner as its tester.
        val local = if (OwnerTester.policy(state).sendsWithToken) record else JsonObject(record + ("tester" to JsonPrimitive("local")))
        val text = MeasuredRecord.text(local)
        val a = client.putRecord(text)
        val line = if (a.ok) {
            // Only the owner's token writes measured records to main (SM11); a ROM build's records stay on this phone.
            if (OwnerTester.policy(state).sendsWithToken) {
                store.update { s -> s.copy(pendingRecords = (s.pendingRecords.filterNot { MeasuredRecord.recordKey(json.parseToJsonElement(it) as JsonObject) == MeasuredRecord.recordKey(record) } + text).takeLast(100)) }
            }
            "record ${MeasuredRecord.recordKey(record)} ${MeasuredRecord.result(record)} for ${MeasuredRecord.targetKey(record) ?: "emergency data"}: ${a.str("effect") ?: "stored"}"
        } else {
            "record ${MeasuredRecord.recordKey(record)} refused by CarrierSettings: ${a.line}"
        }
        store.update { it.logged(now, "record", line) }
        return line
    }

    /** Sends pending records and source reviews with the owner's token, as its own work with a network; never in a ROM build (SM11). */
    fun publish(context: Context, store: StateStore) {
        val s = store.read()
        if (!OwnerTester.policy(s).sendsWithToken) return
        if (s.pendingRecords.isNotEmpty() || s.pendingReviews.isNotEmpty()) OwnerScheduler.autoReport(context)
    }

    /** A round 30 trial left by a crash: clear the slot's overrides, prove it, forget it. A trial still there after a reboot is a stop event. */
    suspend fun clearLeftoverTrial(context: Context, store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>): String? {
        val t = store.read().trial30 ?: return null
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val dump = phone.carrierConfigText()?.takeIf { "Phone Id = " in it }?.let(CcDump::parse)
        val present = dump?.overrides(t.slot)?.values?.flatMap { it.keys }?.toSet().orEmpty()
        if (t.sinceMs < bootAt && present.any { it in t.keys }) {
            store.update { it.copy(stop = "a round 30 trial override was still present after a reboot").logged(System.currentTimeMillis(), "stop", "a trial override was still present after a reboot: ${t.keys.take(5)}") }
            runCatching { client(context).stop("a trial override was still present after a reboot") }
        }
        val cleared = phone.ccClear(t.slot, t.subId)
        val after = phone.carrierConfigText()?.takeIf { "Phone Id = " in it }?.let(CcDump::parse)
        val left = after?.overrides(t.slot)?.values?.flatMap { it.keys }.orEmpty().filter { it in t.keys }
        if (!cleared.ok || after == null || left.isNotEmpty()) {
            store.update { it.copy(stop = "a round 30 trial override could not be removed").logged(System.currentTimeMillis(), "stop", "a trial override could not be removed on slot ${t.slot + 1}: ${cleared.reason ?: left.take(5)}") }
            runCatching { client(context).stop("a trial override could not be removed") }
            return "STOP: a leftover trial could not be removed"
        }
        store.update { it.copy(trial30 = null).logged(System.currentTimeMillis(), "trial", "a leftover trial on slot ${t.slot + 1} was removed, proven in the carrier config") }
        return "leftover trial removed"
    }

    /**
     * The voice-loss trigger (testsDecide (b), round 31's hold): voice lost on two reads 30 s apart while an installed
     * dataset serves. The next candidates of the voice-class changes at once (with the active tests on, on a calibrated
     * stack), then the build's bundle as the final candidate. When only that restores voice, CarrierSettings holds the
     * build's dataset (`voice_loss_hold`) until the split at the next idle moment at home; a second loss under the same
     * values is CarrierSettings's stop. When nothing restores voice, the newest dataset stays (the cause is the network)
     * and it is a stop event. Works while roaming; never during a call or an emergency state.
     */
    private suspend fun voiceLoss(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, status: DatasetStatus, sim: OwnerSim): String? {
        val session = OwnerSession(phone, store, sim)
        val first = runCatching { session.observe() }.getOrNull() ?: return null
        fun inService(o: io.github.open_carrier_data.check.owner.core.Observation) = o.ps?.state?.let { it == "home" || it == "roaming" }
        if (!VoiceLoss.applies(first.voiceLost(), status, inService(first))) return null
        phone.sleep(30_000)
        val again = runCatching { session.observe() }.getOrNull() ?: return null
        if (!VoiceLoss.applies(again.voiceLost(), status, inService(again))) return null
        val safety = runCatching { session.safety() }.getOrNull()
        if (safety?.refusal != null && safety.refusal !in setOf(Refusal.ROAMING, Refusal.ROAMING_UNREADABLE)) return "voice lost on slot ${sim.slot + 1}; no change now (${safety.refusal.plain})"
        val now = System.currentTimeMillis()
        store.update { it.logged(now, "voice_loss", "slot ${sim.slot + 1}: voice lost (no IMS voice, no circuit-switched service) while main ${status.installed?.commit?.take(7)} serves") }
        val ctx = DatasetContext.load(context, store.read().pulled)
        val served = ExplainView.parse(sim.subId, client.explain(sim.subId)) ?: return "voice lost; explain not readable"
        val device = DeviceKeys.of(served, status)
        val changes = store.read().campaigns.lastOrNull { it.subId == sim.subId }?.changes.orEmpty()
        val runner = TrialRunner(context, store, phone, client, sim, ctx, status, emptySet(), Long.MAX_VALUE)
        val budget = TestCaps.trialsLeft(store.read().caps, sim.key, now)
        var tried = 0
        // Candidates change carrier settings: only with the active tests on (SM9) and on a calibrated stack (SM8).
        val candidates = OwnerTester.policy(store.read()).activeTests && StackCalibration.counted(status.build.imsStackId)
        if (candidates) for (ch in VoiceLoss.suspects(changes, device)) {
            for (cand in ctx?.candidates(ch, served).orEmpty()) {
                if (tried >= budget - 1) break
                tried++
                if (runner.voiceBackWith(mapOf(ch.key to cand), "voice loss: ${ch.key} = ${cand.plain}")) {
                    val record = runner.winnerRecord(ch, cand, TrialVerdict(TrialOutcome.PASS, reason = "IMS voice registration came back", level = "no_regression"), served, tried)
                    val line = record?.let { putRecord(store, client, it.first, it.second, ctx?.type(ch.key), ctx?.datasetA.orEmpty(), device) } ?: "no record"
                    runner.endChain()
                    OwnerNotifications.notice(context, Notice("Calls are back", "Open Carrier Data changed ${ch.key} after calls stopped working; $line", failed = true))
                    return "voice loss on slot ${sim.slot + 1}: ${ch.key} = ${cand.plain} restored voice; $line"
                }
            }
        }
        // The final candidate: the build's dataset as an override; the hold only if that restores voice.
        val build = ExplainView.parse(sim.subId, client.explain(sim.subId, "build"))
        val overrides = build?.let { VoiceLoss.buildOverrides(served.keys, it.keys, ctx?.datasetA.orEmpty(), device) }.orEmpty()
        val restored = overrides.isNotEmpty() && runner.voiceBackWith(overrides, "voice loss: the build's dataset as the final candidate")
        if (!restored) {
            runner.endChain()
            val reason = "voice lost on slot ${sim.slot + 1} and no candidate, not even the build's dataset, restored it; the newest dataset stays (the cause is the network)"
            client.stop(reason)
            store.update { it.copy(stop = "voice_loss_not_restored").logged(System.currentTimeMillis(), "stop", reason) }
            OwnerNotifications.notice(context, Notice("Tests paused: no calls", "$reason. Tap Resume when calls work again.", failed = true))
            return "STOP: $reason"
        }
        // Only the build's dataset restored voice: CarrierSettings holds it (both parts) before the overrides go, so voice stays.
        val a = client.voiceLossHold("the build's dataset restored voice on slot ${sim.slot + 1}; no candidate did")
        // CarrierSettings installs the build's dataset itself; the overrides go only once it serves (at most 2 minutes).
        if (a.ok) waitServing(client, maxMs = 120_000)
        runner.endChain()
        val held = a.str("voice_loss")
        val line = when {
            a.ok && held == "stop" -> {
                store.update { it.copy(stop = "voice_loss_stop").logged(System.currentTimeMillis(), "stop", "slot ${sim.slot + 1}: voice lost again under the same values; CarrierSettings keeps the build's dataset until Resume or a newer build") }
                OwnerNotifications.notice(context, Notice("Calls stopped again", "Calls stopped working again with the same new values, so the ROM's own data stays. Tap Resume when you want Open Carrier Data to try again, or wait for a newer ROM build.", failed = true))
                "voice loss on slot ${sim.slot + 1}: the second loss under the same values; the build's dataset stays (stop event)"
            }
            a.ok -> {
                val legacy = a.bool("round30_rom") == true
                store.update { it.logged(System.currentTimeMillis(), "voice_loss", "the build's dataset restored voice; CarrierSettings holds it" + if (legacy) " (a round 30 ROM: until a newer ROM build)" else "; the split runs at the next idle moment at home") }
                OwnerNotifications.notice(context, Notice("Calls are back with the ROM's own data", "Calls stopped working with Open Carrier Data's newest data and came back with the ROM's own data, which now serves. When the phone is next idle at home, Open Carrier Data finds the values at fault and then serves the newest data with working values in their place.", failed = true))
                "voice loss on slot ${sim.slot + 1}: the build's dataset restored voice; held${a.str("values_digest")?.let { " (values ${it.take(19)})" } ?: ""}"
            }
            else -> {
                val reason = "voice came back only with the build's dataset on slot ${sim.slot + 1}, and CarrierSettings did not hold it (${a.line})"
                client.stop(reason)
                store.update { it.copy(stop = "voice_loss_hold_refused").logged(System.currentTimeMillis(), "stop", reason) }
                OwnerNotifications.notice(context, Notice("Tests paused: no calls", "$reason. Disable Open Carrier Data in Android's Settings to go back to the ROM's own data.", failed = true))
                "STOP: $reason"
            }
        }
        status(context, store)
        return line
    }

    /** Polls `status` until the build's dataset serves (the voice-loss hold or stop), or [maxMs] passes. */
    private suspend fun waitServing(client: DatasetClient, maxMs: Long) {
        var waited = 0L
        while (waited < maxMs) {
            val s = DatasetStatus.parse(client.status(), System.currentTimeMillis())
            if (!s.available || s.serving == "build") return
            kotlinx.coroutines.delay(2_000)
            waited += 2_000
        }
    }

    /**
     * Round 31's split (R31.8, safeMode (c)): while the build's dataset is held, at the idle moment at home, test the
     * newest dataset's differences from the build's as overrides over it, halving to the values at fault, and try their
     * candidates. Winners become records (stored while held, served after the release); then `voice_loss_release`
     * with `winners`, or `no_failing_value` when every newest value passed together. A failing value with no working
     * alternative keeps the build's dataset held and is a stop event. Only with the active tests on and on a calibrated
     * stack (SM8, SM9): otherwise the build's dataset stays held until a newer ROM build.
     */
    private suspend fun holdSplit(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, status: DatasetStatus, sims: List<OwnerSim>, testNow: Boolean, deadline: Long): List<String> {
        val notes = mutableListOf<String>()
        if (!status.round31) return listOf("the build's dataset is held by this round 30 ROM until a newer ROM build")
        val passive = StackCalibration.notCounted(status.build.imsStackId) ?: if (!OwnerTester.policy(store.read()).activeTests) "the test calls are off on this phone" else null
        if (passive != null) return listOf("the build's dataset stays held: no split ($passive)")
        val ctx = DatasetContext.load(context, store.read().pulled)
        val install = store.read().install?.takeIf { it.datasetDigest == status.installed?.datasetDigest }
        val basis = OwnerBasis.current(context, store.read()).let { "${it.rom}|${status.build.imsStackId}|${status.build.imsStackVersion ?: ""}" }
        val ends = mutableListOf<VoiceLoss.SplitEnd>()
        for (sim in sims) {
            var c = store.read().campaigns.firstOrNull { it.subId == sim.subId && it.held && it.datasetDigest == status.installed?.datasetDigest.orEmpty() }
            if (c == null) {
                val build = ExplainView.parse(sim.subId, client.explain(sim.subId, "build")) ?: ExplainView.parse(sim.subId, client.explain(sim.subId))
                val newest = install?.let { ExplainView.parse(sim.subId, client.explain(sim.subId, it.installId)) } ?: ExplainView.parse(sim.subId, client.explain(sim.subId, "installed"))
                val device = DeviceKeys.of(build, status) + DeviceKeys.of(newest, status)
                // The differences from both bundles; else the install's own changes, which CarrierSettings computed against what served before it.
                val diffs = when {
                    build != null && newest != null -> VoiceLoss.differences(newest.keys, build.keys, ctx?.datasetA.orEmpty(), device)
                    install != null -> install.changes.firstOrNull { it.subId == sim.subId }?.keys.orEmpty().filterNot { DeviceKeys.owned(it.source) || it.key in device }.map { Changed(it.key, it.old, it.new, it.source) }
                    else -> null
                }
                if (diffs == null) {
                    notes += "slot ${sim.slot + 1}: the newest dataset's values are not readable while held; the split waits"
                    continue
                }
                if (newest == null) store.update { it.logged(System.currentTimeMillis(), "voice_loss", "slot ${sim.slot + 1}: the held dataset's bundle is not readable; the split uses the install's ${diffs.size} changes") }
                c = CampaignPlanner.start(UUID.randomUUID().toString().take(8), sim, status.installed?.commit.orEmpty(), status.installed?.datasetDigest.orEmpty(), diffs, ctx?.datasetA.orEmpty(), System.currentTimeMillis(), CampaignPlanner.VOICE_LOSS_SPLIT)
                    .copy(held = true)
                store.update { s -> s.copy(campaigns = s.campaigns.filterNot { it.subId == sim.subId } + c) }
                store.update { it.logged(System.currentTimeMillis(), "voice_loss", "slot ${sim.slot + 1}: the split starts over ${diffs.size} value(s) that differ from the build's") }
            }
            val runner = TrialRunner(context, store, phone, client, sim, ctx, status, if (testNow) TrialRunner.OWNER_INTERRUPTS else TrialRunner.INTERRUPTS, deadline)
            notes += runner.run(c, basis)
            val end = runner.last?.let(VoiceLoss::splitEnd) ?: return notes + "slot ${sim.slot + 1}: the split goes on at the next idle moment"
            ends += end
        }
        if (ends.size < sims.size) return notes
        val noWinner = ends.filterIsInstance<VoiceLoss.SplitEnd.NoWinner>()
        val winners = ends.filterIsInstance<VoiceLoss.SplitEnd.Winners>().flatMap { it.keys }
        if (noWinner.isNotEmpty()) {
            val keys = noWinner.flatMap { it.keys }
            val reason = if (keys.isEmpty()) "the split could not decide which newest value removed calls" else "no other value works in place of ${keys.joinToString()}"
            client.stop("voice-loss split: $reason; the build's dataset stays held")
            store.update { it.copy(stop = "voice_loss_split_no_winner").logged(System.currentTimeMillis(), "stop", "voice-loss split: $reason; the build's dataset stays held until Resume") }
            OwnerNotifications.notice(context, Notice("The ROM's own data stays", "Calls stopped working with Open Carrier Data's newest data, and $reason. The ROM's own data keeps serving. Tap Resume to try again.", failed = true))
            return notes + "split: $reason; held, stop event"
        }
        val outcome = if (winners.isEmpty()) OcdDataProtocol.OUTCOME_NO_FAILING_VALUE else OcdDataProtocol.OUTCOME_WINNERS
        val a = client.voiceLossRelease(outcome, if (winners.isEmpty()) "every newest value passed together" else "winners in place of ${winners.joinToString()}")
        val line = "voice-loss split: ${outcome.replace('_', ' ')}${if (winners.isEmpty()) "" else " (${winners.joinToString()})"}; release ${if (a.ok) "done: the newest dataset serves again" else a.line}"
        store.update { it.logged(System.currentTimeMillis(), "voice_loss", line) }
        if (a.ok) OwnerScheduler.voiceCheck(context)
        status(context, store)
        return notes + line
    }

    /** Resume after a stop event: the app's own flag and CarrierSettings's "changes halted". */
    fun resume(context: Context, store: StateStore): String {
        val a = client(context).resume()
        val line = if (a.ok || a.error == DataAnswer.ABSENT) "changes resumed" else "CarrierSettings did not resume: ${a.line}"
        store.update { it.logged(System.currentTimeMillis(), "resume", line) }
        status(context, store)
        return line
    }
}

/** What a SIM's tests need from the pulled dataset: the key types, group A, and the network's profiles and Google values. */
class DatasetContext private constructor(
    private val dir: File,
    val types: Map<String, String>,
    val datasetA: Set<String>,
    private val googleIncluded: Boolean,
) {
    fun type(key: String): String? = types[key] ?: TypedValues.typeBySuffix(key)

    private val networks = HashMap<String, JsonObject?>()
    private val google = HashMap<String, JsonObject?>()

    private fun network(mccmnc: String): JsonObject? = networks.getOrPut(mccmnc) {
        runCatching { DatasetCheck.network(File(dir, "profiles.json"), mccmnc.take(3), mccmnc.drop(3)) }.getOrNull()
    }

    private fun googleValues(entry: String): JsonObject? = if (!googleIncluded) null else google.getOrPut(entry) {
        runCatching {
            File(dir, "google-lane.json").bufferedReader().use { io.github.open_carrier_data.check.owner.core.StrictJson.scan(it, capture = listOf("entries", entry, "values")).captured as? JsonObject }
        }.getOrNull()
    }

    /** The stack's profiles from profiles.json, generic to specific, as [explain] names them. */
    fun stack(explain: ExplainView): List<JsonObject> {
        val net = network(explain.network.replace("-", "").ifEmpty { return emptyList() }) ?: return emptyList()
        val profiles = (net["profiles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return explain.stack.mapNotNull { id -> profiles.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id } }
    }

    /** The Google value of [key] for the SIM: its fill entry's, else the lane's default. Null when the dataset has no Google lane (the baked one is CarrierSettings's). */
    fun google(key: String, explain: ExplainView): JsonElement? =
        explain.fillEntry?.let { googleValues(it)?.get(key) } ?: googleValues("default")?.get(key)

    /** device-records.json's records, read once. */
    private val records: JsonArray? by lazy {
        runCatching {
            File(dir, "device-records.json").bufferedReader().use { io.github.open_carrier_data.check.owner.core.StrictJson.scan(it, capture = listOf("records")).captured as? JsonArray }
        }.getOrNull()
    }

    /**
     * The candidates for a failing key ([Candidates]); round 31: a value a measured record of a neighbouring cell holds
     * (this device and carrier, another ROM or stack) goes first, when it is one of them.
     */
    fun candidates(ch: Changed, explain: ExplainView, cell: RecordCell? = null, carrier: RecordCarrier? = null): List<Candidate> {
        val list = Candidates.of(ch.key, ch.new ?: explain.keys[ch.key]?.value, type(ch.key), stack(explain), google(ch.key, explain), AndroidDefaults.value(ch.key))
        if (cell == null || carrier == null) return list
        return io.github.open_carrier_data.check.owner.core.NeighbourRecords.first(list, io.github.open_carrier_data.check.owner.core.NeighbourRecords.values(records, cell, carrier, ch.key, System.currentTimeMillis()))
    }

    /** The values a record for [key] may hold (spec 6.4), for the app's own check before put_record. */
    fun recordCandidates(key: String, explain: ExplainView): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        explain.keys[key]?.value?.let { out += it }
        for (p in stack(explain)) {
            (p["values"] as? JsonObject)?.get(key)?.let { out += it }
            ((p["alternatives"] as? JsonObject)?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("value") }.forEach { out += it }
        }
        google(key, explain)?.let { out += it }
        TypedValues.bool(explain.keys[key]?.value)?.let { out += JsonPrimitive(!it) }
        return out
    }

    companion object {
        fun load(context: Context, pulled: PulledDataset?): DatasetContext? {
            val dir = OwnerDatasetStep.dir(context, pulled) ?: return null
            val (keys, groups) = runCatching { DatasetCheck.registry(File(dir, "profiles.json")) }.getOrNull() ?: return null
            val types = keys.orEmpty().mapNotNull { (k, v) -> ((v as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            return DatasetContext(dir, types, Groups.datasetA(groups), pulled?.googleLane == "included")
        }
    }
}

/** Android's defaults (CarrierConfigManager.getDefaultConfig, a static API), as JSON for comparison and as raw values for an override. */
object AndroidDefaults {
    /** getDefaultConfig() is a static @SystemApi; the hidden-API allowlist lets the owner build reach it, as overrideConfig. */
    private val defaults: PersistableBundle? by lazy {
        runCatching { CarrierConfigManager::class.java.getMethod("getDefaultConfig").invoke(null) as? PersistableBundle }.getOrNull()
    }

    @Suppress("DEPRECATION")
    fun raw(key: String): Any? = defaults?.get(key)

    fun value(key: String): JsonElement? = when (val v = raw(key)) {
        null -> null
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Array<*> -> JsonArray(v.map { JsonPrimitive(it?.toString()) })
        is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
        else -> null
    }
}

/** Typed values into a PersistableBundle for a non-persistent override (spec 2.3's types). */
object OverrideBundle {
    fun of(values: Map<String, TypedOverride>): PersistableBundle = PersistableBundle().apply {
        for ((key, o) in values) {
            if (o.value == null) putRaw(this, key, AndroidDefaults.raw(key)) else putTyped(this, key, o.type ?: shape(o.value), o.value)
        }
    }

    private fun shape(v: JsonElement): String = when (v) {
        is JsonArray -> if (v.all { (it as? JsonPrimitive)?.isString == true }) "string_array" else "int_array"
        is JsonObject -> "bundle"
        is JsonPrimitive -> when {
            v.isString -> "string"
            v.booleanOrNull != null -> "bool"
            v.intOrNull != null -> "int"
            v.longOrNull != null -> "long"
            else -> "double"
        }
        JsonNull -> "string"
    }

    private fun putTyped(b: PersistableBundle, key: String, type: String, v: JsonElement) {
        val p = v as? JsonPrimitive
        when (type) {
            "bool" -> b.putBoolean(key, p!!.booleanOrNull!!)
            "int" -> b.putInt(key, p!!.intOrNull!!)
            "long" -> b.putLong(key, p!!.longOrNull!!)
            "double" -> b.putDouble(key, p!!.doubleOrNull!!)
            "string" -> b.putString(key, p!!.contentOrNull)
            "string_array" -> b.putStringArray(key, (v as JsonArray).map { (it as JsonPrimitive).content }.toTypedArray())
            "int_array" -> b.putIntArray(key, (v as JsonArray).map { (it as JsonPrimitive).intOrNull!! }.toIntArray())
            "bundle" -> b.putPersistableBundle(key, PersistableBundle().apply {
                for ((inner, tv) in v as JsonObject) {
                    val o = tv as JsonObject
                    putTyped(this, inner, (o["type"] as JsonPrimitive).content, o["value"]!!)
                }
            })
            else -> throw IllegalArgumentException("type $type")
        }
    }

    private fun putRaw(b: PersistableBundle, key: String, v: Any?) {
        when (v) {
            null -> b.putString(key, null)
            is Boolean -> b.putBoolean(key, v)
            is Int -> b.putInt(key, v)
            is Long -> b.putLong(key, v)
            is Double -> b.putDouble(key, v)
            is String -> b.putString(key, v)
            is IntArray -> b.putIntArray(key, v)
            is LongArray -> b.putLongArray(key, v)
            is BooleanArray -> b.putBooleanArray(key, v)
            is DoubleArray -> b.putDoubleArray(key, v)
            is Array<*> -> b.putStringArray(key, v.map { it?.toString() }.toTypedArray())
            is PersistableBundle -> b.putPersistableBundle(key, v)
            else -> throw IllegalArgumentException("default of $key")
        }
    }
}

/**
 * Runs a SIM's campaign: each trial a non-persistent override bundle on top of what CarrierSettings
 * serves (registered in the state first), the tests, the verdict, the records. A chain of trials
 * sets one bundle per trial and clears once at the end. It stops for this idle moment when a cap is
 * reached, a test was not counted, the screen came on or a call rang.
 */
class TrialRunner(
    private val context: Context,
    private val store: StateStore,
    private val phone: OwnerPhone,
    private val client: DatasetClient,
    private val sim: OwnerSim,
    private val ctx: DatasetContext?,
    private val status: DatasetStatus,
    /** What stops the chain for this idle moment; "Test now" stops only for a call, an emergency state or roaming ([IdleMoment.OWNER]). */
    private val interrupts: Set<IdleBlock> = INTERRUPTS,
    /** No new trial starts after this (a worker has about 10 minutes); the campaign goes on next time. */
    private val deadline: Long = Long.MAX_VALUE,
) {
    private val session = OwnerSession(phone, store, sim)

    /**
     * The dataset a record is measured on: the installed one, or the build's when that serves (spec 2.10 `data`).
     * During round 31's voice-loss hold the split measures the installed (newest) dataset's values over the held build's.
     */
    private val served: io.github.open_carrier_data.check.owner.core.DatasetRef? get() = if (status.servingInstalled || status.voiceLoss.hold) status.installed ?: status.buildPin else status.buildPin

    /** The campaign as [run] left it (done, or where it stopped for this idle moment). */
    var last: Campaign? = null
        private set
    private var current: Set<String> = emptySet()
    private var servedKeys: Map<String, io.github.open_carrier_data.check.owner.core.ExplainKey> = emptyMap()

    /** Round 31: the keys the ROM's device override sets on this phone ([DeviceKeys]): never overridden, tested or recorded. */
    private var device: Set<String> = emptySet()

    /**
     * Round 31 (SM8, SM9): why only the passive check runs (no override, call, SMS, candidate or X5 trial), or null:
     * an IMS stack the tests are not calibrated on, or a ROM build whose person did not turn the active tests on.
     */
    private val passiveOnly: String? get() = StackCalibration.notCounted(status.build.imsStackId)
        ?: if (!OwnerTester.policy(store.read()).activeTests) "the active tests are off on this phone; only the passive checks run" else null

    private fun log(kind: String, text: String) = store.update { it.logged(System.currentTimeMillis(), kind, text) }

    suspend fun run(start: Campaign, basis: String): String {
        val explain = ExplainView.parse(sim.subId, client.explain(sim.subId)) ?: return "slot ${sim.slot + 1}: explain not readable; tests wait"
        servedKeys = explain.keys
        device = DeviceKeys.of(explain, status)
        var c = CampaignPlanner.withoutDevice(start, device)
        val passive = passiveOnly
        val notes = mutableListOf<String>()
        try {
            // An install's trials compare with IMS before the install; a re-test compares with now.
            val before = c.baseline ?: obs()
            while (!c.done) {
                val plan = CampaignPlanner.next(c) ?: break
                if (System.currentTimeMillis() > deadline) {
                    notes += "slot ${sim.slot + 1}: time is up for this run; the tests go on next time"
                    break
                }
                if (TestCaps.trialsLeft(store.read().caps, sim.key, System.currentTimeMillis()) <= 0 && (plan.overrides.isNotEmpty() || current.isNotEmpty())) {
                    notes += "slot ${sim.slot + 1}: the hourly or daily cap of IMS re-registrations is reached; the tests go on in the next idle moment"
                    break
                }
                val block = IdleMoment.blockers(OwnerDatasetStep.idleInputs(context, store, phone, listOf(sim))).firstOrNull { it in interrupts }
                if (block != null) {
                    notes += "slot ${sim.slot + 1}: ${block.plain}; the tests go on next time"
                    break
                }
                if (passive != null && (plan.overrides.isNotEmpty() || c.held)) {
                    c = c.copy(phase = io.github.open_carrier_data.check.owner.core.CampaignPhase.DONE, endedAtMs = System.currentTimeMillis(), note = passive)
                    store.update { s -> s.copy(campaigns = s.campaigns.filterNot { it.id == c.id } + c) }
                    break
                }
                val effective = if (c.held) CampaignPlanner.onHeld(c, plan) else plan
                val verdict = trial(if (passive != null) effective.copy(classes = effective.classes - setOf(TestClass.VOICE, TestClass.WIFI_CALLING, TestClass.SMS)) else effective, before, explain)
                val (next, settled) = if (passive != null) CampaignPlanner.passive(c, verdict, passive, System.currentTimeMillis()) to emptyList()
                else CampaignPlanner.advance(c, verdict, { ch -> if (ch.key in device) emptyList() else ctx?.candidates(ch, explain, cell(), carrier(explain)).orEmpty() }, System.currentTimeMillis())
                c = next
                store.update { s -> s.copy(campaigns = s.campaigns.filterNot { it.id == c.id } + c) }
                log("tests", "slot ${sim.slot + 1}: ${plan.label}: ${verdict.outcome.name.lowercase()} (${verdict.reason})")
                for (s in settled) notes += settle(s, explain, c)
                if (verdict.outcome == TrialOutcome.NOT_COUNTED) {
                    notes += "slot ${sim.slot + 1}: not counted (${verdict.reason}); tried again in the next idle moment"
                    break
                }
            }
            // X5: the held offs of this SIM, each tested as an override when the caps allow (never on the passive check alone or in the split).
            if (c.done && passive == null && !c.held) notes += x5(explain)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SafetyException) {
            notes += "slot ${sim.slot + 1}: ${e.reason}"
        } finally {
            withContext(NonCancellable) { endChain() }
        }
        last = c
        if (c.done) {
            val end = CampaignEnd(sim.subId, sim.identity.mccmnc, System.currentTimeMillis(), basis, c.commit, c.note, c.winners.size, c.noValue.size)
            store.update { s -> s.copy(campaigns = s.campaigns.filterNot { it.id == c.id }, campaignEnds = (s.campaignEnds + end).takeLast(CampaignEnd.MAX)) }
            notes += "slot ${sim.slot + 1}: ${c.note}"
            // Round 32 (D8 and D7 rule 4, rounds 34 and 36): a measured failure no value explained opens the APN candidates.
            if (!c.held) for (failure in ApnFailures.of(c.lastVerdict)) notes += OwnerApnCandidates.afterFailure(context, store, phone, client, status, sim, failure)
        }
        return notes.joinToString("; ")
    }

    private suspend fun obs(): TrialObs = observe(phone, store, sim)

    /** Sets [overrides] (on top of the served bundle), keeping the keys of the trial before at their served values. */
    private suspend fun set(overrides: Map<String, Candidate>, label: String): Boolean {
        TrialGuard.check(overrides.keys, ctx?.datasetA.orEmpty(), device)
        val restore = (current - overrides.keys).associateWith { k -> TypedOverride(ctx?.type(k), servedKeys[k]?.value) }
        val values = overrides.mapValues { (k, c) -> TypedOverride(ctx?.type(k), c.value) } + restore
        if (values.isEmpty()) return true
        val now = System.currentTimeMillis()
        // Registered first: from here on any exit finds it and clears it.
        store.update { s -> s.copy(trial30 = Trial30(sim.slot, sim.subId, sim.identity.mccmnc, (current + overrides.keys).toList(), s.trial30?.sinceMs ?: now, label), caps = TestCaps.add(s.caps, sim.key, CapEvent.REREGISTRATION, now)) }
        val r = phone.ccSetBundle(sim.slot, sim.subId, values)
        current = current + overrides.keys
        if (!r.ok) {
            log("trial", "slot ${sim.slot + 1}: the override did not take: ${r.reason}")
            return false
        }
        return true
    }

    /** Clears the slot's overrides at the end of a chain, proven in the carrier config; an unprovable clear is a stop event. */
    suspend fun endChain() {
        if (store.read().trial30 == null && current.isEmpty()) return
        val now = System.currentTimeMillis()
        val r = phone.ccClear(sim.slot, sim.subId)
        store.update { it.copy(caps = TestCaps.add(it.caps, sim.key, CapEvent.REREGISTRATION, now)) }
        val dump = phone.carrierConfigText()?.takeIf { "Phone Id = " in it }?.let(CcDump::parse)
        val left = dump?.overrides(sim.slot)?.values?.flatMap { it.keys }.orEmpty()
        if (!r.ok || dump == null || left.isNotEmpty()) {
            store.update { it.copy(stop = "a round 30 trial override could not be removed").logged(now, "stop", "slot ${sim.slot + 1}: the trial overrides could not be removed (${r.reason ?: left.take(5)})") }
            client.stop("a trial override could not be removed")
            return
        }
        current = emptySet()
        store.update { it.copy(trial30 = null) }
    }

    /** One trial: the overrides, IMS settling (up to 120 s), the calls out on the bearer IMS uses, the optional SMS, the verdict. */
    private suspend fun trial(plan: TrialPlan, before: TrialObs, explain: ExplainView): TrialVerdict {
        session.guard(callWaitMs = 0)
        val start = System.currentTimeMillis()
        if (plan.overrides.isNotEmpty() || current.isNotEmpty()) {
            if (!set(plan.overrides, plan.label)) return TrialVerdict(TrialOutcome.NOT_COUNTED, reason = "the override did not take")
        }
        val after = settle(before)
        val calls = calls(plan.classes, after, start)
        val sms = if (TestClass.SMS in plan.classes) sms(start) else null
        return TrialJudge.judge(plan.classes, before, after, calls, sms)
    }

    private suspend fun settle(before: TrialObs): TrialObs {
        var waited = 0L
        var o = obs()
        while (waited < SETTLE_MS && !(o.imsRegistered == true && o.services.containsAll(before.services) && (before.dataValidated != true || o.dataValidated == true))) {
            phone.sleep(5_000)
            waited += 5_000
            o = obs()
        }
        return o
    }

    /** A calls-out call on the bearer IMS uses now (never a move), within the daily cap, in the owner's calls-out mode; then the ledger's word. */
    private suspend fun calls(classes: Set<TestClass>, after: TrialObs, sinceMs: Long): List<CallEvidence> {
        if (TestClass.VOICE !in classes && TestClass.WIFI_CALLING !in classes) return emptyList()
        if (after.imsRegistered != true || TrialJudge.MMTEL !in after.services) return emptyList()
        val wifi = after.imsOnWifi == true
        if (wifi && TestClass.WIFI_CALLING !in classes && TestClass.VOICE !in classes) return emptyList()
        val feature = if (wifi) OwnerFeature.VOWIFI else OwnerFeature.VOLTE
        val bearer = io.github.open_carrier_data.check.owner.core.CallLevels.bearerOf(feature) ?: return emptyList()
        val state = store.read()
        val build = OwnerBasis.current(context, state).rom
        if (TestCaps.callsLeft(state.calls, sim.key, System.currentTimeMillis()) > 0) {
            val caller = MailboxCaller(phone, store, AndroidCallDevice(context), build, CallBuild.short(context), CallBuild.stack(context), OwnerWorker.device(context).model, CallBuild.cellOf(context), policy = OwnerTester.policy(state))
            runCatching { caller.run(sim, CallsOutDue(sim.slot, sim.key, feature, bearer, "round30_trial"), CallBuild.targets(context), state.callsOutMode, night = true, nightKey = QuietWindow.nightOf(ZonedDateTime.now())) }
        }
        val s = Settled.voice(store.read().calls, sim.key, feature, build, sinceMs, System.currentTimeMillis()) ?: return emptyList()
        return listOf(CallEvidence(bearer.wire, if (s.failed) CallEvidence.FAILED else CallEvidence.PASSED, if (s.level == Settled.L3) "L3" else "L2", s.method))
    }

    /** SMS both ways over IMS, only when the owner turned the test SMS on (it may cost an SMS); null when not run or not settled. */
    private suspend fun sms(sinceMs: Long): Boolean? {
        if (!store.read().selfSmsOn) return null
        if (OwnerTester.policy(store.read()).smsRefusal(CallBuild.entry(context, sim)) != null) return null
        val check = runCatching { OwnerSelfSms.send(context, phone, sim, on = true) }.getOrNull() ?: return null
        var waited = 0L
        while (waited < 60_000) {
            store.read().selfSms.firstOrNull { it.nonce == check.nonce }?.let { if (SelfSms.bothWays(it)) return true }
            phone.sleep(5_000)
            waited += 5_000
        }
        return null
    }

    /** Applies [overrides] and waits up to 60 s for IMS voice; true when voice came back. */
    suspend fun voiceBackWith(overrides: Map<String, Candidate>, label: String): Boolean {
        if (servedKeys.isEmpty()) ExplainView.parse(sim.subId, client.explain(sim.subId))?.let { servedKeys = it.keys; device = DeviceKeys.of(it, status) }
        if (!set(overrides, label)) return false
        var waited = 0L
        while (waited < 60_000) {
            phone.sleep(5_000)
            waited += 5_000
            if (runCatching { session.observe() }.getOrNull()?.voiceLost() == false) return true
        }
        return false
    }

    private fun cell(): RecordCell? = RecordCell.of(status.build)

    private fun carrier(explain: ExplainView): RecordCarrier? =
        RecordCarrier.of(explain, sim.identity.mccmnc.take(3), sim.identity.mccmnc.drop(3), sim.identity.spn?.trim(), sim.identity.gid1)

    /** A winner's record (result winner_after_failure) and the candidate values the app checks it against. */
    fun winnerRecord(ch: Changed, cand: Candidate, verdict: TrialVerdict, explain: ExplainView, trials: Int): Pair<JsonObject, List<JsonElement>>? {
        val cell = cell() ?: return null
        val carrier = carrier(explain) ?: return null
        val now = System.currentTimeMillis()
        val record = MeasuredRecord.build(
            RecordInput(
                cell, carrier, RecordTarget.Config(ch.key), cand.value?.let { RecordValue.Value(it) } ?: RecordValue.AndroidDefault,
                RecordResult.WINNER_AFTER_FAILURE, verdict.level, "override_trial", verdict.bearer, trials, now, now,
                "measured failure with ${ch.new ?: "Android's default"} (${verdict.reason.ifEmpty { "the trial's tests" }}); ${cand.plain} (${cand.origin}) passed under the same conditions",
                served?.commit.orEmpty(), served?.dataDigest.orEmpty(),
            ),
        )
        return record to (ctx?.recordCandidates(ch.key, explain).orEmpty() + listOfNotNull(cand.value))
    }

    private fun settle(s: Settlement, explain: ExplainView, c: Campaign): String {
        val cell = cell() ?: return "no record: CarrierSettings did not name this phone's cell"
        val carrier = carrier(explain) ?: return "no record: the SIM gives no SPN or GID1 for a record of its own"
        val now = System.currentTimeMillis()
        val commit = served?.commit.orEmpty()
        val digest = served?.dataDigest.orEmpty()
        return when (s) {
            is Settlement.Winner -> {
                val ch = c.changes.first { it.key == s.key }
                val (record, cands) = winnerRecord(ch, s.candidate, s.verdict, explain, s.trials) ?: return "no record"
                val line = OwnerDatasetStep.putRecord(store, client, record, cands, ctx?.type(s.key), ctx?.datasetA.orEmpty(), device)
                OwnerNotifications.notice(context, Notice(io.github.open_carrier_data.check.owner.core.Notices.CHANGED_TITLE, "${s.key}: ${s.candidate.plain} works on this phone where Open Carrier Data's value failed", failed = false))
                line
            }
            is Settlement.NoValuePassed -> {
                val record = MeasuredRecord.build(
                    RecordInput(
                        cell, carrier, RecordTarget.Config(s.key), RecordValue.None, RecordResult.NO_VALUE_PASSED, s.verdict.level, "override_trial", s.verdict.bearer,
                        s.tried.size, c.startedAtMs, now, "measured failure with ${s.failing ?: "Android's default"} (${s.verdict.reason}); none of ${s.tried.size} other values passed; the newest value stays",
                        commit, digest,
                    ),
                )
                val review = SourceReview.noValuePassed(explain.serving, s.key, s.failing, s.tried, commit, s.verdict.reason, now)
                store.update { st -> if (st.reviewsSent.contains(review.key)) st else st.copy(pendingReviews = (st.pendingReviews + review).takeLast(SourceReview.MAX)) }
                OwnerDatasetStep.putRecord(store, client, record, emptyList(), ctx?.type(s.key), ctx?.datasetA.orEmpty(), device)
            }
            // The split's pass is over the held build's bundle, not what serves: no pass record from it.
            is Settlement.Passed -> if (c.held) "passed over the held build's dataset (${s.verdict.reason})" else passRecords(s.verdict, explain, cell, carrier, commit, digest, c.startedAtMs)

        }
    }

    /** Settled passes of the three switches (calls out or works on their bearer, SMS both ways), renewed each 14 days. */
    private fun passRecords(v: TrialVerdict, explain: ExplainView, cell: RecordCell, carrier: RecordCarrier, commit: String, digest: String, sinceMs: Long): String {
        val notes = mutableListOf<String>()
        val build = OwnerBasis.current(context, store.read()).rom
        val now = System.currentTimeMillis()
        for (feature in listOf(OwnerFeature.VOLTE, OwnerFeature.VOWIFI)) {
            if (feature.key in device) continue
            val servedValue = explain.keys[feature.key]?.value ?: continue
            if (TypedValues.bool(servedValue) != true) continue
            // Only calls measured in this campaign, on the data it serves now.
            val s = Settled.voice(store.read().calls, sim.key, feature, build, sinceMs, now)?.takeIf { !it.failed } ?: continue
            val record = MeasuredRecord.build(
                RecordInput(
                    cell, carrier, RecordTarget.Config(feature.key), RecordValue.Value(servedValue), if (s.level == Settled.L3) RecordResult.WORKS else RecordResult.CALLS_OUT,
                    if (s.level == Settled.L3) "L3" else "L2", s.method.takeIf { it in MeasuredRecord.METHODS } ?: "mailbox_call", s.bearer, null, now, now,
                    "${if (s.level == Settled.L3) "calls both ways" else "calls out"} over ${s.bearer} measured on this phone (audio from packet counts)", commit, digest,
                ),
            )
            notes += OwnerDatasetStep.putRecord(store, client, record, ctx?.recordCandidates(feature.key, explain).orEmpty(), "bool", ctx?.datasetA.orEmpty(), device)
        }
        val smsServed = explain.keys[OwnerFeature.SMS_OVER_IMS.key]?.value?.takeIf { OwnerFeature.SMS_OVER_IMS.key !in device }
        val sms = Settled.sms(store.read().selfSms, sim.key, build, now)
        if (TypedValues.bool(smsServed) == true && sms != null && !sms.failed) {
            val record = MeasuredRecord.build(
                RecordInput(
                    cell, carrier, RecordTarget.Config(OwnerFeature.SMS_OVER_IMS.key), RecordValue.Value(smsServed!!), RecordResult.SMS_BOTH_WAYS, "sms_both_ways", "self_sms", sms.bearer, null, now, now,
                    "a data SMS to the phone's own number went out and came back over IMS by Telephony's own word on each leg", commit, digest,
                ),
            )
            notes += OwnerDatasetStep.putRecord(store, client, record, ctx?.recordCandidates(OwnerFeature.SMS_OVER_IMS.key, explain).orEmpty(), "bool", ctx?.datasetA.orEmpty(), device)
        }
        return if (notes.isEmpty()) "passed (${v.reason})" else notes.joinToString("; ")
    }

    /** X5: each held off tried as an override; released by a record only with a call ACTIVE on the remaining path, or an SMS delivered. */
    private suspend fun x5(explain: ExplainView): List<String> {
        val notes = mutableListOf<String>()
        for (hold in explain.x5Held) {
            if (hold.key in device) continue
            val off = Groups.X5_OFF[hold.key] ?: continue
            if (TestCaps.trialsLeft(store.read().caps, sim.key, System.currentTimeMillis()) <= 0) break
            val start = System.currentTimeMillis()
            session.guard(callWaitMs = 0)
            if (!set(mapOf(hold.key to Candidate(JsonPrimitive(off), Candidate.X5_OFF)), "X5: ${hold.key} = $off")) continue
            val after = settle(obs())
            val calls = X5Test.remainingBearer(hold.key)?.let { b ->
                if ((b == "iwlan") == (after.imsOnWifi == true)) calls(setOf(TestClass.VOICE, TestClass.WIFI_CALLING), after, start) else emptyList()
            }.orEmpty()
            val sms = if (X5Test.isSms(hold.key)) sms(start) else null
            endChain()
            if (!X5Test.passed(hold.key, calls, sms)) {
                notes += "slot ${sim.slot + 1}: ${hold.key} = $off stays held (no call on the remaining path, or no SMS delivered, with it in effect)"
                continue
            }
            val cell = cell() ?: continue
            val carrier = carrier(explain) ?: continue
            val pass = calls.firstOrNull { it.result == CallEvidence.PASSED }
            val now = System.currentTimeMillis()
            val record = MeasuredRecord.build(
                RecordInput(
                    cell, carrier, RecordTarget.Config(hold.key), RecordValue.Value(JsonPrimitive(off)),
                    if (pass != null) RecordResult.CALLS_OUT else RecordResult.SMS_BOTH_WAYS, if (pass != null) pass.level ?: "L2" else "sms_both_ways",
                    "override_trial", pass?.bearer ?: if (after.imsOnWifi == true) "iwlan" else "lte", null, now, now,
                    if (pass != null) "with ${hold.key} = $off in effect an outgoing call went ACTIVE over ${pass.bearer}" else "with ${hold.key} = $off in effect an SMS to the phone's own number was delivered",
                    served?.commit.orEmpty(), served?.dataDigest.orEmpty(),
                ),
            )
            notes += OwnerDatasetStep.putRecord(store, client, record, listOf(JsonPrimitive(off)) + ctx?.recordCandidates(hold.key, explain).orEmpty(), "bool", ctx?.datasetA.orEmpty(), device)
        }
        return notes
    }

    companion object {
        const val SETTLE_MS = 120_000L
        /** A background campaign stops when the person picks up the phone: the screen, a call, media, an emergency state, roaming. */
        val INTERRUPTS = setOf(IdleBlock.SCREEN_ON, IdleBlock.IN_CALL, IdleBlock.CALL_RECENT, IdleBlock.MEDIA, IdleBlock.EMERGENCY_MODE, IdleBlock.EMERGENCY_RECENT, IdleBlock.ROAMING)

        /** "Test now" stops only for a call, emergency callback or SMS mode and roaming; each trial's refusals add the 10 minutes after an emergency call. */
        val OWNER_INTERRUPTS = IdleMoment.OWNER

        /** IMS, data and service of one SIM as a trial compares them. */
        suspend fun observe(phone: OwnerPhone, store: StateStore, sim: OwnerSim): TrialObs {
            val o = OwnerSession(phone, store, sim).observe()
            val data = phone.mobileData(sim.subId)
            return TrialObs(o.ims.registered, o.ims.transport?.isWifi, o.ims.services.map { it.wire }.toSet(), data.validated, o.ps?.state?.let { it == "home" || it == "roaming" }, o.voiceLost())
        }
    }
}
