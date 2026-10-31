/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

/**
 * CarrierSettings's own copies of the rules it enforces whatever the app or the data say
 * (spec 5.1 to 5.4). The dataset's marking is unioned in, never used to narrow these.
 */
object Rules {
    /** 5.1: case-insensitive. */
    val GROUP_A_PATTERN = Regex("emergency|e911|ecbm|(^|[._])ecm([._]|$)|wps|tty|rtt|sos|^imsemergency\\.", RegexOption.IGNORE_CASE)

    val GROUP_A_NAMES = setOf(
        "call_forwarding_blocks_while_roaming_string_array",
        "ims_reasoninfo_mapping_string_array",
        // Owner decision of 2026-10-25 on spec 11.4: the eight extra emergency-path keys join group A.
        "use_only_dialed_sim_ecc_list_bool",
        "rilext.carrier_ignore_clir_when_ecc",
        "rilext.carrier_ignore_sms_for_eims",
        "rilext.exiting_ecc_mode_after_epdn_disconnected_enable",
        "rilext.ims_prefer_apm_normal_ecc_enable",
        "gps.es_extension_sec",
        "dial_string_replace_string_array",
        "international_roaming_dial_string_replace_string_array",
    )

    /** 5.2 */
    val GROUP_B = setOf("carrier_metered_apn_types_strings", "carrier_metered_roaming_apn_types_strings")
    const val MUST_CONTAIN = "default"

    /** 5.3: the MMS-ended networks (round 29's LayerKeys.MMS_ENDED, kept). */
    val X6_NETWORKS = setOf("262-01", "262-02", "262-03", "262-07", "262-23", "228-01", "228-02", "228-03", "270-01")
    const val SMS_TO_MMS = "smsToMmsTextThreshold"
    const val SMS_TO_MMS_LENGTH = "smsToMmsTextLengthThreshold"
    const val GROUP_MMS = "enableGroupMms"
    const val MULTIPART_SMS = "enableMultipartSMS"

    /** 5.4: the X5 keys and their off value. */
    val X5_OFF: Map<String, Boolean> = linkedMapOf(
        "carrier_volte_available_bool" to false,
        "carrier_wfc_ims_available_bool" to false,
        "imssms.sms_over_ims_supported_bool" to false,
        "carrier_volte_provisioning_required_bool" to true,
        "carrier_volte_provisioned_bool" to false,
    )

    /** X4: a Wi-Fi-only starting mode (ImsMmTelManager.WIFI_MODE_WIFI_ONLY), at home or roaming. */
    val X4_KEYS = setOf("carrier_default_wfc_ims_mode_int", "carrier_default_wfc_ims_roaming_mode_int")
    const val WIFI_ONLY = 0

    /**
     * Round 31 SM7: without a tester, an X3, X4 or X5 value serves only where it equals the
     * Google-lane value. X3 is round 29's lock-out set, which covers the ruling's words (hide or
     * lock the VoLTE or Wi-Fi calling switch, remove the option to turn IMS off, block editing or
     * adding APNs) and a little more.
     */
    fun sm7Kind(key: String, value: Any?): String? = when {
        key in X5_OFF -> if (value == X5_OFF.getValue(key)) "X5" else null
        key in X4_KEYS -> if (value == WIFI_ONLY) "X4" else null
        key == "carrier_allow_turnoff_ims_bool" || key == "allow_adding_apns_bool" -> if (value == false) "X3" else null
        key.startsWith("editable_") -> if (value == false) "X3" else null
        key.startsWith("hide_") -> if (value == true) "X3" else null
        key == "read_only_apn_types_string_array" || key == "read_only_apn_fields_string_array" -> if ((value as? List<*>)?.isNotEmpty() == true) "X3" else null
        else -> null
    }

    /** SM8: the IMS stacks where one calls-out pass confirmed the tester's readings. Elsewhere SM7 applies. */
    val CALIBRATED_STACKS = setOf("aosp_imsstack")

    /** These stay in group A whatever a read finds (round 31, emergencyValues). */
    val GROUP_A_NEVER_LEAVE = setOf(
        "emergency_number_prefix_string_array",
        "dial_string_replace_string_array",
        "international_roaming_dial_string_replace_string_array",
    )

    /** OCD serves no emergency number: no key that holds an emergency number list is ever served. */
    val EMERGENCY_NUMBER_LIST = Regex("(^|[._])(ecc_?list|emergency_?numbers?(_?list)?)(_string_array|_strings|_string|_int_array)?$", RegexOption.IGNORE_CASE)

    fun groupA(registry: Collection<String>, marked: Collection<String>, left: Collection<String> = emptySet()): Set<String> =
        (marked.toSet() + registry.filter { GROUP_A_PATTERN.containsMatchIn(it) } + GROUP_A_NAMES - left.filter { it !in GROUP_A_NEVER_LEAVE }.toSet()).toSortedSet()

    fun groupB(marked: Collection<String>): Set<String> = (GROUP_B + marked).toSortedSet()

    fun x6Networks(marked: Collection<String>): Set<String> = (X6_NETWORKS + marked).toSortedSet()
}

/**
 * This phone's cell (spec 4.4 and 9.1, round 31 item "keys"): ocd-build.json, checked against
 * the running build. [firstUsePassedOn] is the build's declaration that the device family's
 * first use of the emergency data test passed (round 31 clearData, SM10): the emergency gate is
 * narrow only with it.
 */
data class BuildCell(
    val codename: String,
    val model: String,
    val romFamily: String,
    val romLine: String,
    val stackId: String,
    val stackFork: String?,
    val firstUsePassedOn: String? = null,
) {
    /** A record serves only in its exact cell: device, ROM family and line, IMS stack with its fork. */
    fun matches(r: Record): Boolean =
        r.codename == codename && r.model.equals(model, ignoreCase = true) && r.romFamily == romFamily && r.romLine == romLine &&
            r.stackId == stackId && r.stackFork == stackFork

    companion object {
        private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

        /** ocd-build.json (schema ocd-build/1, or ocd-build/2 with the emergency data test's first use); null when it is unreadable. */
        fun parse(bytes: ByteArray): Pair<BuildCell, String?>? = runCatching {
            val raw = Obj.of(Json.parseBytes(bytes), "ocd-build.json")
            val v2 = raw.str("schema") == "ocd-build/2"
            if (!v2 && raw.str("schema") != "ocd-build/1") return null
            val o = raw.members(setOf("schema", "device", "rom", "ims_stack"), if (v2) setOf("emergency_data_test") else emptySet())
            val d = o.obj("device").members(setOf("codename", "model"))
            val r = o.obj("rom").members(setOf("family", "line"))
            val s = o.obj("ims_stack").members(setOf("id", "fork", "package"))
            val firstUse = o.objOrNull("emergency_data_test")?.members(setOf("first_use", "passed_on"), setOf("basis"))?.let { e ->
                if (e.str("first_use") == "passed" && DATE.matches(e.str("passed_on"))) e.str("passed_on") else null
            }
            BuildCell(d.str("codename"), d.str("model"), r.str("family"), r.str("line"), s.str("id"), s.strOrNull("fork"), firstUse) to s.strOrNull("package")
        }.getOrNull()
    }
}

/** A profile as the serving index holds it: fill entries resolved to the Google values they name (null: missing). */
data class ServedProfile(val profile: Profile, val fillValues: Map<String, Map<String, Any>?>)

/** One dataset as a load sees it, from its serving index (9.3) or straight from the dataset. */
class ServingData(
    val role: String,
    val commit: String,
    val datasetDigest: String,
    val types: Map<String, KeyType>,
    val forks: Map<String, String>,
    val groupA: Set<String>,
    val groupB: Set<String>,
    val x6Networks: Set<String>,
    val googleDefault: Map<String, Any>,
    val googleNoSim: Map<String, Any>,
    /** The dataset's records whose cell matches this phone. */
    val records: List<Record>,
    /** A network's profiles; null when that network file cannot be read (the load then fails over). */
    val network: (String) -> List<ServedProfile>?,
    /** The dataset's group A reads for this phone's ROM family and IMS stack (R31.7). */
    val groupAReads: List<GroupARead> = emptyList(),
) {
    val sha7: String get() = commit.take(7)
}

/** What [BundleBuilder] makes for one SIM. Values are typed; [types] covers every key of [values]. */
class Built(
    val values: LinkedHashMap<String, Any>,
    val types: Map<String, KeyType>,
    val sources: Map<String, String>,
    val version: String,
    val serving: String,
    val stack: List<Profile>,
    val fill: FillItem?,
    val fillSkipped: List<String>,
    val x5Held: Map<String, Pair<Any?, Any?>>,
    val recordsApplied: List<String>,
    val groupA: Set<String>,
    /** step 2 to 6 values of the X5 keys, before the hold (the "data value"). */
    val x5Data: Map<String, Any?>,
    /** SM7 applied to this bundle. */
    val sm7: Boolean = false,
    /** SM7 replaced these: key to (the data's value, the served value or null for Android's default). */
    val sm7Held: Map<String, Pair<Any?, Any?>> = emptyMap(),
)

class BuildInput(
    val sim: SimId,
    val data: ServingData,
    /** Local measured records; empty while the build's dataset serves (7.3 point 5). */
    val localRecords: List<Record>,
    val x5TestedHere: Set<String>,
    /** The X5 reference for a key (5.4): the value last served this subscription, else the build's result. */
    val x5Reference: (String) -> Any?,
    /** UTC date, yyyy-MM-dd, for record expiry. */
    val today: String,
    val cell: BuildCell?,
    /** The crash guard tripped on the build dataset itself: group A only (8.3; step 5 is gone). */
    val crashGuardBuild: Boolean = false,
    /** For the X5 reference from the build: steps 2 to 6 only, no hold and no groups. */
    val rawOnly: Boolean = false,
    /** Round 31 SM7: no dialled tests run on this phone. The X5 hold does not apply then; SM7 does. */
    val sm7: Boolean = false,
)

/** The bundle order of spec section 5, exactly `ruling.replace.rule`. Later steps win. */
object BundleBuilder {
    const val VERSION_KEY = Types.VERSION_KEY

    /** The records that count for this phone: one per record_key, the newer of local and published (6.4). */
    fun effectiveRecords(dataset: List<Record>, local: List<Record>): List<Record> {
        val byKey = LinkedHashMap<String, Record>()
        for (r in dataset) byKey[r.key] = r
        for (r in local) {
            val published = byKey[r.key]
            if (published == null || (published.lastMeasured ?: "") < (r.lastMeasured ?: "")) byKey[r.key] = r
        }
        return byKey.values.sortedWith { a, b -> compareBytes(a.key, b.key) }
    }

    fun expired(r: Record, today: String): Boolean = r.measured && r.validUntil != null && today > r.validUntil

    /**
     * A record that may be served on this phone at all: a measured record (round 31: no
     * `configured_device` record and no carrier-less record is served), in its exact cell, not
     * expired, its key in the registry and in the fork it needs.
     */
    fun servable(r: Record, input: BuildInput): Boolean {
        val cell = input.cell ?: return false
        if (!r.measured || r.carrier.scope == "all") return false
        if (!cell.matches(r) || expired(r, input.today)) return false
        if (r.target.type == "config") {
            val fork = input.data.forks[r.target.key]
            if (fork != null && fork != cell.stackFork) return false
            if (r.target.key !in input.data.types) return false
        }
        return true
    }

    /** The cell's records for the serving profile or the SIM key (round 30's step 6; step 5 is gone). */
    fun cellRecords(records: List<Record>, input: BuildInput, serving: Profile?): List<Record> = records.filter { r ->
        servable(r, input) && when (r.carrier.scope) {
            "profile" -> serving != null && r.carrier.profileId == serving.id
            "sim_key" -> (serving == null || serving.fillOnly) && r.carrier.mcc == input.sim.mcc && r.carrier.mnc == input.sim.mnc &&
                (if (r.carrier.gid1 != null) r.carrier.gid1.equals(input.sim.gid1, ignoreCase = true) else Matcher.eqIgnoreCase(r.carrier.spn, input.sim.spn))
            else -> false
        }
    }

    private class Acc(val data: ServingData) {
        val values = LinkedHashMap<String, Any>()
        val types = HashMap<String, KeyType>()
        val sources = LinkedHashMap<String, String>()
        val applied = ArrayList<String>()

        fun put(key: String, value: Any, source: String) {
            val type = data.types[key] ?: return
            values[key] = value
            types[key] = type
            sources[key] = source
        }

        fun putTyped(key: String, type: KeyType, value: Any, source: String) {
            values[key] = value
            types[key] = type
            sources[key] = source
        }

        fun remove(key: String, source: String) {
            values.remove(key)
            types.remove(key)
            sources[key] = source
        }

        fun putAll(map: Map<String, Any>, source: String) {
            for ((k, v) in map) put(k, v, source)
        }

        fun record(r: Record) {
            val key = r.target.key ?: return
            val source = "measured_record:" + r.key
            when (r.valueKind) {
                "value" -> put(key, r.value!!, source)
                "android_default" -> remove(key, source)
                else -> return
            }
            applied += r.key
        }
    }

    fun build(input: BuildInput): Built {
        val data = input.data
        val acc = Acc(data)
        val records = effectiveRecords(data.records, input.localRecords)
        val configRecords = records.filter { it.target.type == "config" && it.valueKind != "none" && it.target.key !in data.groupB && it.target.key !in data.groupA }
        val sim = input.sim
        var stack: List<Profile> = emptyList()
        var serving: Profile? = null
        var fill: FillItem? = null
        var skipped: List<String> = emptyList()
        var googleValue: Map<String, Any> = data.googleDefault
        val ocdValues = LinkedHashMap<String, Any>()
        val x5Held = LinkedHashMap<String, Pair<Any?, Any?>>()
        val x5Data = LinkedHashMap<String, Any?>()
        val sm7Held = LinkedHashMap<String, Pair<Any?, Any?>>()

        if (input.crashGuardBuild) {
            // 8.3: the crash guard tripped on the build's own dataset: group A only (step 5 is gone).
            if (sim.noSim) googleValue = data.googleNoSim
        } else if (sim.noSim) {
            acc.putAll(data.googleNoSim, "default")
            googleValue = data.googleNoSim
        } else {
            val candidates = data.network(sim.network) ?: refuse("storage", "network ${sim.network} unreadable")
            val served = Matcher.stack(candidates, sim, { it.profile.match }, { it.profile.id }, { it.profile.fillOnly })
            stack = served.map { it.profile }
            val top = served.lastOrNull()
            serving = top?.profile
            // (2) OCD's default profile: the Google lane's default.
            acc.putAll(data.googleDefault, "default")
            // (3) The Google-lane fill of the serving profile.
            if (top != null) {
                val pick = Matcher.pickFill(top.profile.fill, sim) { top.fillValues[it] != null }
                fill = pick.item
                skipped = pick.skippedMissing
                fill?.let { f ->
                    val fv = top.fillValues.getValue(f.entry)!!
                    acc.putAll(fv, "google_fill:${f.entry}")
                    googleValue = LinkedHashMap(data.googleDefault).apply { putAll(fv) }
                }
            }
            // (4) OCD's published values, generic to specific.
            for (p in stack) {
                acc.putAll(p.values, "ocd:${p.id}")
                for ((k, v) in p.values) if (k in data.types) ocdValues[k] = v
            }
            // (5) The cell's measured records, the most specific last; never group A or group B.
            cellRecords(configRecords, input, serving).forEach(acc::record)
            for (k in Rules.X5_OFF.keys) x5Data[k] = acc.values[k]
            if (!input.rawOnly && !input.sm7) x5Hold(acc, input, cellRecords(configRecords, input, serving), x5Held)
        }

        if (!input.rawOnly) {
            applyGroupA(acc, input, stack, googleValue)
            if (!input.crashGuardBuild) applyGroupB(acc, data, ocdValues, googleValue, sim.noSim)
            if (!sim.noSim && !input.crashGuardBuild && sim.network in data.x6Networks) applyX6(acc)
            if (input.sm7) applySm7(acc, googleValue, sm7Held)
            for (k in acc.values.keys.filter { Rules.EMERGENCY_NUMBER_LIST.containsMatchIn(it) }) acc.remove(k, "no_emergency_number")
        }

        val profileName = when {
            input.crashGuardBuild -> "crash_guard_build"
            sim.noSim -> "no_sim"
            else -> serving?.id ?: "default"
        }
        val version = "$profileName+ocd${data.sha7}"
        acc.putTyped(VERSION_KEY, KeyType.STRING, version, "ocd:$profileName")
        return Built(
            acc.values, acc.types, acc.sources, version, serving?.id ?: if (sim.noSim) "no_sim" else "default", stack, fill, skipped, x5Held, acc.applied,
            data.groupA, x5Data, input.sm7, sm7Held,
        )
    }

    /** 5.4: an off from the data is served only once tested on this phone; otherwise the reference's on value. */
    private fun x5Hold(acc: Acc, input: BuildInput, stepRecords: List<Record>, held: MutableMap<String, Pair<Any?, Any?>>) {
        for ((key, off) in Rules.X5_OFF) {
            val value = acc.values[key] ?: continue
            if (value != off) continue
            val released = stepRecords.any { r ->
                r.target.key == key && r.valueKind == "value" && r.value == off &&
                    (r in input.localRecords || r.key in input.x5TestedHere)
            }
            if (released) continue
            val reference = input.x5Reference(key)
            if (reference == !off) {
                acc.put(key, !off, "x5_hold")
                held[key] = off to !off
            }
        }
    }

    /**
     * Round 31 SM7, enforced here whatever the app or the data say: without a tester an X3, X4
     * or X5 value serves only where it equals the Google-lane value; otherwise the Google-lane
     * value, else Android's default.
     */
    private fun applySm7(acc: Acc, googleValue: Map<String, Any>, held: MutableMap<String, Pair<Any?, Any?>>) {
        for (key in acc.values.keys.toList()) {
            val value = acc.values[key]
            Rules.sm7Kind(key, value) ?: continue
            val google = googleValue[key]
            if (google == value) continue
            if (google != null && key in acc.data.types) {
                acc.put(key, google, "sm7:google")
                held[key] = value to google
            } else {
                acc.remove(key, "sm7:android_default")
                held[key] = value to null
            }
        }
    }

    /**
     * Round 31 emergencyValues, with CarrierSettings's own key pattern: for each group A key, the
     * value OCD's stack resolves (the most specific profile that sets the key: its value, then its
     * alternatives) when it differs from the Google lane and a `proceed` read admits that key,
     * value, family and profile for this ROM family and IMS stack; else the Google-lane value of
     * the served dataset; else Android's default. Records never set a group A key.
     */
    private fun applyGroupA(acc: Acc, input: BuildInput, stack: List<Profile>, googleValue: Map<String, Any>) {
        val data = input.data
        for (key in data.groupA) {
            val google = googleValue[key]?.takeIf { key in data.types }
            val read = admitted(key, stack, google, input)
            when {
                read != null -> acc.put(key, read.value, "group_a:read:${read.id}")
                google != null -> acc.put(key, google, "group_a:google")
                else -> acc.remove(key, "group_a:android_default")
            }
        }
    }

    /** The read that admits the stack's own value for [key], or null (R31.7). */
    fun admitted(key: String, stack: List<Profile>, google: Any?, input: BuildInput): GroupARead? {
        val cell = input.cell ?: return null
        val owner = stack.lastOrNull { key in it.values } ?: stack.lastOrNull { key in it.alternatives } ?: return null
        val candidates = ArrayList<Pair<Any, List<String>>>()
        owner.values[key]?.let { candidates += it to owner.sources[key].orEmpty() }
        owner.alternatives[key]?.forEach { candidates += it.value to it.families }
        for ((value, families) in candidates) {
            if (value == google) return null
            val reads = input.data.groupAReads.filter { r ->
                r.verdict == "proceed" && r.key == key && r.profileId == owner.id && r.value == value && r.family in families &&
                    r.romFamily == cell.romFamily && r.stack == cell.stackId && (r.fork == null || r.fork == cell.stackFork)
            }
            // A read of this stack's fork wins over one without a fork.
            (reads.firstOrNull { it.fork != null } ?: reads.firstOrNull())?.let { return it }
        }
        return null
    }

    private fun containsDefault(v: Any?): Boolean = (v as? List<*>)?.contains(Rules.MUST_CONTAIN) == true

    /** 5.2: the first of OCD's resolved value, the Google value and Android's default that contains `default`. */
    private fun applyGroupB(acc: Acc, data: ServingData, ocd: Map<String, Any>, googleValue: Map<String, Any>, noSim: Boolean) {
        for (key in data.groupB) {
            when {
                !noSim && containsDefault(ocd[key]) -> acc.put(key, ocd.getValue(key), "group_b:ocd")
                containsDefault(googleValue[key]) -> acc.put(key, googleValue.getValue(key), "group_b:google")
                else -> acc.remove(key, "group_b:android_default")
            }
        }
    }

    /** 5.3: no SMS-to-MMS conversion, no group MMS, multipart SMS on, on the MMS-ended networks. */
    private fun applyX6(acc: Acc) {
        for (key in listOf(Rules.SMS_TO_MMS, Rules.SMS_TO_MMS_LENGTH)) {
            val v = acc.values[key]
            if (v is Int && v >= 0) acc.putTyped(key, KeyType.INT, -1, "x6")
        }
        val group = acc.values[Rules.GROUP_MMS]
        if (group == null || group == true) acc.putTyped(Rules.GROUP_MMS, KeyType.BOOL, false, "x6")
        if (acc.values[Rules.MULTIPART_SMS] == false) acc.putTyped(Rules.MULTIPART_SMS, KeyType.BOOL, true, "x6")
    }

    /**
     * 9.4 point 3: the last fallback when no dataset can be read. Step 5 is gone (round 31), so
     * it holds the version string only; CarrierConfigService applies LineageOS's fixed key and the
     * device override XML after it, as on every path.
     */
    fun minimal(sha7: String): Built {
        val version = "fallback+ocd$sha7"
        val values = LinkedHashMap<String, Any>()
        values[VERSION_KEY] = version
        return Built(values, mapOf(VERSION_KEY to KeyType.STRING), mapOf(VERSION_KEY to "ocd:fallback"), version, "fallback", emptyList(), null, emptyList(), emptyMap(), emptyList(), emptySet(), emptyMap())
    }
}
