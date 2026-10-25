package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.telephony.AccessNetworkConstants
import android.telephony.PreciseDataConnectionState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.data.ApnSetting
import android.util.Log
import io.github.open_carrier_data.check.owner.core.DatasetClient
import io.github.open_carrier_data.check.owner.core.EimsAfter
import io.github.open_carrier_data.check.owner.core.EimsAttempt
import io.github.open_carrier_data.check.owner.core.EimsInputs
import io.github.open_carrier_data.check.owner.core.EmergencyData
import io.github.open_carrier_data.check.owner.core.EmergencyDump
import io.github.open_carrier_data.check.owner.core.EmergencyFirstUse
import io.github.open_carrier_data.check.owner.core.EmergencySession
import io.github.open_carrier_data.check.owner.core.ExplainRow
import io.github.open_carrier_data.check.owner.core.ExplainView
import io.github.open_carrier_data.check.owner.core.MeasuredRecord
import io.github.open_carrier_data.check.owner.core.Notice
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.RecordCarrier
import io.github.open_carrier_data.check.owner.core.RecordCell
import io.github.open_carrier_data.check.owner.core.RecordInput
import io.github.open_carrier_data.check.owner.core.RecordResult
import io.github.open_carrier_data.check.owner.core.RecordTarget
import io.github.open_carrier_data.check.owner.core.RecordValue
import io.github.open_carrier_data.check.owner.core.RegistryText
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The emergency data test on the phone (OCD round 30, "emergencyDataTest"): a request for
 * NET_CAPABILITY_EIMS on the SIM's home mobile network, with the subscription's network specifier,
 * that waits at most 15 s and is released within 5 s of CONNECTED. It needs
 * CONNECTIVITY_USE_RESTRICTED_NETWORKS (the owner build's privapp file). It places no call, dials
 * no number, sends nothing, and starts no emergency registration; releasing the app's own request
 * leaves any other request, such as a real emergency call's, untouched.
 *
 * Every step goes to the device log (tag [TAG]) for the first use, which the owner runs by hand
 * with `adb logcat` streamed. Until the owner records that the first use passed, the app never runs
 * the test on its own and writes no record.
 */
object OwnerEmergencyData {
    const val TAG = "OcdCheckEims"
    private const val NO_STATE = Int.MIN_VALUE

    private fun log(store: StateStore, text: String) {
        runCatching { Log.i(TAG, text) }
        store.update { it.logged(System.currentTimeMillis(), "emergency_data", text) }
    }

    /**
     * One session on [sim]: the row's request, and (only after a counted failure) Android's default.
     * [manual]: the owner's run over adb (the first use); it skips only the due rules, never a
     * condition. Returns the log line, or null when nothing was due.
     */
    @SuppressLint("MissingPermission")
    suspend fun session(context: Context, store: StateStore, phone: OwnerPhone, client: DatasetClient, sim: OwnerSim, manual: Boolean): String? {
        val state = store.read()
        val firstUse = state.emergencyFirstUse
        if (!manual && firstUse?.passed != true) return null
        if (firstUse?.result == EmergencyFirstUse.NOT_MEANINGFUL && !manual) return null
        val status = OwnerDatasetStep.status(context, store)
        if (!status.available) return "emergency data test: no dataset installer on this ROM"
        val explain = ExplainView.parse(sim.subId, client.explain(sim.subId)) ?: return "emergency data test: explain not readable"
        val row: ExplainRow? = explain.emergencyRow
        val basis = EmergencyData.basis(OwnerBasis.current(context, state).rom, status.build.imsStackId, status.build.imsStackVersion, runCatching { Build.getRadioVersion() }.getOrNull())
        val now = System.currentTimeMillis()
        if (!manual) EmergencyData.due(state.emergencySessions, sim.key, row?.rowKey, basis, now) ?: return null
        val session = OwnerSession(phone, store, sim)
        val safety = runCatching { session.safety() }.getOrNull()
        val o = runCatching { session.observe() }.getOrNull()
        val registry = phone.registryText()
        val telephony = phone.telephonyText()
        val rat = when (o?.ps?.rat) {
            "lte", "lte_ca" -> "lte"
            "nr" -> "nr"
            else -> o?.ps?.rat
        }
        val calls = runCatching { phone.callsSeen() }.getOrNull()
        val inputs = EimsInputs(
            inCall = QuietReader.inCall(context, registry),
            // Any call, the app's own mailbox calls included (round 30 item 5: never within 10 minutes of a call).
            lastCallSeenAtMs = listOfNotNull(state.lastCallSeenAtMs, io.github.open_carrier_data.check.owner.core.RestartWindow.lastCallAtMs(calls, own = true)).maxOrNull(),
            emergencySeenAtMs = state.emergencySeen?.firstSeenAtMs,
            upMs = minOf(SystemClock.elapsedRealtime(), runCatching { session.serverUpMs() }.getOrNull() ?: Long.MAX_VALUE),
            ecbm = safety?.emergency?.ecbm,
            emergencySmsMode = safety?.emergency?.emergencySmsMode,
            roaming = safety?.roaming?.roaming?.let { it || o?.ps?.state == "roaming" },
            eimsExists = eimsNetwork(context),
            dataRat = rat,
            emcSupported = EmergencyDump.emcSupported(RegistryText.block(registry.orEmpty(), sim.slot), rat),
            dataWorks = phone.mobileData(sim.subId).validated,
            honorRetryTimer = EmergencyDump.honorRetryTimer(telephony, sim.slot),
            emergencyTransport = EmergencyDump.emergencyTransport(telephony, sim.slot),
            stopped = state.emergencyStop ?: state.stop ?: status.changesHalted.reason?.takeIf { status.changesHalted.on } ?: "safe mode".takeIf { status.safeMode.on },
            nowMs = now,
            callsSeen = calls,
        )
        val refusal = EmergencyData.refusal(inputs)
        if (refusal != null) {
            store.update { it.copy(emergencySessions = EmergencyData.add(it.emergencySessions, EmergencySession(sim.subId, sim.identity.mccmnc, row?.rowKey, basis, EmergencyData.day(now), "refused", now))) }
            log(store, "slot ${sim.slot + 1}: not run: $refusal")
            return "emergency data test not run: $refusal"
        }
        val imsBefore = phone.imsApi(sim.slot, sim.subId)?.let { "${it.state().registered}/${it.state().logTransport}/${it.state().services.sortedBy { s -> s.wire }}" }
        log(store, "slot ${sim.slot + 1}: requesting the emergency data network (EIMS) with ${row?.let { "row ${it.rowKey} ${it.apn} (${it.protocol})" } ?: "Android's default sos profile"}; no call, nothing sent, released within 5 s")
        val attempt = request(context, sim.subId, row)
        val protocol = row?.protocol?.ifEmpty { "IPV4V6" } ?: "IPV4V6"
        val verdict = EmergencyData.classify(attempt, protocol)
        log(store, "slot ${sim.slot + 1}: ${verdict.wire}: connected=${attempt.connected} wwan=${attempt.cellular} profile=${attempt.profileMatches} families=${attempt.families.sorted()} cause=${attempt.failCause} released_in_time=${attempt.releasedInTime}${if (attempt.aborted) " (aborted: a call state changed)" else ""}")
        delay(3_000)
        val after = after(context, phone, session, sim, imsBefore)
        EmergencyData.stopEvent(attempt, after, verdict)?.let { why ->
            store.update { s ->
                s.copy(emergencyStop = why, stop = s.stop ?: "emergency_data_test: $why", emergencySessions = EmergencyData.add(s.emergencySessions, EmergencySession(sim.subId, sim.identity.mccmnc, row?.rowKey, basis, EmergencyData.day(now), "stopped", now)))
            }
            client.stop("emergency data test: $why")
            log(store, "STOP slot ${sim.slot + 1}: $why; the test stops until a judged round")
            OwnerNotifications.notice(context, Notice("Emergency data test stopped", "$why. Tests and changes are paused until you tap Resume; the test itself stays stopped until a judged round.", failed = true))
            return "STOP: $why"
        }
        if (verdict == EmergencyData.Verdict.WIFI) {
            store.update { it.copy(emergencySessions = EmergencyData.add(it.emergencySessions, EmergencySession(sim.subId, sim.identity.mccmnc, row?.rowKey, basis, EmergencyData.day(now), "not_counted", now))) }
            return "emergency data connection came up on Wi-Fi: released at once, nothing recorded"
        }
        // Android's default is tested only after a counted failure of the row, in the same session. While the row is
        // selected, Telephony always uses it; the default can be requested only when the SIM's selection has no row.
        val default: EmergencyData.Verdict? = if (verdict == EmergencyData.Verdict.COUNTED_FAIL && row == null) verdict else null
        val result = EmergencyData.result(verdict, default)
        val outcome = when {
            result == RecordResult.EMERGENCY_DATA_ACCEPTED -> "passed"
            result == RecordResult.EMERGENCY_DEFAULT_WON -> "default_won"
            result == RecordResult.EMERGENCY_DATA_REFUSED -> "both_failed"
            verdict == EmergencyData.Verdict.COUNTED_FAIL -> "failed"
            else -> "not_counted"
        }
        store.update { it.copy(emergencySessions = EmergencyData.add(it.emergencySessions, EmergencySession(sim.subId, sim.identity.mccmnc, row?.rowKey, basis, EmergencyData.day(now), outcome, now))) }
        if (verdict == EmergencyData.Verdict.COUNTED_FAIL && row != null) {
            log(store, "slot ${sim.slot + 1}: the row was refused (${EmergencyData.COUNTED_CAUSES[attempt.failCause]}); Android's default cannot be requested while the row is selected, so nothing changes")
        }
        // Records only after the owner recorded that the first use passed.
        if (result == null || store.read().emergencyFirstUse?.passed != true) return "emergency data test: $outcome" + if (result != null) " (no record before the first use is recorded as passed)" else ""
        val cell = RecordCell.of(status.build) ?: return "emergency data test: $outcome; no record (no cell)"
        val carrier = RecordCarrier.of(explain, sim.identity.mccmnc.take(3), sim.identity.mccmnc.drop(3), sim.identity.spn?.trim(), sim.identity.gid1) ?: return "emergency data test: $outcome; no record (no SIM key)"
        val record = MeasuredRecord.build(
            RecordInput(
                cell, carrier, RecordTarget.EmergencyData(row?.rowKey), RecordValue.None, result, "emergency_data", "eims_request",
                if (rat == "nr") "nr" else "lte", 1, now, now, EmergencyData.recordBasis(result, row?.apn, protocol, attempt.families, attempt.failCause),
                (if (status.servingInstalled) status.installed else status.buildPin)?.commit.orEmpty(),
                (if (status.servingInstalled) status.installed else status.buildPin)?.dataDigest.orEmpty(),
            ),
        )
        val line = OwnerDatasetStep.putRecord(store, client, record, emptyList(), null, emptySet())
        if (result == RecordResult.EMERGENCY_DEFAULT_WON) {
            val a = client.applyApnRecords()
            log(store, "apply_apn_records after the default won: ${if (a.ok) "job ${a.str("job_id")}" else a.line}")
        }
        OwnerDatasetStep.publish(context, store)
        return "emergency data test: $outcome; $line"
    }

    /** Whether an EIMS network exists now (one the app can see with CONNECTIVITY_USE_RESTRICTED_NETWORKS); null when unreadable. */
    @Suppress("DEPRECATION")
    private fun eimsNetwork(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS) == true }
    }.getOrNull()

    /** The request: at most 15 s, released at once after CONNECTED is read (well within 5 s), aborted on any call-state change. */
    @SuppressLint("MissingPermission")
    private suspend fun request(context: Context, subId: Int, row: ExplainRow?): EimsAttempt {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val tm = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(subId)
        if (cm == null || tm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return EimsAttempt(false, null, null, emptySet(), null, null, true)
        val executor = Executors.newSingleThreadExecutor()
        val done = CompletableDeferred<Unit>()
        val initialCallState = AtomicInteger(NO_STATE)
        val abortedRef = AtomicBoolean(false)
        val preciseRef = AtomicReference<PreciseDataConnectionState?>(null)
        val linkRef = AtomicReference<LinkProperties?>(null)
        val connectedRef = AtomicLong(0L)
        val telephony = object : TelephonyCallback(), TelephonyCallback.CallStateListener, TelephonyCallback.PreciseDataConnectionStateListener {
            override fun onCallStateChanged(state: Int) {
                // The first callback is the state at registration; any call at all, or any change after it, aborts.
                if (initialCallState.compareAndSet(NO_STATE, state)) {
                    if (state != TelephonyManager.CALL_STATE_IDLE) {
                        abortedRef.set(true)
                        done.complete(Unit)
                    }
                } else if (state != initialCallState.get()) {
                    abortedRef.set(true)
                    done.complete(Unit)
                }
            }

            override fun onPreciseDataConnectionStateChanged(s: PreciseDataConnectionState) {
                val setting = s.apnSetting ?: return
                if (setting.apnTypeBitmask and ApnSetting.TYPE_EMERGENCY == 0) return
                preciseRef.set(s)
                if (s.state == TelephonyManager.DATA_DISCONNECTED && s.lastCauseCode != 0 && connectedRef.get() == 0L) done.complete(Unit)
            }
        }
        val network = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                connectedRef.compareAndSet(0L, SystemClock.elapsedRealtime())
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                linkRef.set(lp)
                if (connectedRef.get() != 0L) done.complete(Unit)
            }

            override fun onUnavailable() {
                done.complete(Unit)
            }
        }
        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)
            .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build())
            .build()
        var released = 0L
        try {
            tm.registerTelephonyCallback(executor, telephony)
            cm.requestNetwork(request, network, EmergencyData.WAIT_MS.toInt())
            withTimeoutOrNull(EmergencyData.WAIT_MS + 1_000) { done.await() }
            // CONNECTED but no link properties yet: give them at most one second, then release regardless.
            if (connectedRef.get() != 0L && linkRef.get() == null) withTimeoutOrNull(1_000) { while (linkRef.get() == null) delay(100) }
        } finally {
            runCatching { cm.unregisterNetworkCallback(network) }
            released = SystemClock.elapsedRealtime()
            runCatching { tm.unregisterTelephonyCallback(telephony) }
            executor.shutdown()
        }
        val p = preciseRef.get()
        val link = linkRef.get()
        val connectedAt = connectedRef.get()
        val aborted = abortedRef.get()
        val families = link?.linkAddresses.orEmpty().mapNotNull { a ->
            when (a.address) {
                is Inet4Address -> "ipv4"
                is Inet6Address -> if (a.address.isLinkLocalAddress) null else "ipv6"
                else -> null
            }
        }.toSet()
        val apnName = p?.apnSetting?.apnName.orEmpty()
        val profileMatches = p?.apnSetting?.let { if (row != null) apnName.equals(row.apn, ignoreCase = true) else apnName.equals("sos", ignoreCase = true) }
        val pcscf = link?.let { lp -> runCatching { (LinkProperties::class.java.getMethod("getPcscfServers").invoke(lp) as? Collection<*>)?.isNotEmpty() }.getOrNull() }
        return EimsAttempt(
            connected = connectedAt != 0L,
            cellular = p?.transportType?.let { it == AccessNetworkConstants.TRANSPORT_TYPE_WWAN },
            profileMatches = profileMatches,
            families = families,
            failCause = p?.lastCauseCode?.takeIf { it != 0 },
            dataWorks = null,
            releasedInTime = connectedAt == 0L || released - connectedAt <= EmergencyData.RELEASE_MS,
            aborted = aborted,
            pcscf = pcscf,
        ).let { a ->
            // Ordinary data during the attempt: read after the release (the request does not touch the default network).
            a.copy(dataWorks = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }.getOrNull())
        }
    }

    /** The checks after every attempt (the ruling's list). */
    private suspend fun after(context: Context, phone: OwnerPhone, session: OwnerSession, sim: OwnerSim, imsBefore: String?): EimsAfter {
        val emergency = phone.emergencyApi()
        val registry = phone.registryText()
        val o = runCatching { session.observe() }.getOrNull()
        val imsAfter = phone.imsApi(sim.slot, sim.subId)?.let { "${it.state().registered}/${it.state().logTransport}/${it.state().services.sortedBy { s -> s.wire }}" }
        return EimsAfter(
            imsUnchanged = if (imsBefore == null || imsAfter == null) null else imsBefore == imsAfter,
            ecbm = emergency?.ecbm,
            emergencyCallState = emergency?.inEmergencyCall,
            callUp = QuietReader.inCall(context, registry),
            emergencyThrottle = EmergencyDump.emergencyThrottle(phone.telephonyText(), sim.slot),
            eimsGone = eimsNetwork(context)?.not(),
            serviceOk = o?.ps?.state?.let { it == "home" },
        )
    }
}
