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
 * `cmd phone cc set-value` without `-p`, `cmd phone cc clear-values`,
 * `content` on the APN table and the preferred APN, and `cmd wifi` for
 * VoLTE's Wi-Fi-off measurement. Never `-p`, never `settings put`, never a
 * reboot, never a dial.
 */
enum class OwnerCommand(val timeoutSeconds: Long, val writes: Boolean) {
    UID(5, false),
    CC_DUMP(60, false),
    REGISTRY(20, false),
    PHONE(120, false),
    ECM_PROPERTY(10, false),
    IWLAN_LOG(30, false),
    CC_SET(60, true),
    CC_CLEAR(60, true),
    APN_QUERY(30, false),
    APN_QUERY_MARKED(30, false),
    APN_CONFLICTS(30, false),
    APN_INSERT(30, true),
    APN_DELETE(30, true),
    PREFERRED_QUERY(20, false),
    PREFERRED_SET(20, true),
    PREFERRED_CLEAR(20, true),
    WIFI_SET(20, true);

    /** The argv, or null when an argument is missing or of the wrong shape. A forbidden key throws. */
    fun argv(a: OwnerArgs): List<String>? = when (this) {
        UID -> listOf("id", "-u")
        CC_DUMP -> listOf("dumpsys", "carrier_config")
        REGISTRY -> listOf("dumpsys", "telephony.registry")
        PHONE -> listOf("dumpsys", "activity", "service", "com.android.phone")
        ECM_PROPERTY -> listOf("getprop", "ril.cdma.inecmmode")
        IWLAN_LOG -> slot(a)?.let {
            listOf("logcat", "-d", "-v", "epoch", "-b", "main", "-b", "radio", "-s", "EpdgSelector:V", "IwlanDataService[$it]:V", "EpdgTunnelManager[$it]:V")
        }
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
        // The rows the provider would merge an insert of these values into (its UNIQUE columns).
        APN_CONFLICTS -> binds(a)?.let { binds ->
            val where = ApnColumns.conflictMatch(binds).joinToString(" AND ") { (column, value) -> "$column='${value.replace("'", "''")}'" }
            listOf("content", "query", "--uri", CARRIERS, "--projection", "_id", "--where", where)
        }
        APN_INSERT -> binds(a)?.let { binds ->
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
        // VoLTE's measurement while IMS runs over Wi-Fi; the runner always switches it back on.
        WIFI_SET -> a.value?.let { listOf("cmd", "wifi", "set-wifi-enabled", if (it) "enabled" else "disabled") }
    }

    companion object {
        const val CARRIERS = "content://telephony/carriers"
        const val PREFERAPN = "content://telephony/carriers/preferapn/subId"

        private val NUMERIC = Regex("[0-9]{5,6}")
        private val VALUE = Regex("[\\x20-\\x7E]{0,255}")
        private val BIND_COLUMNS: Set<String> = ApnColumns.FROM_XML.values.toSet() + setOf("numeric", "name", "user_visible", "edited")

        private fun slot(a: OwnerArgs): Int? = a.slot?.takeIf { it in 0..3 }
        private fun sub(a: OwnerArgs): Int? = a.subId?.takeIf { it in 1..Int.MAX_VALUE }
        private fun numeric(a: OwnerArgs): String? = a.numeric?.takeIf(NUMERIC::matches)

        /**
         * APN insert values: only provider columns the owner build writes,
         * printable ASCII, integers where the column is one, never an
         * emergency type, and the test marker on the name.
         */
        private fun binds(a: OwnerArgs): Map<String, String>? {
            val binds = a.binds
            if (binds.isEmpty() || binds.keys.any { it !in BIND_COLUMNS }) return null
            if (binds.values.any { !VALUE.matches(it) }) return null
            if (binds.any { (k, v) -> k in ApnColumns.INTEGER && v.toLongOrNull() == null }) return null
            if (binds["numeric"]?.let(NUMERIC::matches) != true) return null
            if (binds["name"]?.endsWith(ApnColumns.MARKER) != true) return null
            val types = binds["type"].orEmpty().lowercase().split(',').map { it.trim() }
            if ("emergency" in types || types.any { KeyGuard.FORBIDDEN.containsMatchIn(it) }) return null
            return binds
        }
    }
}
