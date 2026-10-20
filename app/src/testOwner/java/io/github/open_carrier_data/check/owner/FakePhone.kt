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
import io.github.open_carrier_data.check.owner.core.RegistrationEvent
import io.github.open_carrier_data.check.owner.core.RegistrationWatch
import io.github.open_carrier_data.check.owner.core.Route
import io.github.open_carrier_data.check.owner.core.ServerStart
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
 * switchable call, an IMS that registers what the effective switches allow
 * (over Wi-Fi while Wi-Fi calling is on and Wi-Fi is up, as QNS prefers it;
 * over LTE once carrier_wfc_ims_available_bool is false), an APN table with
 * the provider's merge rule, and a log of every write.
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

    /** IMS rides on Wi-Fi while Wi-Fi is up and Wi-Fi calling is on (the S20 at home): the IMS data connection is on WLAN. */
    var imsOverWifi = false

    /** IMS adds video to its registration when video calling is on (PHH IMS does not). */
    var imsVideo = false

    /** IMS keeps its Wi-Fi registration when Wi-Fi calling goes off (a stack that does not move). */
    var imsStaysOnWifi = false

    /** VoLTE registers over LTE when switched on; false: the network or the stack refuses it. */
    var volteRegisters = true

    /** LTE offers voice over PS (the registry's VoPS); false makes LTE no voice path. */
    var lteVops = true

    /** The system server's start on the elapsed clock (its property); null: unreadable. */
    var serverStartElapsed: Long? = 0L
    var zygotePid = 1234

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
        if (!lteVops) text = text.replace("mVopsSupport = 2", "mVopsSupport = 3")
        if (ims().onWifi) {
            text = text.replace("state: HANDOVERINPROGRESS, transport: WLAN", "state: CONNECTED, transport: WLAN")
                .replace("state: CONNECTED, transport: WWAN, id: 2", "state: DISCONNECTED, transport: WWAN, id: 2")
        }
        return text
    }

    private data class FakeIms(val registered: Boolean, val voice: Boolean, val video: Boolean, val sms: Boolean, val onWifi: Boolean)

    private fun ims(): FakeIms {
        val volte = effective("carrier_volte_available_bool") == "true"
        val sms = effective("imssms.sms_over_ims_supported_bool") == "true"
        val video = effective("carrier_vt_available_bool") == "true"
        val wfc = effective("carrier_wfc_ims_available_bool") == "true"
        // Over Wi-Fi, IMS registers for voice when Wi-Fi calling is on, whatever VoLTE says; with it off, QNS moves IMS to LTE.
        val onWifi = imsOverWifi && wifi == true && (wfc || imsStaysOnWifi)
        val voice = if (onWifi) true else volte && volteRegisters
        val registered = imsWorks && (voice || sms)
        return FakeIms(registered, registered && voice, registered && voice && video && imsVideo, registered && sms, onWifi && registered)
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

    /** No route reads the device log any more; this counts any attempt. */
    var logReads = 0
    var iwlanDump: String? = null
    var qnsDump: String? = null
    override suspend fun iwlanServiceText(): String? = iwlanDump
    override suspend fun qnsText(): String? = qnsDump

    override suspend fun serverStart(): ServerStart = ServerStart(serverStartElapsed, 1, "boot-1", zygotePid, uptime)

    // --- IMS registrations, as the registration callback reports them ---
    private val registrations = mutableListOf<RegistrationEvent>()
    private var lastIms: Pair<Boolean, Boolean>? = null

    /** A registration that changes transport, or one that comes or goes, is a fresh REGISTER (or a sign-out). */
    private fun imsMoved() {
        val ims = ims()
        val signature = ims.registered to ims.onWifi
        if (lastIms != null && lastIms != signature) {
            registrations += RegistrationEvent(now, ims.registered, if (!ims.registered) null else if (ims.onWifi) ImsApi.TRANSPORT_WLAN else ImsApi.TRANSPORT_WWAN)
        }
        lastIms = signature
    }

    var watchable = true
    override suspend fun watchRegistrations(subId: Int): RegistrationWatch? {
        if (!watchable) return null
        lastIms = ims().let { it.registered to it.onWifi }
        val from = registrations.size
        return object : RegistrationWatch {
            override fun events() = registrations.drop(from)
            override fun close() {}
        }
    }

    override suspend fun ccSet(slot: Int, subId: Int, key: String, value: Boolean): CcResult {
        KeyGuard.check(key)
        writes += "set $key=$value"
        recordedAtWrite += store.read().overrides.any { it.key == key && it.value == value }
        if (!sim) return CcResult(false, "no_valid_subscription")
        overrides = (overrides ?: LinkedHashMap()).also { it[key] = value.toString() }
        imsMoved()
        return CcResult(true, null)
    }

    override suspend fun ccClear(slot: Int, subId: Int): CcResult {
        writes += "clear"
        if (!sim) return CcResult(false, "no_valid_subscription")
        if (honourClear) {
            overrides = LinkedHashMap()
            persistent = LinkedHashMap()
        }
        imsMoved()
        return CcResult(true, null)
    }

    // --- APN table ---
    /** The stock row: numeric 26202, no carrier ID (-1), as the S20's apns-conf.xml loads it. */
    val apns = mutableListOf(PhoneApnRow(1, "LIDL Internet", "web.vodafone.de", listOf("default", "supl"), "IPV4V6", null, numeric = "26202", carrierId = null))
    var preferred: Long? = 1
    private var nextId = 100L

    /** Mobile data validates only with these APN keys preferred. */
    var workingApnKeys = setOf(apnKey("web.vodafone.de", listOf("default", "supl"), "IPV4V6"))

    /** Values of the last insert, as the provider got them. */
    var lastInsert: Map<String, String>? = null

    /** The provider answers an insert with no row (a refusal the app did not foresee). */
    var insertAnswersNull = false

    override suspend fun apnQuery(numeric: String) = apns.filter { (it.numeric ?: "") == numeric }
    override suspend fun apnQueryMarked() = apns.filter { it.name?.endsWith(" (OCD Check test)") == true }
    override suspend fun apnRowById(id: Long) = apns.firstOrNull { it.id == id }

    /** The rows an insert would merge into, as the provider sees them: numeric, APN, protocol and carrier ID equal (the defaults filled in). */
    private fun conflicting(values: Map<String, String>) = apns.filter {
        (it.numeric ?: "") == (values["numeric"] ?: "") && it.apn == values["apn"] &&
            (it.protocol ?: "IP") == (values["protocol"] ?: "IP") && (it.carrierId ?: -1L) == (values["carrier_id"]?.toLongOrNull() ?: -1L)
    }

    /** The conflict lookup fails (null) or misses every conflict (an older provider with other UNIQUE columns). */
    var conflictsUnreadable = false
    var conflictsBlind = false

    override suspend fun apnConflicts(values: Map<String, String>): List<Long>? = when {
        conflictsUnreadable -> null
        conflictsBlind -> emptyList()
        else -> conflicting(values).map { it.id }
    }

    override suspend fun apnInsert(values: Map<String, String>): Long? {
        writes += "apn insert ${values["apn"]}"
        lastInsert = values
        if (insertAnswersNull) return null
        val old = conflicting(values).firstOrNull()
        if (old != null) {
            // TelephonyProvider: the insert is merged into the existing row (name, types) and answers no row.
            apns[apns.indexOf(old)] = old.copy(name = values["name"], types = (old.types + values["type"].orEmpty().split(',')).distinct())
            return null
        }
        val id = nextId++
        apns += PhoneApnRow(id, values["name"], values["apn"].orEmpty(), values["type"].orEmpty().split(','), values["protocol"], values["mvno_type"], values["numeric"], values["carrier_id"]?.toLongOrNull())
        return id
    }

    override suspend fun apnDelete(id: Long): Boolean {
        writes += "apn delete $id"
        return apns.removeAll { it.id == id }
    }

    override suspend fun apnTableHash(): String = apns.sortedBy { it.id }.joinToString("\n").hashCode().toString()

    override suspend fun preferredApn(subId: Int) = PreferredApn(true, preferred)
    override suspend fun setPreferredApn(subId: Int, id: Long?): Boolean {
        writes += "prefer $id"
        preferred = id
        return true
    }

    override suspend fun mobileData(subId: Int): MobileData =
        MobileData(true, apns.firstOrNull { it.id == preferred }?.key in workingApnKeys)

    override suspend fun wifiConnected(): Boolean? = wifi

    override fun nowMs(): Long = now
    override fun uptimeMs(): Long = uptime
    override suspend fun sleep(ms: Long) {
        now += ms
        uptime += ms
        onSleep(this)
    }
}
