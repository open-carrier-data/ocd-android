package io.github.open_carrier_data.check.owner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.R
import io.github.open_carrier_data.check.owner.core.MainNet
import io.github.open_carrier_data.check.owner.core.QuietPlan
import io.github.open_carrier_data.check.owner.core.Relay
import io.github.open_carrier_data.check.owner.core.SimKey
import io.github.open_carrier_data.check.owner.core.ImsStack
import io.github.open_carrier_data.check.owner.core.StateStore
import io.github.open_carrier_data.check.owner.core.WeekCell
import io.github.open_carrier_data.check.owner.core.WeekDevice
import io.github.open_carrier_data.check.owner.core.WeekQuestion
import io.github.open_carrier_data.check.owner.core.WeekReports
import io.github.open_carrier_data.check.owner.core.WeekStep
import io.github.open_carrier_data.check.owner.core.WeekSwitch
import io.github.open_carrier_data.check.owner.core.sameCard
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

/** Round 48, item 2 on the phone: the build switch, the one-time question and the weekly step ([WeekStep] decides). */
object OwnerWeekReport {
    fun switch(context: Context): WeekSwitch = WeekSwitch.of(runCatching { context.getString(R.string.ocd_week_report) }.getOrNull())

    suspend fun step(context: Context, store: StateStore) {
        val line = WeekStep(store, AndroidWeekDevice(context, store)) { Random.nextLong(it) }.run(System.currentTimeMillis()) ?: return
        store.update { it.logged(System.currentTimeMillis(), WeekReports.LOG_KIND, line) }
    }

    /** One notification on the first boot with a SIM. Blocked notifications ask nothing, and the answer stays no. */
    fun askOnce(context: Context) {
        val store = FileStateStore(context)
        val sim = !OwnerData.simViews(context).isNullOrEmpty()
        if (!WeekQuestion.due(store.read().week, OwnerAccess.privilegedGranted(context), switch(context), sim)) return
        val asked = OwnerNotifications.question(context)
        store.updateLocked {
            it.copy(week = it.week.copy(asked = true)).logged(
                System.currentTimeMillis(), WeekReports.LOG_KIND,
                if (asked) "the one-time question went out" else "notifications are blocked: nothing asked, the answer stays no",
            )
        }
    }

    fun mayAnswer(context: Context): Boolean = WeekQuestion.open(OwnerAccess.privilegedGranted(context), switch(context))

    /** The screen is open, from a notification, the entry's switch or a ROM's setup: the notification is skipped from now on. */
    fun shown(context: Context) {
        OwnerNotifications.questionShown(context)
        FileStateStore(context).updateLocked { if (it.week.asked) it else it.copy(week = it.week.copy(asked = true)) }
    }

    fun answer(context: Context, yes: Boolean) {
        val now = System.currentTimeMillis()
        FileStateStore(context).updateLocked {
            it.copy(week = WeekReports.answered(it.week, yes, now)).logged(now, WeekReports.LOG_KIND, if (yes) "the person said yes to the weekly summary" else "the person said no to the weekly summary")
        }
    }
}

private class AndroidWeekDevice(private val context: Context, private val store: StateStore) : WeekDevice {
    override val privileged: Boolean get() = OwnerAccess.privilegedGranted(context)
    override val switch: WeekSwitch get() = OwnerWeekReport.switch(context)
    override val relayUrl: String get() = Relay.URL
    override val rom: String get() = OwnerBasis.current(context, store.read()).rom
    override val schema: JsonObject
        get() = context.assets.open(WeekReports.SCHEMA_ASSET).use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }

    override fun net() = MainNet(QuietReader.unmetered(context), QuietReader.roaming(context), QuietReader.dataSaver(context))

    override fun inCall(): Boolean? = QuietReader.inCall(context, null)

    override fun cell(): WeekCell {
        val device = OwnerWorker.device(context)
        return WeekCell(device.model, device.romFamily.wire, device.androidMajor, ImsStack.of(VideoStatic.imsPackages(context)), WeekReports.majorMinor(BuildConfig.VERSION_NAME))
    }

    override fun onList(model: String): Boolean = runCatching {
        GZIPInputStream(context.assets.open(WeekReports.MODELS_ASSET)).bufferedReader().useLines { lines -> lines.any { it == model } }
    }.getOrDefault(false)

    override suspend fun ocdRows(sim: SimKey): Set<String> {
        val live = OwnerData.sims(context).firstOrNull { it.key.sameCard(sim) } ?: return emptySet()
        return QuietPlan.apnCandidates(OwnerData(context, store).apnRows(live), live).map { it.key }.toSet()
    }

    override suspend fun relayOpen(): Boolean = OwnerData(context, store).relayOpen()

    /**
     * One POST on the very network the limits were read on, so a switch to mobile data cannot carry it. HTTPS only, no
     * redirect, no cookie, a constant User-Agent, nothing cached, the answer unread beyond its code. No retry.
     */
    override suspend fun post(body: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork ?: return@runCatching false
            val caps = manager.getNetworkCapabilities(network) ?: return@runCatching false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) return@runCatching false
            val connection = network.openConnection(URL(Relay.URL)) as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("User-Agent", Relay.USER_AGENT)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body) }
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
