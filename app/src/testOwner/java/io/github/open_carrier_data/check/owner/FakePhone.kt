package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.core.SimIdentity
import io.github.open_carrier_data.check.owner.core.CcDump
import io.github.open_carrier_data.check.owner.core.CcResult
import io.github.open_carrier_data.check.owner.core.EmergencyApi
import io.github.open_carrier_data.check.owner.core.ImsApi
import io.github.open_carrier_data.check.owner.core.KeyGuard
import io.github.open_carrier_data.check.owner.core.MemoryStore
import io.github.open_carrier_data.check.owner.core.MobileData
import io.github.open_carrier_data.check.owner.core.OwnerPhone
import io.github.open_carrier_data.check.owner.core.OwnerSim
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PhoneApnRow
import io.github.open_carrier_data.check.owner.core.PreferredApn
import io.github.open_carrier_data.check.owner.core.Route
import io.github.open_carrier_data.check.owner.core.apnKey

/** The lab's fixtures (tools/ocd_lab/fixtures in OCD's private repository), from the S20 with the LIDL SIM. */
object OwnerFixtures {
    fun text(name: String): String = requireNotNull(javaClass.getResourceAsStream("/owner/$name")) { name }.use { it.readBytes().decodeToString() }

    val CC = text("carrier_config_s20_no_sim.txt")
    val REGISTRY = text("registry_s20_lidl_lte.txt")
    val PHONE = text("phone_lineage_two_phones.txt")
    val IWLAN = text("iwlan_lidl_backoff_then_up.log")

    /** The dump with one phone's layer replaced: null prints null, an empty map an empty bundle (lab `with_override`). */
    fun withOverride(text: String, layer: String, entries: Map<String, String>?, phone: Int = 0): String {
        val header = "Phone Id = $phone\n"
        val at = text.indexOf(header)
        val head = text.substring(0, at + header.length)
        val rest = text.substring(at + header.length)
        val marker = "    $layer : null \n"
        val replacement = if (entries == null) marker else "    $layer : \n" + entries.entries.joinToString("") { "            ${it.key} = ${it.value}\n" }
        return head + rest.replaceFirst(marker, replacement)
    }

    val LIDL = SimIdentity("26202", "LIDL Connect", null, setOf(2397L))
    val SIM = OwnerSim(slot = 0, subId = 3, identity = LIDL, name = "LIDL Connect", carrierId = 2397)
}

/**
 * Enough of the phone for the owner session: a CarrierConfig dump that
 * follows set and clear the way CarrierConfigLoader does, a registry with a
 * switchable call, an IMS that registers what the effective switches allow,
 * an APN table, and a log of every write.
 */
open class FakePhone(
    /** First-use check 3 done, unless a test says otherwise. */
    val store: MemoryStore = MemoryStore(OwnerState(rebootCleared = true)),
    var sim: Boolean = true,
    var honourClear: Boolean = true,
) : OwnerPhone {
    override val route get() = if (privileged) Route.PRIVILEGED else Route.SHIZUKU_ROOT
    var overrides: MutableMap<String, String>? = null
    var persistent: MutableMap<String, String>? = null

    /** The carrier config app's own values for phone 0 (mConfigFromDefaultApp), below the override layers. */
    var defaultApp: Map<String, String>? = null
    var call = false
    var roaming = false
    var ecbm = false
    var now = 1_792_408_702_000L
    var uptime = 3_600_000L
    var wifi: Boolean? = false
    val writes = mutableListOf<String>()

    /** Keys recorded in the state when their write ran (register before write). */
    val recordedAtWrite = mutableListOf<Boolean>()

    /** IMS registers mmtel when VoLTE is on, smsip when SMS over IMS is on; [imsWorks] false keeps it unregistered. */
    var imsWorks = true

    /** IMS rides on Wi-Fi while Wi-Fi is up (the S20 with Wi-Fi calling on): the IMS data connection is on WLAN. */
    var imsOverWifi = false

    /** IMS adds video to its registration when video calling is on (PHH IMS does not). */
    var imsVideo = false

    /** The phone refuses Wi-Fi changes. */
    var wifiSwitchRefused = false

    /** Called during a sleep, to change the phone mid-test. */
    var onSleep: (FakePhone) -> Unit = {}

    fun effective(key: String): String? {
        val base = CcDump.parse(OwnerFixtures.CC).effective(0)
        return overrides?.get(key) ?: persistent?.get(key) ?: defaultApp?.get(key) ?: base[key]
    }

    override suspend fun carrierConfigText(): String =
        OwnerFixtures.withOverride(
            OwnerFixtures.withOverride(OwnerFixtures.withOverride(OwnerFixtures.CC, "mConfigFromDefaultApp", defaultApp), "mOverrideConfigs", overrides),
            "mPersistentOverrideConfigs", persistent,
        )

    override suspend fun registryText(): String {
        var text = OwnerFixtures.REGISTRY
        if (call) text = text.replaceFirst("mCallState=0", "mCallState=2").replaceFirst("Foreground call state: 0", "Foreground call state: 1").replaceFirst("mCallNetworkType=0", "mCallNetworkType=13")
        if (roaming) text = text.replace("isRoaming=false", "isRoaming=true")
        if (imsOverWifi && wifi == true) {
            text = text.replace("state: HANDOVERINPROGRESS, transport: WLAN", "state: CONNECTED, transport: WLAN")
                .replace("state: CONNECTED, transport: WWAN, id: 2", "state: DISCONNECTED, transport: WWAN, id: 2")
        }
        return text
    }

    private class FakeIms(val registered: Boolean, val voice: Boolean, val video: Boolean, val sms: Boolean, val onWifi: Boolean)

    private fun ims(): FakeIms {
        val volte = effective("carrier_volte_available_bool") == "true"
        val sms = effective("imssms.sms_over_ims_supported_bool") == "true"
        val video = effective("carrier_vt_available_bool") == "true"
        val wfc = effective("carrier_wfc_ims_available_bool") == "true"
        // Over Wi-Fi, IMS registers for voice when Wi-Fi calling is on, whatever VoLTE says.
        val onWifi = imsOverWifi && wifi == true && wfc
        val voice = if (onWifi) true else volte
        val registered = imsWorks && (voice || sms)
        return FakeIms(registered, registered && voice, registered && voice && video && imsVideo, registered && sms, onWifi)
    }

    override suspend fun phoneText(): String? {
        if (privileged) return null
        val ims = ims()
        val second = OwnerFixtures.PHONE.indexOf("GsmCdmaPhone extends:", OwnerFixtures.PHONE.indexOf("mPhoneId=0"))
        var section = OwnerFixtures.PHONE.substring(0, second)
        section = section.replace("mImsMmTelRegistrationState = 0", "mImsMmTelRegistrationState = ${if (ims.registered) 2 else 0}")
            .replace("Voice: false Video: false UT: false SMS: false", "Voice: ${ims.voice} Video: ${ims.video} UT: false SMS: ${ims.sms}")
        if (ecbm) section = section.replace("isInEcm()=false", "isInEcm()=true")
        return section + OwnerFixtures.PHONE.substring(second)
    }

    override suspend fun ecmProperty(): String? = if (privileged) null else ""

    /**
     * The privileged route: no phone dump; the emergency state and IMS come
     * from the system APIs. [emergencyApiAnswers] false makes every emergency
     * call fail (null), as when a binder is missing.
     */
    var privileged = false
    var emergencyApiAnswers = true
    var emergencySms = false

    override suspend fun emergencyApi(): EmergencyApi? = when {
        !privileged -> null
        !emergencyApiAnswers -> EmergencyApi(null, null, null)
        else -> EmergencyApi(ecbm = ecbm, inEmergencyCall = false, emergencySmsMode = emergencySms)
    }

    override suspend fun imsApi(slot: Int, subId: Int): ImsApi? {
        if (!privileged) return null
        val ims = ims()
        return ImsApi(
            registrationState = if (ims.registered) ImsApi.REGISTERED else ImsApi.NOT_REGISTERED,
            transportType = if (!ims.registered) -1 else if (ims.onWifi) ImsApi.TRANSPORT_WLAN else ImsApi.TRANSPORT_WWAN,
            voice = ims.voice,
            video = ims.video,
            sms = ims.sms,
            volteByUser = true,
            vtByUser = true,
            wfcByUser = true,
        )
    }
    override suspend fun iwlanLogText(slot: Int): String = ""

    override suspend fun ccSet(slot: Int, subId: Int, key: String, value: Boolean): CcResult {
        KeyGuard.check(key)
        writes += "set $key=$value"
        recordedAtWrite += store.read().overrides.any { it.key == key && it.value == value }
        if (!sim) return CcResult(false, "no_valid_subscription")
        overrides = (overrides ?: LinkedHashMap()).also { it[key] = value.toString() }
        return CcResult(true, null)
    }

    override suspend fun ccClear(slot: Int, subId: Int): CcResult {
        writes += "clear"
        if (!sim) return CcResult(false, "no_valid_subscription")
        if (honourClear) {
            overrides = LinkedHashMap()
            persistent = LinkedHashMap()
        }
        return CcResult(true, null)
    }

    // --- APN table ---
    val apns = mutableListOf(PhoneApnRow(1, "LIDL Internet", "web.vodafone.de", listOf("default", "supl"), "IPV4V6", null))
    var preferred: Long? = 1
    private var nextId = 100L

    /** Mobile data validates only with these APN keys preferred. */
    var workingApnKeys = setOf(apnKey("web.vodafone.de", listOf("default", "supl"), "IPV4V6"))

    override suspend fun apnQuery(numeric: String) = apns.toList()
    override suspend fun apnQueryMarked() = apns.filter { it.name?.endsWith(" (OCD Check test)") == true }
    override suspend fun apnInsert(values: Map<String, String>): Boolean {
        writes += "apn insert ${values["apn"]}"
        apns += PhoneApnRow(nextId++, values["name"], values["apn"].orEmpty(), values["type"].orEmpty().split(','), values["protocol"], values["mvno_type"])
        return true
    }

    override suspend fun apnDelete(id: Long): Boolean {
        writes += "apn delete $id"
        return apns.removeAll { it.id == id }
    }

    override suspend fun preferredApn(subId: Int) = PreferredApn(true, preferred)
    override suspend fun setPreferredApn(subId: Int, id: Long?): Boolean {
        writes += "prefer $id"
        preferred = id
        return true
    }

    override suspend fun mobileData(subId: Int): MobileData =
        MobileData(true, apns.firstOrNull { it.id == preferred }?.key in workingApnKeys)

    override suspend fun wifiConnected(): Boolean? = wifi

    override suspend fun setWifiEnabled(on: Boolean): Boolean {
        writes += "wifi ${if (on) "on" else "off"}"
        if (wifiSwitchRefused) return false
        wifi = on
        return true
    }
    override fun nowMs(): Long = now
    override fun uptimeMs(): Long = uptime
    override suspend fun sleep(ms: Long) {
        now += ms
        uptime += ms
        onSleep(this)
    }
}
