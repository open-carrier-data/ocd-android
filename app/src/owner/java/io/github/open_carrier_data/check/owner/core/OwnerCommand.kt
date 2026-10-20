package io.github.open_carrier_data.check.owner.core

/** What a command is run with. Each command takes only what its argv needs and checks it again. */
data class OwnerArgs(
    val slot: Int? = null,
    val subId: Int? = null,
    val key: String? = null,
    val value: Boolean? = null,
    val numeric: String? = null,
    val id: Long? = null,
    val binds: Map<String, String> = emptyMap(),
)

/**
 * The owner build's whole command list for the Shizuku route (and the read
 * commands of the privileged route). Every argv is built from constants and
 * validated arguments only; no other text reaches a process. The writes are
 * `cmd phone cc set-value` without `-p`, `cmd phone cc clear-values`, and
 * `content` on the APN table (a test row in, a row out by its ID) and the
 * preferred APN. Never `-p`, never `settings put`, never Wi-Fi, never
 * logcat, never a reboot, never a dial (2026-10-20).
 */
enum class OwnerCommand(val timeoutSeconds: Long, val writes: Boolean) {
    UID(5, false),
    CC_DUMP(60, false),
    REGISTRY(20, false),
    PHONE(120, false),
    ECM_PROPERTY(10, false),
    POWER(20, false),
    IWLAN_DUMP(30, false),
    QNS_DUMP(30, false),
    CC_SET(60, true),
    CC_CLEAR(60, true),
    APN_QUERY(30, false),
    APN_QUERY_MARKED(30, false),
    APN_BY_ID(30, false),
    APN_ALL(60, false),
    APN_CONFLICTS(30, false),
    APN_INSERT(30, true),
    APN_DELETE(30, true),
    PREFERRED_QUERY(20, false),
    PREFERRED_SET(20, true),
    PREFERRED_CLEAR(20, true);

    /** The argv, or null when an argument is missing or of the wrong shape. A forbidden key throws. */
    fun argv(a: OwnerArgs): List<String>? = when (this) {
        UID -> listOf("id", "-u")
        CC_DUMP -> listOf("dumpsys", "carrier_config")
        REGISTRY -> listOf("dumpsys", "telephony.registry")
        PHONE -> listOf("dumpsys", "activity", "service", "com.android.phone")
        ECM_PROPERTY -> listOf("getprop", "ril.cdma.inecmmode")
        POWER -> listOf("dumpsys", "power")
        IWLAN_DUMP -> listOf("dumpsys", "activity", "service", "com.google.android.iwlan/.IwlanDataService")
        QNS_DUMP -> listOf("dumpsys", "activity", "service", "com.android.telephony.qns")
        CC_SET -> {
            val key = a.key ?: return null
            KeyGuard.check(key)
            val value = a.value ?: return null
            slot(a)?.let { listOf("cmd", "phone", "cc", "set-value", "-s", it.toString(), key, value.toString()) }
        }
        CC_CLEAR -> slot(a)?.let { listOf("cmd", "phone", "cc", "clear-values", "-s", it.toString()) }
        APN_QUERY -> numeric(a)?.let {
            listOf("content", "query", "--uri", CARRIERS, "--projection", PhoneApnRow.COLUMNS.joinToString(":"), "--where", "numeric='$it'")
        }
        APN_QUERY_MARKED -> listOf(
            "content", "query", "--uri", CARRIERS, "--projection", PhoneApnRow.COLUMNS.joinToString(":"),
            "--where", "name LIKE '%${ApnColumns.MARKER.trim()}'",
        )
        APN_BY_ID -> a.id?.takeIf { it >= 0 }?.let {
            listOf("content", "query", "--uri", CARRIERS, "--projection", PhoneApnRow.COLUMNS.joinToString(":"), "--where", "_id=$it")
        }
        // Every visible row, for the journal's hash; no credential or server column.
        APN_ALL -> listOf("content", "query", "--uri", CARRIERS, "--projection", HASH_COLUMNS.joinToString(":"), "--sort", "_id")
        // The rows the provider would merge an insert of these values into (its UNIQUE columns).
        APN_CONFLICTS -> binds(a, insert = false)?.let { binds ->
            val where = ApnColumns.conflictMatch(binds).joinToString(" AND ") { (column, value) -> "$column='${value.replace("'", "''")}'" }
            listOf("content", "query", "--uri", CARRIERS, "--projection", "_id", "--where", where)
        }
        APN_INSERT -> binds(a, insert = true)?.let { binds ->
            listOf("content", "insert", "--uri", CARRIERS) + binds.flatMap { (column, value) ->
                listOf("--bind", "$column:${if (column in ApnColumns.INTEGER) "i" else "s"}:$value")
            }
        }
        APN_DELETE -> a.id?.takeIf { it >= 0 }?.let { listOf("content", "delete", "--uri", CARRIERS, "--where", "_id=$it") }
        PREFERRED_QUERY -> sub(a)?.let { listOf("content", "query", "--uri", "$PREFERAPN/$it", "--projection", "_id") }
        PREFERRED_SET -> {
            val id = a.id?.takeIf { it >= 0 } ?: return null
            sub(a)?.let { listOf("content", "insert", "--uri", "$PREFERAPN/$it", "--bind", "apn_id:l:$id") }
        }
        PREFERRED_CLEAR -> sub(a)?.let { listOf("content", "delete", "--uri", "$PREFERAPN/$it") }
    }

    companion object {
        const val CARRIERS = "content://telephony/carriers"
        const val PREFERAPN = "content://telephony/carriers/preferapn/subId"

        private val NUMERIC = Regex("[0-9]{5,6}")
        private val VALUE = Regex("[\\x20-\\x7E]{0,255}")
        private val BIND_COLUMNS: Set<String> = ApnColumns.FROM_XML.values.toSet() +
            setOf("numeric", "name", "user_visible", "edited", "infrastructure_bitmask", "apn_set_id")

        /** The columns hashed for the APN journal: what a stock row is, without credentials or servers. */
        val HASH_COLUMNS = listOf(
            "_id", "name", "numeric", "apn", "type", "protocol", "roaming_protocol", "mvno_type", "mvno_match_data",
            "carrier_id", "carrier_enabled", "edited", "user_visible", "apn_set_id", "infrastructure_bitmask",
        )

        private fun slot(a: OwnerArgs): Int? = a.slot?.takeIf { it in 0..3 }
        private fun sub(a: OwnerArgs): Int? = a.subId?.takeIf { it in 1..Int.MAX_VALUE }
        private fun numeric(a: OwnerArgs): String? = a.numeric?.takeIf(NUMERIC::matches)

        /**
         * APN values: only provider columns the owner build writes, printable
         * ASCII, integers where the column is one, never an emergency type. A
         * lookup ([insert] false) carries a network code or a carrier ID. An
         * insert is a test row only: the marker on the name, an empty numeric
         * with the SIM's carrier ID, edited (never 0), and no ia, wildcard,
         * ims or emergency type (the report of 2026-10-20).
         */
        private fun binds(a: OwnerArgs, insert: Boolean): Map<String, String>? {
            val binds = a.binds
            if (binds.isEmpty() || binds.keys.any { it !in BIND_COLUMNS }) return null
            if (binds.values.any { !VALUE.matches(it) }) return null
            if (binds.any { (k, v) -> k in ApnColumns.INTEGER && v.isNotEmpty() && v.toLongOrNull() == null }) return null
            val carrierId = binds["carrier_id"]?.toLongOrNull()
            val numeric = binds["numeric"] ?: return null
            if (!NUMERIC.matches(numeric) && !(numeric.isEmpty() && carrierId != null && carrierId > 0)) return null
            val types = binds["type"].orEmpty().lowercase().split(',').map { it.trim() }
            if ("emergency" in types || types.any { KeyGuard.FORBIDDEN.containsMatchIn(it) }) return null
            if (insert) {
                if (binds["name"]?.endsWith(ApnColumns.MARKER) != true) return null
                if (numeric.isNotEmpty() || carrierId == null || carrierId <= 0) return null
                if (binds["edited"] != ApnColumns.USER_EDITED) return null
                if (types.any { it in ApnColumns.TEST_ROW_NEVER }) return null
            }
            return binds
        }
    }
}
