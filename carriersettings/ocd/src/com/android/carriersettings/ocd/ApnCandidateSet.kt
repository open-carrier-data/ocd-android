/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

/**
 * The APN candidate set (OCD rule round 32, D8 and D7 rule 4, with the restore candidates of
 * rounds 33, 34 and 36): one SIM's non-persistent trial set, through round 31 item 6's
 * temporary-set path. After a measured failure the OCD app computes the candidate from OCD's
 * published data at the installed commit (its `ApnSetCandidate`) and sends it as JSON in the
 * `candidate` extra of `apn_candidate_set`. CarrierSettings reads it strictly, applies its
 * operations to the installed commit's composed APN file, and checks the result itself,
 * whatever the app sent:
 * - only rows of this SIM's network that this SIM selects change, and only inserted rows this
 *   SIM selects afterwards are added;
 * - no row that serves emergency (its type `emergency`, empty or `*`), and no row TelephonyProvider
 *   would store as one row with it, is removed, retyped, stripped or moved; an inserted row may
 *   serve emergency only as a copy of one of the SIM's emergency rows under the SIM's own MVNO
 *   selector, and the SIM's emergency rows read the same afterwards (selector aside);
 * - the SIM keeps an internet row and an IMS row where it had them;
 * - every other SIM in the phone selects exactly the rows it selected before;
 * - the result is APN version 8 with the expected row count, and differs from the installed file.
 * A refusal is `candidate_invalid` (or `sim_loses_internet`, `sim_loses_ims`) with a reason that
 * names row keys only, never a SIM identifier. Everything here is pure.
 */
object ApnCandidateSet {
    const val OMIT = "omit"
    const val INSERT = "insert"
    const val SET_TYPES = "set_types"
    const val UNSET = "unset"
    const val ORDER = "order"

    const val MAX_CHARS = 64 * 1024
    const val MAX_OPS = 64
    const val MAX_INSERTS = 48
    const val MAX_VALUE = 1024

    /** APN types a candidate may write (ApnSetting's names), never `emergency` or `*` on a row of its own. */
    val TYPES = setOf(
        "default", "mms", "supl", "dun", "hipri", "fota", "ims", "cbs", "ia", "mcx", "xcap", "vsim", "bip", "enterprise", "rcs", "oem_paid", "oem_private",
    )

    /** The fields a fill-in carries (D8 class (a)): what `unset` may take off a row. */
    val FILL_FIELDS = setOf("user", "password", "authtype", "mtu", "mtu_v4", "mtu_v6")

    /** The attributes OCD's generator writes into apns-conf.xml: the only ones an inserted row may carry. */
    val ATTRIBUTES = setOf(
        "carrier", "mcc", "mnc", "apn", "type", "protocol", "roaming_protocol", "mvno_type", "mvno_match_data", "mmsc", "mmsproxy", "mmsport",
        "authtype", "user", "password", "server", "proxy", "port", "bearer", "bearer_bitmask", "network_type_bitmask",
        "lingering_network_type_bitmask", "infrastructure_bitmask", "user_visible", "user_editable", "carrier_enabled", "carrier_id",
        "profile_id", "apn_set_id", "skip_464xlat", "modem_cognitive", "always_on", "esim_bootstrap_provisioning", "max_conns",
        "max_conns_time", "wait_time", "mtu", "mtu_v4", "mtu_v6",
    )

    val MVNO_TYPES = setOf("spn", "imsi", "gid", "iccid")

    /** The app's LeadRole (its wire name or enum name) to the APN type that serves it. */
    val ROLES = mapOf("internet" to "default", "attach" to "ia", "mms" to "mms", "ims" to "ims", "xcap" to "xcap")

    private val ROW_KEY = Regex("^a:[0-9a-f]{16}$")
    private val ID = Regex("^[A-Za-z0-9:._-]{1,64}$")
    private val SELECTOR = setOf("mvno_type", "mvno_match_data")

    /** One operation (the app's ApnOp); members by [op], checked by [parse]. */
    class Op(
        val op: String,
        val rowKey: String? = null,
        val attributes: Map<String, String>? = null,
        val before: String? = null,
        val types: List<String>? = null,
        val fields: List<String>? = null,
        val rowKeys: List<String>? = null,
    )

    /** One candidate (the app's ApnSetCandidate). [role] is the APN type of its lead, when named. */
    class Candidate(val id: String, val rule: String, val kind: String, val role: String?, val plain: String, val ops: List<Op>)

    /**
     * The checked set: the composed file, the row keys left out, written anew (their new keys) and
     * added, the rows of the installed file it touches (for the user-edited check, with the stored
     * keys of the inserted rows), and the SIM's selection afterwards.
     */
    class Result(
        val composed: ApnLogic.Composed,
        val omitted: List<String>,
        val changed: List<String>,
        val added: List<String>,
        val touchedKeys: Set<String>,
        val selection: List<String>,
    )

    private fun invalid(detail: String): Nothing = refuse("candidate_invalid", detail)

    // --- the candidate as the app sends it ----------------------------------------------------

    fun parse(text: String?): Candidate {
        text ?: refuse("bad_request", "no candidate")
        if (text.length > MAX_CHARS) invalid("the candidate is over ${MAX_CHARS / 1024} KiB")
        return try {
            parseObject(Obj.of(Json.parse(text), "candidate"))
        } catch (e: Json.ParseException) {
            invalid("the candidate is not JSON: ${e.message}")
        } catch (e: Refused) {
            if (e.code == "candidate_invalid") throw e
            invalid(e.detail)
        }
    }

    private fun parseObject(o: Obj): Candidate {
        val id = o.str("id").also { if (!ID.matches(it)) invalid("the candidate's id is not an ID") }
        val role = o.strOrNull("role")?.lowercase(java.util.Locale.ROOT)?.let { r ->
            ROLES[r] ?: r.takeIf { it in ROLES.values } ?: invalid("unknown role $r")
        }
        val list = o.list("ops")
        if (list.isEmpty()) invalid("the candidate has no operation")
        if (list.size > MAX_OPS) invalid("the candidate has ${list.size} operations, at most $MAX_OPS")
        val ops = list.mapIndexed { i, raw -> op(Obj.of(raw, "candidate.ops[$i]"), i) }
        if (ops.count { it.op == INSERT } > MAX_INSERTS) invalid("the candidate inserts more than $MAX_INSERTS rows")
        return Candidate(id, o.strOrNull("rule").orEmpty().take(120), o.strOrNull("kind").orEmpty().take(40), role, o.strOrNull("plain").orEmpty().take(240), ops)
    }

    private fun rowKey(o: Obj, name: String, where: String): String =
        o.str(name).also { if (!ROW_KEY.matches(it)) invalid("$where.$name is not a row key") }

    private fun op(o: Obj, i: Int): Op {
        val where = "operation $i"
        return when (val name = o.str("op")) {
            OMIT -> {
                o.members(setOf("op", "row_key"))
                Op(OMIT, rowKey = rowKey(o, "row_key", where))
            }
            SET_TYPES -> {
                o.members(setOf("op", "row_key", "types"))
                val types = o.str("types").split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }
                if (types.any { it.isEmpty() }) invalid("$where: an empty type")
                types.firstOrNull { it !in TYPES }?.let { invalid("$where: type $it is not one a candidate may write") }
                if (types.toSet().size != types.size) invalid("$where: a type twice")
                Op(SET_TYPES, rowKey = rowKey(o, "row_key", where), types = types)
            }
            UNSET -> {
                o.members(setOf("op", "row_key", "fields"))
                val fields = o.strings("fields")
                if (fields.isEmpty()) invalid("$where: no field")
                fields.firstOrNull { it !in FILL_FIELDS }?.let { invalid("$where: field $it is not a username, password, auth type or MTU") }
                if (fields.toSet().size != fields.size) invalid("$where: a field twice")
                Op(UNSET, rowKey = rowKey(o, "row_key", where), fields = fields)
            }
            INSERT -> {
                o.members(setOf("op", "attributes"), setOf("before"))
                val a = o.obj("attributes")
                val attrs = LinkedHashMap<String, String>()
                for (k in a.keys) {
                    if (k !in ATTRIBUTES) invalid("$where: attribute $k is not one apns-conf.xml carries")
                    val v = a.str(k)
                    if (v.length > MAX_VALUE) invalid("$where: attribute $k is over $MAX_VALUE characters")
                    if (v.any { it < ' ' || it == '\u007f' }) invalid("$where: attribute $k has a control character")
                    attrs[k] = v
                }
                Op(INSERT, attributes = attrs, before = if (o.isNull("before")) null else rowKey(o, "before", where))
            }
            ORDER -> {
                o.members(setOf("op", "row_keys"))
                val keys = o.strings("row_keys")
                keys.firstOrNull { !ROW_KEY.matches(it) }?.let { invalid("$where: $it is not a row key") }
                if (keys.size < 2) invalid("$where: an order needs two rows")
                if (keys.toSet().size != keys.size) invalid("$where: a row twice")
                Op(ORDER, rowKeys = keys)
            }
            else -> invalid("$where: unknown operation $name")
        }
    }

    // --- the set ------------------------------------------------------------------------------

    private fun types(attrs: Map<String, String>): List<String> =
        attrs["type"].orEmpty().split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }

    /** A row's attributes without its MVNO selector: an emergency row and its copy under the SIM's selector read the same. */
    private fun unselected(attrs: Map<String, String>): String = Json.write(attrs.filterKeys { it !in SELECTOR }.toSortedMap())

    /** The SIM's emergency rows as they read without the selector, sorted: equal before and after. */
    private fun emergencyPrint(rows: List<ApnXml.Row>): List<String> = rows.filter { it.serves("emergency") }.map { unselected(it.attrs) }.sorted()

    /**
     * IMS after a candidate set is no worse than before it: every MmTel service available before
     * (the `imsSnapshot` after its `;`) is available again. Before with none, any reading passes.
     */
    fun imsNoWorse(before: String?, after: String?): Boolean {
        before ?: return true
        after ?: return false
        fun services(s: String) = s.substringAfter(';', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return services(after).containsAll(services(before))
    }

    fun escape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c < ' ' || c == '\u007f' -> append("&#").append(c.code).append(';')
                else -> append(c)
            }
        }
    }

    /** One `<apn …/>` line as OCD's generator writes it: the attributes sorted by name, ` />` at the end. */
    fun line(indent: String, attrs: Map<String, String>, cr: Boolean): String =
        indent + "<apn " + attrs.entries.sortedWith { a, b -> compareBytes(a.key, b.key) }.joinToString(" ") { "${it.key}=\"${escape(it.value)}\"" } + " />" + if (cr) "\r" else ""

    /**
     * [full] with the candidate's operations for [sim], checked. [others] are the other SIMs in the
     * phone, whose selections must stay as they are.
     */
    fun compose(full: ApnLogic.Composed, sim: SimId, others: List<SimId>, c: Candidate): Result {
        val doc = full.doc
        val network = sim.mcc + sim.mnc
        val selected = ApnSelect.select(doc.rows, sim)
        val mine = selected.filter { it.numeric == network }
        if (mine.isEmpty()) invalid("the SIM selects no APN row of its network")
        val emergencyKeys = selected.filter { it.serves("emergency") }.map { ApnLogic.key(it) }.toSet()
        val emergencyRows = selected.filter { it.serves("emergency") }

        fun row(key: String, what: String): ApnXml.Row {
            val m = mine.filter { it.rowKey == key }
            if (m.isEmpty()) invalid("$what row $key is not one of this SIM's rows")
            if (m.size > 1) invalid("$what row $key is on ${m.size} lines")
            return m[0]
        }
        fun guarded(key: String, what: String): ApnXml.Row {
            val r = row(key, what)
            if (r.serves("emergency")) invalid("$what row $key serves emergency; a candidate never removes or changes the SIM's emergency rows")
            if (ApnLogic.key(r) in emergencyKeys) invalid("$what row $key is stored as one row with an emergency row")
            return r
        }

        val omit = LinkedHashSet<Int>()
        val rewrite = HashMap<Int, Map<String, String>>()
        val edited = HashSet<Int>()
        val placed = HashMap<Int, Int>()
        val beforeRow = HashMap<Int, MutableList<Map<String, String>>>()
        val afterLine = HashMap<Int, MutableList<Map<String, String>>>()
        val anchors = HashSet<Int>()
        val added = ArrayList<String>()
        val touched = HashSet<String>()
        val lastLine = mine.maxOf { it.line }

        for ((i, op) in c.ops.withIndex()) {
            val where = "operation $i"
            when (op.op) {
                OMIT -> {
                    val r = guarded(op.rowKey!!, "the omitted")
                    if (!edited.add(r.line)) invalid("$where: row ${r.rowKey} is changed twice")
                    omit += r.line
                    touched += ApnLogic.key(r)
                }
                SET_TYPES -> {
                    val r = guarded(op.rowKey!!, "the retyped")
                    if (!edited.add(r.line)) invalid("$where: row ${r.rowKey} is changed twice")
                    val old = r.types
                    val new = op.types!!
                    if (!new.containsAll(old)) invalid("$where: row ${r.rowKey} would lose a type it has")
                    if (new.toSet() == old.toSet()) invalid("$where: row ${r.rowKey} has these types already")
                    rewrite[r.line] = LinkedHashMap(r.attrs).apply { put("type", new.joinToString(",")) }
                    touched += ApnLogic.key(r)
                }
                UNSET -> {
                    val r = guarded(op.rowKey!!, "the stripped")
                    if (!edited.add(r.line)) invalid("$where: row ${r.rowKey} is changed twice")
                    op.fields!!.firstOrNull { it !in r.attrs }?.let { invalid("$where: row ${r.rowKey} has no $it") }
                    rewrite[r.line] = r.attrs.filterKeys { it !in op.fields }
                    touched += ApnLogic.key(r)
                }
                ORDER -> {
                    val rows = op.rowKeys!!.map { guarded(it, "the reordered") }
                    val positions = rows.map { it.line }.sorted()
                    if (positions == rows.map { it.line }) invalid("$where: the rows are in this order already")
                    for ((k, pos) in positions.withIndex()) {
                        if (placed.containsKey(pos)) invalid("$where: row ${rows[k].rowKey} is reordered twice")
                        placed[pos] = rows[k].line
                    }
                    rows.forEach { touched += ApnLogic.key(it) }
                }
                INSERT -> {
                    val a = op.attributes!!
                    if (a["mcc"] != sim.mcc || a["mnc"] != sim.mnc) invalid("$where: the row is not for this SIM's network")
                    if (a["apn"].isNullOrEmpty()) invalid("$where: the row has no APN")
                    val t = types(a)
                    if (t.isEmpty()) invalid("$where: the row has no type (it would serve every type, emergency included)")
                    t.firstOrNull { it !in TYPES && it != "emergency" }?.let { invalid("$where: type $it is not one a candidate may write") }
                    val mt = a["mvno_type"]
                    val md = a["mvno_match_data"]
                    if ((mt == null) != (md == null)) invalid("$where: an MVNO type without match data, or match data without a type")
                    if (mt != null) {
                        if (mt.lowercase(java.util.Locale.ROOT) !in MVNO_TYPES) invalid("$where: unknown MVNO type")
                        if (!ApnSelect.mvnoMatches(mt, md!!, sim)) invalid("$where: its MVNO selector does not match this SIM")
                    }
                    a["carrier_id"]?.let { if (it.toIntOrNull() == null) invalid("$where: carrier_id is not a number") }
                    if ("emergency" in t && (mt == null || emergencyRows.none { unselected(it.attrs) == unselected(a) })) {
                        invalid("$where: the row serves emergency; only a copy of one of the SIM's emergency rows under the SIM's MVNO selector may be added")
                    }
                    val key = ApnXml.rowKey(a)
                    if (key in added) invalid("$where: row $key is inserted twice")
                    added += key
                    touched += ApnLogic.key(mcc = a["mcc"], mnc = a["mnc"], apn = a["apn"], mvnoType = mt, mvnoData = md, protocol = a["protocol"], roaming = a["roaming_protocol"], carrierId = a["carrier_id"]?.toIntOrNull())
                    if (op.before != null) {
                        val anchor = row(op.before, "the anchor")
                        anchors += anchor.line
                        beforeRow.getOrPut(anchor.line) { ArrayList() } += a
                    } else {
                        afterLine.getOrPut(lastLine) { ArrayList() } += a
                    }
                }
                else -> invalid("$where: unknown operation ${op.op}")
            }
        }
        if (placed.values.any { it in omit }) invalid("a reordered row is also left out")
        // A row may come back where the candidate places it only if the candidate leaves it out where it was (D7's plain scope).
        val kept = doc.rows.filter { it.line !in omit }.map { it.rowKey }.toHashSet()
        added.firstOrNull { it in kept }?.let { invalid("the inserted row $it is in the file already") }
        if (anchors.any { it in omit }) invalid("a row is inserted before a row the candidate leaves out")

        // The file, line by line: rows in their (re)ordered places, inserts before their anchor or after the SIM's last row.
        val rowAt = doc.rows.associateBy { it.line }
        val out = ArrayList<String>(doc.lines.size + added.size)
        for ((i, raw) in doc.lines.withIndex()) {
            val n = i + 1
            val src = placed[n] ?: n
            val r = rowAt[src]
            if (r != null) {
                val cr = doc.lines[src - 1].endsWith("\r")
                val indent = r.text.takeWhile { it == ' ' || it == '\t' }
                beforeRow[src]?.forEach { out += line(indent, it, cr) }
                if (src !in omit) out += rewrite[src]?.let { line(indent, it, cr) } ?: doc.lines[src - 1]
            } else {
                out += raw
            }
            afterLine[n]?.let { list ->
                val anchor = rowAt.getValue(n)
                list.forEach { out += line(anchor.text.takeWhile { c -> c == ' ' || c == '\t' }, it, raw.endsWith("\r")) }
            }
        }
        val bytes = out.joinToString("\n").toByteArray(Charsets.UTF_8)
        val again = ApnXml.parse(bytes)
        val expected = doc.rows.size - omit.size + added.size
        if (again.rows.size != expected) refuse("apn_index_mismatch", "the candidate set has ${again.rows.size} rows, expected $expected")
        if (again.version != 8 || again.version != doc.version) refuse("apn_version", "the candidate set's version is ${again.version}")
        val sha = Files.sha512(bytes)
        if (sha == full.sha512) invalid("the candidate changes nothing")
        val omittedKeys = omit.map { rowAt.getValue(it).rowKey }.sorted()
        val changedKeys = rewrite.values.map { ApnXml.rowKey(it) }.sorted()

        val after = ApnSelect.select(again.rows, sim)
        val afterKeys = after.map { it.rowKey }
        added.firstOrNull { it !in afterKeys }?.let { invalid("the inserted row $it would not be one of this SIM's rows") }
        if (afterKeys == selected.map { it.rowKey }) invalid("the candidate changes none of this SIM's rows")
        if (selected.any { it.serves("default") } && after.none { it.serves("default") }) refuse("sim_loses_internet", "the SIM would lose its internet row")
        if (selected.any { it.serves("ims") } && after.none { it.serves("ims") }) refuse("sim_loses_ims", "the SIM would lose its IMS row")
        if (emergencyPrint(selected) != emergencyPrint(after)) invalid("the SIM's emergency rows would change")
        // TelephonyProvider stores rows with equal unique fields as one: no new row may join an emergency row there.
        val afterEmergency = after.filter { it.serves("emergency") }.map { ApnLogic.key(it) }.toSet()
        val partnersBefore = selected.filter { !it.serves("emergency") && ApnLogic.key(it) in emergencyKeys }.map { it.rowKey }.toSet()
        after.firstOrNull { !it.serves("emergency") && ApnLogic.key(it) in afterEmergency && it.rowKey !in partnersBefore }?.let {
            invalid("row ${it.rowKey} would be stored as one row with an emergency row")
        }
        for ((n, other) in others.withIndex()) {
            if (ApnSelect.select(doc.rows, other).map { it.rowKey } != ApnSelect.select(again.rows, other).map { it.rowKey }) {
                invalid("the candidate would change the APN rows of another SIM in the phone (${n + 1} of ${others.size})")
            }
        }
        return Result(ApnLogic.Composed(bytes, sha, again, omittedKeys), omittedKeys, changedKeys, added.sorted(), touched, afterKeys)
    }
}
