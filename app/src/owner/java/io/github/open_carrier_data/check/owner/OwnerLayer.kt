package io.github.open_carrier_data.check.owner

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.github.open_carrier_data.check.core.Matcher
import io.github.open_carrier_data.check.owner.core.CcDump
import io.github.open_carrier_data.check.owner.core.LayerClient
import io.github.open_carrier_data.check.owner.core.LayerData
import io.github.open_carrier_data.check.owner.core.LayerInputs
import io.github.open_carrier_data.check.owner.core.LayerPlan
import io.github.open_carrier_data.check.owner.core.LayerPlanner
import io.github.open_carrier_data.check.owner.core.LayerProtocol
import io.github.open_carrier_data.check.owner.core.LayerPut
import io.github.open_carrier_data.check.owner.core.LayerResult
import io.github.open_carrier_data.check.owner.core.LayerSimInput
import io.github.open_carrier_data.check.owner.core.LayerSimView
import io.github.open_carrier_data.check.owner.core.LayerStatus
import io.github.open_carrier_data.check.owner.core.LayerWriter
import io.github.open_carrier_data.check.owner.core.OwnerFeature
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerRunner
import io.github.open_carrier_data.check.owner.core.OwnerSession
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.SafetyException
import io.github.open_carrier_data.check.owner.core.SimCheck
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.update
import io.github.open_carrier_data.check.owner.core.voiceLost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The runtime layer's provider in the ROM's CarrierSettings
 * (`content://com.android.carriersettings.ocdlayer`, round 29 S3), reached with
 * ContentResolver.call. On a ROM without it (no OcdCarrierSettingsLayer), or when this app
 * does not hold the writer permission, every call answers "absent" or "refused" and nothing
 * else happens: the app keeps its non-persistent keeps as before.
 */
class ProviderLayer(private val context: Context) : LayerClient {
    private val uri = Uri.parse(LayerProtocol.URI)

    /** One method call; null when the provider is absent or refuses this caller. */
    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle? {
        val client = runCatching { context.contentResolver.acquireUnstableContentProviderClient(uri) }.getOrNull() ?: return null
        return try {
            client.call(method, arg, extras)
        } catch (e: SecurityException) {
            Log.w(TAG, "the runtime layer refused this app: ${e.message?.take(120)}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "the runtime layer did not answer $method: ${e.javaClass.simpleName}")
            null
        } finally {
            client.close()
        }
    }

    private fun result(b: Bundle?): LayerResult {
        b ?: return LayerResult(false, "the runtime layer is not on this ROM build or did not answer")
        val refused = b.getString(LayerProtocol.REFUSED)?.let { text ->
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() } }.getOrNull()
        }.orEmpty()
        return LayerResult(
            ok = b.getBoolean(LayerProtocol.OK, false),
            reason = b.getString(LayerProtocol.REASON),
            reloaded = b.getBoolean(LayerProtocol.RELOADED, false),
            pending = b.getBoolean(LayerProtocol.PENDING, false),
            waitingFor = b.getString(LayerProtocol.WAITING_FOR),
            suffix = b.getString(LayerProtocol.SUFFIX),
            count = if (b.containsKey(LayerProtocol.REMOVED)) b.getInt(LayerProtocol.REMOVED) else b.getInt(LayerProtocol.RENEWED, 0),
            refused = refused,
        )
    }

    override fun status(): LayerStatus {
        val b = call(LayerProtocol.STATUS) ?: return LayerStatus.absent("not on this ROM build")
        if (!b.getBoolean(LayerProtocol.OK, false)) return LayerStatus.absent(b.getString(LayerProtocol.REASON) ?: "refused")
        val protocol = b.getInt(LayerProtocol.PROTOCOL, 0)
        if (protocol != LayerProtocol.VERSION) return LayerStatus.absent("protocol $protocol, this app speaks ${LayerProtocol.VERSION}")
        fun ints(key: String) = b.getString(key).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        return LayerStatus(
            available = true,
            stop = b.getString(LayerProtocol.STOP_FLAG),
            firstUse = ints(LayerProtocol.FIRST_USE),
            ownerOk = b.getBoolean(LayerProtocol.OWNER_OK, false),
            ownerReason = b.getString(LayerProtocol.OWNER_REASON).orEmpty(),
            file = b.getString(LayerProtocol.FILE).orEmpty(),
            seq = b.getLong(LayerProtocol.SEQ, -1),
            livenessUntilMs = b.getLong(LayerProtocol.LIVENESS_UNTIL_MS, 0),
            dataCommit = b.getString(LayerProtocol.DATA_COMMIT).orEmpty(),
            entryCount = b.getInt(LayerProtocol.ENTRY_COUNT, 0),
            pendingSubs = ints(LayerProtocol.PENDING_SUBS),
            waitingFor = b.getString(LayerProtocol.WAITING_FOR).orEmpty(),
            lastCheck = b.getString(LayerProtocol.LAST_CHECK).orEmpty(),
        )
    }

    override fun list(subId: Int): LayerSimView? {
        val b = call(LayerProtocol.LIST, subId.toString()) ?: return null
        if (!b.getBoolean(LayerProtocol.OK, false)) return null
        return LayerWriter.view(
            b.getString(LayerProtocol.SIM), b.getString(LayerProtocol.SERVED_SUFFIX).orEmpty(), b.getInt(LayerProtocol.OTHER_SIMS, 0),
            b.getString(LayerProtocol.FILE).orEmpty(), b.getString(LayerProtocol.STOP_FLAG),
        )
    }

    override fun put(subId: Int, profile: String, data: LayerData, entries: List<LayerPut>, livenessUntilMs: Long): LayerResult = result(
        call(
            LayerProtocol.PUT,
            extras = Bundle().apply {
                putInt(LayerProtocol.SUB_ID, subId)
                putString(LayerProtocol.PROFILE, profile)
                putString(LayerProtocol.DATA_COMMIT, data.commit)
                putString(LayerProtocol.DATA_DIGEST, data.digest)
                putString(LayerProtocol.DATA_DATE, data.date)
                putLong(LayerProtocol.LIVENESS_UNTIL_MS, livenessUntilMs)
                putString(LayerProtocol.ENTRIES, JsonArray(entries.map { it.json() }).toString())
            },
        ),
    )

    override fun remove(subId: Int, keys: List<String>?, reason: String): LayerResult = result(
        call(
            LayerProtocol.REMOVE,
            extras = Bundle().apply {
                putInt(LayerProtocol.SUB_ID, subId)
                keys?.let { putString(LayerProtocol.KEYS, JsonArray(it.map(::JsonPrimitive)).toString()) }
                putString(LayerProtocol.REASON, reason.take(200))
            },
        ),
    )

    override fun renew(subId: Int, keys: List<String>, untilMs: Long): LayerResult = result(
        call(
            LayerProtocol.RENEW,
            extras = Bundle().apply {
                putInt(LayerProtocol.SUB_ID, subId)
                putString(LayerProtocol.KEYS, JsonArray(keys.map(::JsonPrimitive)).toString())
                putLong(LayerProtocol.LEASE_UNTIL_MS, untilMs)
            },
        ),
    )

    override fun liveness(untilMs: Long): LayerResult =
        result(call(LayerProtocol.LIVENESS, extras = Bundle().apply { putLong(LayerProtocol.LIVENESS_UNTIL_MS, untilMs) }))

    override fun stop(reason: String): LayerResult = result(call(LayerProtocol.STOP, extras = Bundle().apply { putString(LayerProtocol.REASON, reason.take(240)) }))

    override fun clearAll(reason: String): LayerResult = result(call(LayerProtocol.CLEAR))

    override fun clearStop(): LayerResult = result(call(LayerProtocol.CLEAR_STOP))

    companion object {
        const val TAG = "OcdLayer"
    }
}

/**
 * The layer step: read the layer, plan with [LayerPlanner], carry the plan out with
 * [LayerWriter] and its dump proofs. Run by the quiet job after its light check (with the
 * silent window open or not) and on its own after a measured call settles a voice entry or the
 * owner's Undo. Never throws but for a cancelled worker; returns one line for the log.
 */
object OwnerLayerStep {
    fun client(context: Context): LayerClient = ProviderLayer(context)

    suspend fun run(context: Context, store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>, checks: List<SimCheck>?, quietOpen: Boolean): String {
        val layer = client(context)
        val status = layer.status()
        store.update { it.copy(layerStatus = status) }
        if (!status.available) return "no runtime layer (${status.why})"
        val device = OwnerWorker.device(context)
        val data = OwnerData(context, store)
        val bundle = data.bundle()
        val runner = OwnerRunner(phone, store, device)
        val views = sims.associate { it.key to layer.list(it.subId) }
        val now = System.currentTimeMillis()
        store.update { s -> s.copy(layer = LayerPlanner.reconcile(s.layer, views.filterValues { it != null }.mapValues { it.value!! }, now)) }
        val inputs = sims.mapNotNull { sim ->
            try {
                val session = OwnerSession(phone, store, sim, device.model)
                val check = checks?.firstOrNull { it.sim.slot == sim.slot } ?: runner.check(sim, VideoStatic.reasons(context), data.apnRows(sim))
                val o = session.observe()
                val dump: CcDump = session.dump()
                LayerSimInput(
                    sim = sim,
                    view = views[sim.key],
                    belowOverrides = dump.effectiveBelowOverrides(sim.slot),
                    switches = OwnerFeature.entries.associateWith { o.switch(it) },
                    observed = check.observed,
                    healthy = check.healthy,
                    voiceLost = o.voiceLost(),
                    refusal = session.safety().refusal,
                    answers = OwnerRunner.answers(bundle, sim, device),
                    profileId = Matcher.resolve(bundle, sim.identity).mostSpecific?.profileId,
                )
            } catch (e: SafetyException) {
                store.update { it.logged(now, "layer", "slot ${sim.slot + 1}: the layer step could not read the phone (${e.reason})") }
                null
            }
        }
        val state = store.read()
        val plan: LayerPlan = LayerPlanner.plan(LayerInputs(inputs, state, status, OwnerBasis.current(context, state).rom, now, quietOpen))
        if (plan.actions.isEmpty() && plan.stop == null && plan.livenessUntilMs == null) return "layer: nothing to do"
        val notes = LayerWriter(phone, store, layer, device.model).run(plan, sims, LayerData.of(state.pin))
        store.update { it.copy(layerStatus = layer.status()) }
        return "layer: " + notes.ifEmpty { listOf("liveness renewed") }.joinToString("; ")
    }

    /** Undo one entry ([slot] and [key]), one SIM's entries ([key] null), or every entry ([slot] null). Works while roaming. */
    suspend fun undo(context: Context, store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>, slot: Int?, key: String?): String {
        val layer = client(context)
        val status = layer.status()
        store.update { it.copy(layerStatus = status) }
        if (!status.available) return "no runtime layer (${status.why})"
        if (slot == null && status.entryCount == 0 && store.read().layer.isEmpty()) return "nothing kept after restart"
        val line = LayerWriter(phone, store, layer, OwnerWorker.device(context).model).undo(sims, slot, key)
        store.update { it.copy(layerStatus = layer.status()) }
        return line
    }

    /** S15 check 2's probe: SMS over IMS at the build's own value on the SIM in [slot], which changes nothing but survives a restart. */
    suspend fun probe(context: Context, store: StateStore, phone: OwnerPhone, sims: List<OwnerSim>, slot: Int): String {
        val layer = client(context)
        val status = layer.status()
        store.update { it.copy(layerStatus = status) }
        if (!status.available) return "no runtime layer (${status.why})"
        val sim = sims.firstOrNull { it.slot == slot } ?: return "no SIM in slot ${slot + 1}"
        val device = OwnerWorker.device(context)
        val bundle = OwnerData(context, store).bundle()
        val dump = OwnerSession(phone, store, sim, device.model).dump()
        val put = LayerPlanner.probe(sim, dump.effectiveBelowOverrides(sim.slot), OwnerRunner.answers(bundle, sim, device), Matcher.resolve(bundle, sim.identity).mostSpecific?.profileId, System.currentTimeMillis())
            ?: return "SMS over IMS cannot be read on slot ${slot + 1}"
        val notes = LayerWriter(phone, store, layer, device.model).run(LayerPlan(listOf(put)), sims, LayerData.of(store.read().pin))
        store.update { it.copy(layerStatus = layer.status()) }
        return "first-use probe: ${notes.joinToString()}"
    }

    /** The owner cleared the app's stop flag: CarrierSettings's flag is cleared with it (S9: "until the owner clears the flag"). */
    fun clearStop(context: Context, store: StateStore) {
        val layer = client(context)
        if (!layer.status().available) return
        val r = layer.clearStop()
        store.update { it.copy(layerStatus = layer.status()).logged(System.currentTimeMillis(), "layer", if (r.ok) "the runtime layer's stop flag was cleared by the owner" else "the runtime layer's stop flag could not be cleared: ${r.reason}") }
    }

    /** Whether a SIM's list says so; for the screen. */
    fun views(context: Context, sims: List<OwnerSim>): Map<SimKey, LayerSimView>? {
        val layer = client(context)
        if (!layer.status().available) return null
        return sims.mapNotNull { sim -> layer.list(sim.subId)?.let { sim.key to it } }.toMap()
    }
}
