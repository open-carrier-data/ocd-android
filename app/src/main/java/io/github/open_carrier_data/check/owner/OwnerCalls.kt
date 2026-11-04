package io.github.open_carrier_data.check.owner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.open_carrier_data.check.core.CallTargets
import io.github.open_carrier_data.check.core.CallTargetsFile
import io.github.open_carrier_data.check.core.CallState
import io.github.open_carrier_data.check.core.PreciseCallState
import io.github.open_carrier_data.check.core.ProvenBearer
import io.github.open_carrier_data.check.core.CallWatch
import io.github.open_carrier_data.check.core.RegistryParser
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.owner.core.CallDevice
import io.github.open_carrier_data.check.owner.core.CallLedger
import io.github.open_carrier_data.check.owner.core.CallLevels
import io.github.open_carrier_data.check.owner.core.CallMethod
import io.github.open_carrier_data.check.owner.core.CallRecord
import io.github.open_carrier_data.check.owner.core.MailboxGuard
import io.github.open_carrier_data.check.owner.core.NoCallChecks
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PointerLedger
import io.github.open_carrier_data.check.owner.core.QosDump
import io.github.open_carrier_data.check.owner.core.QosFact
import io.github.open_carrier_data.check.owner.core.QosRadio
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.TrialRefusal
import io.github.open_carrier_data.check.owner.core.ImsStack
import io.github.open_carrier_data.check.owner.core.provisionalSettled
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/*
 * Round 26 on the phone: the app's own mailbox call ([AndroidCallDevice]),
 * and the passive watch of every real call ([CallWatchService]), which
 * writes direction, ringing, answered, ACTIVE, the bearer readings, RTP both
 * ways, inactivity, duration and the disconnect cause to the private ledger,
 * never the number. Neither runs in the read-only builds.
 */

/** The app's own mailbox call is up: the passive watch leaves it to the caller, which records it itself. */
object OwnCall {
    @Volatile
    var sinceElapsedMs: Long = 0

    @Volatile
    var untilElapsedMs: Long = 0

    fun overlaps(fromElapsedMs: Long, toElapsedMs: Long): Boolean =
        sinceElapsedMs != 0L && sinceElapsedMs <= toElapsedMs && (untilElapsedMs == 0L || untilElapsedMs >= fromElapsedMs)
}

/** What the levels and "re-tested by OCD" name as the software: the ROM build and the IMS stack. */
object CallBuild {
    fun short(context: Context): String = "${Build.ID} ${Build.VERSION.INCREMENTAL}, ${stack(context)}"
    fun stack(context: Context): String = ImsStack.of(VideoStatic.imsPackages(context))
    fun cellOf(context: Context): (SimKey) -> String = { key -> PointerLedger.cell(OwnerWorker.device(context), key) }

    fun targets(context: Context): CallTargetsFile? = runCatching {
        context.assets.open(CallTargets.ASSET_NAME).use { CallTargets.parse(it.readBytes().decodeToString()) }
    }.getOrNull()

    /** The SIM's own allowlist entry (its mailbox and the free flags), or null: only the SIM's own brand counts (round 46, [CallTargets.dialableFor]). */
    fun entry(context: Context, sim: io.github.open_carrier_data.check.owner.core.OwnerSim): io.github.open_carrier_data.check.core.Mailbox? =
        targets(context)?.let { CallTargets.dialableFor(it, sim.identity.mccmnc, sim.identity.spn) { false } }
}

/** What may start by itself on this phone ([TesterPolicy]). */
object OwnerTester {
    /** The provider's word is the last status read: a fresh state has none, so nothing starts until the installer has answered. */
    fun policy(context: Context, state: OwnerState) =
        io.github.open_carrier_data.check.owner.core.TesterPolicy(state.datasetStatus?.round31 == true, CallBuild.stack(context))

    /** Why a setting trial may not start, or null: the provider and the calibrated stack (SM8). */
    fun trialRefusal(context: Context, state: OwnerState): TrialRefusal? = policy(context, state).trialRefusal
}

/**
 * Telecom for the app's own mailbox call.
 * CALL_PHONE is a runtime permission, granted by
 * default-permissions-ocd-check-owner.xml in a ROM build and by `pm grant` on a
 * hand install; MODIFY_PHONE_STATE (privileged, held already) lets the app end
 * its own call. Round 47: the call is the person's tap, so nothing is muted: a
 * process death could otherwise leave the voice stream at its lowest for the next
 * real call, 112 included, and the entry stays in the call log.
 */
class AndroidCallDevice(private val context: Context) : CallDevice {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    @SuppressLint("MissingPermission")
    override suspend fun isEmergencyNumber(number: String, subId: Int): Boolean {
        val tm = telephony ?: error("no telephony")
        // Android's own answer (every subscription), and the emergency lists it holds for each.
        if (tm.isEmergencyNumber(number)) return true
        val lists = tm.emergencyNumberList
        return lists.values.flatten().any { it.number == number }
    }

    override suspend fun inEmergencyCall(): Boolean? = SystemBinders.call(SystemBinders.telecom(context), SystemBinders.TELECOM, "isInEmergencyCall")

    @SuppressLint("MissingPermission")
    override suspend fun place(number: String, subId: Int): Boolean {
        // The last look before the dial: the shape, the fixed emergency list and the service prefixes.
        if (MailboxGuard.refusal(number) != null) return false
        // The SIM's phone account is looked up by its subscription (Android 11 and later); the S20 runs Android 16.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        val tc = telecom ?: return false
        val tm = telephony ?: return false
        // The SIM's own phone account, so Android never asks which SIM.
        val account = runCatching { tc.callCapablePhoneAccounts.firstOrNull { tm.getSubscriptionId(it) == subId } }.getOrNull() ?: return false
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
        }
        OwnCall.sinceElapsedMs = SystemClock.elapsedRealtime()
        OwnCall.untilElapsedMs = 0
        return runCatching { withContext(Dispatchers.Main) { tc.placeCall(Uri.fromParts("tel", number, null), extras) } }.isSuccess
    }

    /** Telecom's endCall: MODIFY_PHONE_STATE lets the app use it; Telecom never ends an emergency call this way. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override suspend fun end(): Boolean = runCatching { telecom?.endCall() == true }.getOrDefault(false)

    /** The app's own call is over: from here on the passive watch counts every call again. */
    override fun callEnded() {
        OwnCall.untilElapsedMs = SystemClock.elapsedRealtime()
    }
}

/**
 * The passive call watch's trigger: Android's PHONE_STATE broadcast, sent by
 * the system to apps holding READ_PHONE_STATE (and waking them). A ringing or
 * off-hook state starts [CallWatchService]; nothing else happens here.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        // The call watch's record across restarts first, also before the first unlock (device-protected storage, times only).
        runCatching {
            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING, TelephonyManager.EXTRA_STATE_OFFHOOK -> CallSightings.started(context)
                // A second call may still be up on the other SIM.
                TelephonyManager.EXTRA_STATE_IDLE -> if (!inCall(context)) CallSightings.ended(context)
            }
        }
        if (state != TelephonyManager.EXTRA_STATE_RINGING && state != TelephonyManager.EXTRA_STATE_OFFHOOK) return
        // Before the first unlock only the record above: the watch and the state file need credential storage.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        if (!OwnerAccess.privilegedGranted(context)) return
        runCatching { context.startService(Intent(context, CallWatchService::class.java)) }
            .onFailure { e -> FileStateStore(context).update { it.logged(System.currentTimeMillis(), "call_watch", "the call watch could not start: ${e.javaClass.simpleName}") } }
    }

    @SuppressLint("MissingPermission")
    private fun inCall(context: Context): Boolean = runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall == true }.getOrDefault(false)
}

/**
 * Watches one real call from the side, once a second, through the
 * registry (DUMP): direction, ringing, answered, ACTIVE, the bearer readings
 * at the start, at ACTIVE and at hang-up, RTP both ways, inactivity, duration
 * and the disconnect cause. Mailbox numbers are compared in the parser only;
 * the caller's or callee's number is never read into the record. An
 * emergency call, or the app's own mailbox call, leaves nothing here.
 */
class CallWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        scope.launch {
            try {
                watch()
            } catch (e: Exception) {
                FileStateStore(this@CallWatchService).update { it.logged(System.currentTimeMillis(), "call_watch", "the call watch stopped: ${e.javaClass.simpleName}") }
            } finally {
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        val context = this
        val phone = PrivilegedPhone(context)
        val sims = runCatching { OwnerData.sims(context) }.getOrDefault(emptyList())
        val mailboxes = CallBuild.targets(context)?.let { CallTargets.allMailboxes(it) }.orEmpty().toSet()
        val slots = sims.mapNotNull { SimSlot.of(it.slot) }.ifEmpty { listOfNotNull(SimSlot.of(0)) }
        val watches = slots.associateWith { CallWatch() }
        val started = SystemClock.elapsedRealtime()
        // Round 49: local only, read as the call starts; the week report drops a call from roaming.
        val roaming = QuietReader.roaming(context)
        val rom = OwnerBasis.current(context, FileStateStore(context).read()).rom
        val wantRadio = NoCallChecks.radioWanted(FileStateStore(context).read().noCall.radio, rom)
        var activeSince: Long? = null
        var radioRead: Deferred<QosRadio?>? = null
        var idleRun = 0
        var sawCall = false
        var emergency = false
        while (SystemClock.elapsedRealtime() - started < MAX_MS) {
            val text = phone.registryText()
            val now = SystemClock.elapsedRealtime()
            var anyCall = false
            for ((slot, watch) in watches) {
                val sample = text?.let { RegistryParser.parse(it.lineSequence(), slot, mailboxes)?.call?.copy(atMs = now) }
                watch.add(sample)
                if (sample?.state != null && sample.state != CallState.IDLE) anyCall = true
                if (sample?.foreground == PreciseCallState.ACTIVE && activeSince == null) activeSince = now
            }
            if (SystemBinders.call(SystemBinders.telecom(context), SystemBinders.TELECOM, "isInEmergencyCall") == true) emergency = true
            // Round 49: one read of the radio fact per build, once the call has been up long enough to hold a dedicated bearer.
            if (wantRadio && radioRead == null && !emergency && activeSince?.let { now - it >= NoCallChecks.RADIO_READ_AFTER_MS } == true) {
                radioRead = scope.async { QosDump.radio(phone.telephonyText()) }
            }
            if (anyCall) {
                sawCall = true
                idleRun = 0
            } else if (sawCall) {
                idleRun++
            } else if (now - started > 10_000) {
                break
            }
            // A few readings after the end: the disconnect cause that stands for this call.
            if (sawCall && idleRun >= 3) break
            delay(POLL_MS)
        }
        val ended = SystemClock.elapsedRealtime()
        val store = FileStateStore(context)
        if (emergency) {
            // Kept for the refusals across restarts: when it ended, never the number.
            runCatching { CallSightings.emergency(context, System.currentTimeMillis() - (ended - started)) }
            store.update { it.logged(System.currentTimeMillis(), "call_watch", "a call was not recorded: an emergency call showed") }
            return
        }
        if (OwnCall.overlaps(started, ended)) return
        val state = store.read()
        val build = OwnerBasis.current(context, state).rom
        val stack = CallBuild.stack(context)
        for ((slot, watch) in watches) {
            val summary = watch.summary()
            if (summary.callsSeen == 0 && !summary.emergencySeen) continue
            val sim = sims.firstOrNull { it.slot == slot.index }
            val record = CallRecord.of(summary, System.currentTimeMillis(), slot.index, sim?.key, CallMethod.REAL_CALL, build, stack, roaming = roaming)
            if (record == null) {
                store.update { it.logged(System.currentTimeMillis(), "call_watch", "a call was not recorded: an emergency call showed") }
                continue
            }
            recordCall(context, record)
            // The fact counts only from a real call proven on LTE, on this one call.
            if (record.bearer?.counted == true && record.bearer.bearer == ProvenBearer.LTE && !record.severalCalls) {
                runCatching { radioRead?.await() }.getOrNull()?.let { radio ->
                    store.update { it.copy(noCall = it.noCall.copy(radio = QosFact(rom, radio))) }
                }
            }
        }
    }

    companion object {
        const val POLL_MS = 1_000L
        const val MAX_MS = 4 * 60 * 60_000L

        /** Adds a measured call to the ledger, logs one plain line, and queues the reverts it settled. */
        fun recordCall(context: Context, record: CallRecord) {
            val store = FileStateStore(context)
            var reverts: List<Triple<Int, OwnerFeature, String>> = emptyList()
            val now = System.currentTimeMillis()
            store.update { s ->
                val (next, r) = CallLedger.add(s, record, now, CallBuild.cellOf(context), CallBuild.short(context))
                reverts = r
                next.provisionalSettled().logged(now, "call_watch", CallLevels.line(record, ZoneId.systemDefault()))
            }
            OwnerScheduler.dropKeeps(context, reverts)
            // Round 47: the second failed real call after a change of calls or SMS: the hold, at once, under the Guard's refusals.
            if (store.read().realCallHoldDue) OwnerScheduler.dataset(context)
        }
    }
}

/** Settles the provisional keeps against the ledger now (the night's end, 7 days without incoming); queues their reverts. */
fun settleProvisional(context: Context, state: OwnerState): OwnerState {
    val store = FileStateStore(context)
    var reverts: List<Triple<Int, OwnerFeature, String>> = emptyList()
    val after = store.update { s ->
        val (next, r) = CallLedger.settle(s.provisionalSettled(), OwnerBasis.current(context, s).rom, System.currentTimeMillis())
        reverts = r
        next
    }
    OwnerScheduler.dropKeeps(context, reverts)
    return after
}

/** Queues a drop of provisional keeps (any silent window; the guards still apply). */
fun OwnerScheduler.dropKeeps(context: Context, reverts: List<Triple<Int, OwnerFeature, String>>) {
    if (reverts.isEmpty()) return
    val wm = WorkManager.getInstance(context)
    for ((slot, feature, reason) in reverts) {
        val request = OneTimeWorkRequestBuilder<OwnerWorker>()
            .setInputData(
                workDataOf(
                    OwnerWorker.KEY_STEP to OwnerWorker.STEP_DROP_KEEP, OwnerWorker.KEY_SLOT to slot,
                    OwnerWorker.KEY_FEATURE to feature.wire, OwnerWorker.KEY_REASON to reason,
                ),
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 2, java.util.concurrent.TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork("owner-drop-$slot-${feature.wire}", ExistingWorkPolicy.KEEP, request)
    }
}
