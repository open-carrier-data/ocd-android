package io.github.open_carrier_data.check.core

/*
 * Field-allowlist parsers for the deep check, ported from phone_check.py.
 * Each reads its command's output line by line and keeps only typed fields;
 * a line is dropped as soon as it has been looked at. IMSI, ICCID, IMEI,
 * phone numbers, SIP URIs and IP addresses have no field to land in.
 */

/** A free-text value is kept only if it has a letter and no run of 9 or more digits (phone_check.py `redact`). */
internal fun safeToken(value: String): Boolean = value.any { it in 'A'..'Z' || it in 'a'..'z' } && !LONG_DIGITS.containsMatchIn(value)

private val LONG_DIGITS = Regex("[0-9]{9,}")

/** `dumpsys secims`: phone_check.py `parse_secims`, for one slot. */
object SamsungImsParser {
    private val PDN = Regex("pdn : (\\w+)")
    private val GOVERNOR = Regex("Governor: RegiGvnBase \\[mMno=([^,\\]]+)")
    private val ANY_TASK = Regex("SIM slot: \\[\\d+\\]")

    private class Task(val state: String, val profile: String, val imsPdn: Boolean) {
        var mno: String? = null
    }

    fun parse(lines: Sequence<String>, slot: SimSlot): ImsReading {
        val n = slot.index
        val taskLine = Regex("SIM slot: \\[$n\\] state: \\[(\\w+)\\] IMS Profile: \\[Name : ([^,\\]]+)(.*)")
        val registeredLine = Regex(
            "slot\\[$n\\]: \\[[^\\]]*\\] onRegistered: RAT = (\\d+), profile=([^,]*), service=\\[([^\\]]*)\\]",
        )
        var count = 0
        var notFound = false
        var denied = false
        var chosen: Task? = null
        var block: Task? = null
        // Last registration per profile name: the profile names are Samsung's table labels, not personal.
        val lastRegistration = HashMap<String, Pair<ImsTransport, List<ImsService>>>()
        for (line in lines) {
            count++
            if (count <= 2 && line.startsWith("Can't find service")) notFound = true
            if (count <= 2 && line.startsWith("Permission Denial")) denied = true
            val task = taskLine.find(line)
            if (task != null) {
                val pdn = PDN.find(task.groupValues[3])?.groupValues?.get(1)
                if (pdn == "emergency") {
                    block = null
                    continue
                }
                val candidate = Task(task.groupValues[1], task.groupValues[2].trim(), pdn == "ims")
                block = candidate
                // phone_check.py takes the first task of the slot; a task on the ims PDN wins over others.
                if (chosen == null || (!chosen.imsPdn && candidate.imsPdn)) chosen = candidate
                continue
            }
            if (ANY_TASK.containsMatchIn(line)) {
                block = null
                continue
            }
            val current = block
            if (current != null && current.mno == null) {
                GOVERNOR.find(line)?.let { current.mno = it.groupValues[1].trim().takeIf(ImsReading::validMno) }
            }
            registeredLine.find(line)?.let { match ->
                val services = match.groupValues[3].split(',')
                    .mapNotNull { ImsService.fromWire(it.trim()) }
                    .distinct()
                    .sortedBy { it.wire }
                lastRegistration[match.groupValues[2].trim()] = ImsTransport.fromRat(match.groupValues[1]) to services
            }
        }
        val task = chosen
        return when {
            notFound -> ImsReading(ImsSource.NOT_FOUND)
            denied || count == 0 -> ImsReading(ImsSource.UNREADABLE)
            task == null -> ImsReading(ImsSource.SAMSUNG)
            else -> {
                val registered = task.state == "REGISTERED"
                val registration = if (registered) lastRegistration[task.profile] else null
                ImsReading(
                    source = ImsSource.SAMSUNG,
                    registered = registered,
                    state = task.state.lowercase().takeIf(ImsReading.STATE::matches),
                    transport = registration?.first,
                    services = registration?.second.orEmpty(),
                    mno = task.mno,
                )
            }
        }
    }
}

/** `settings get system wifi_call_enable<N>`: phone_check.py reads 1 as on and 0 as off. */
object WifiCallingSwitchParser {
    fun parse(lines: Sequence<String>): WifiCallingSwitch = when (lines.firstOrNull()?.trim()) {
        "1" -> WifiCallingSwitch.ON
        "0" -> WifiCallingSwitch.OFF
        "null" -> WifiCallingSwitch.NOT_SET
        else -> WifiCallingSwitch.UNREADABLE
    }
}

/** First line of `ping -c1 -W2 <ePDG name>`: phone_check.py `epdg_resolves`. The address in it is never kept. */
object EpdgLookupParser {
    fun parse(lines: Sequence<String>): EpdgName {
        val first = lines.firstOrNull() ?: return EpdgName.UNKNOWN
        return when {
            "unknown host" in first -> EpdgName.DOES_NOT_RESOLVE
            first.startsWith("PING ") -> EpdgName.RESOLVES
            else -> EpdgName.UNKNOWN
        }
    }
}

/** `content query` on the APN table, in the `Row: N col=value, col=value` format of Android's `content` tool. */
object ApnListParser {
    val COLUMNS = listOf("apn", "type", "protocol", "roaming_protocol", "carrier_enabled", "mvno_type", "edited")
    val TYPES = setOf(
        "default", "mms", "supl", "dun", "hipri", "fota", "ims", "cbs", "ia", "xcap", "mcx",
        "vsim", "bip", "enterprise", "rcs", "oem_paid", "oem_private", "*",
    )
    val PROTOCOLS = setOf("IP", "IPV6", "IPV4V6", "PPP", "NON-IP", "UNSTRUCTURED")
    val MVNO_TYPES = setOf("spn", "imsi", "gid", "iccid")
    const val MAX_ROWS = 40

    private val ROW = Regex("^Row: \\d+ (.*)$")
    private val SPLIT = Regex(", (?=(?:${COLUMNS.joinToString("|")})=)")

    /**
     * Rows whose kept columns are all the same are shown once and counted in
     * [ApnList.repeated]: Samsung keeps a copy of each row per SIM slot (A50,
     * One UI on Android 14, 2026-10-19), which the projection cannot tell apart.
     */
    fun parse(lines: Sequence<String>): ApnList {
        var answered = false
        val rows = mutableListOf<ApnRow>()
        val seen = HashSet<ApnRow>()
        var leftOut = 0
        var repeated = 0
        for (line in lines) {
            if (line.startsWith("No result found")) answered = true
            val body = ROW.find(line)?.groupValues?.get(1) ?: continue
            answered = true
            val row = row(body)
            when {
                row == null -> leftOut++
                !seen.add(row) -> repeated++
                rows.size >= MAX_ROWS -> leftOut++
                else -> rows += row
            }
        }
        return if (answered) ApnList(readable = true, rows = rows, leftOut = leftOut, repeated = repeated) else ApnList.UNREADABLE
    }

    private fun row(body: String): ApnRow? {
        val fields = body.split(SPLIT).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        if (fields.keys != COLUMNS.toSet()) return null
        val apn = fields.getValue("apn").takeIf(ApnRow::validApn) ?: return null
        val types = fields.getValue("type").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if ("emergency" in types) return null
        val edited = when (fields.getValue("edited")) {
            "0" -> ApnEdited.NO
            "1" -> ApnEdited.BY_USER
            "4" -> ApnEdited.BY_CARRIER
            "2", "3", "5", "6" -> return null // deleted rows
            else -> ApnEdited.OTHER
        }
        return ApnRow(
            apn = apn,
            types = types.filter { it in TYPES }.distinct(),
            protocol = fields.getValue("protocol").uppercase().takeIf { it in PROTOCOLS },
            roamingProtocol = fields.getValue("roaming_protocol").uppercase().takeIf { it in PROTOCOLS },
            carrierEnabled = when (fields.getValue("carrier_enabled")) {
                "1" -> true
                "0" -> false
                else -> null
            },
            mvnoType = fields.getValue("mvno_type").lowercase().takeIf { it in MVNO_TYPES },
            edited = edited,
        )
    }
}
