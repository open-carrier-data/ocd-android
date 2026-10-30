package io.github.open_carrier_data.check.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * OCD's rule round 32, part A and the MMS override: what operators, brands and MVNOs state on
 * their own pages, published as generated/operators/statements.json with the carrier-identity
 * table (generated/operators/carrier-identity.json), both CC0. The app shows them, never serves
 * them, and never turns one into a value of its own:
 *
 * - an offer reads "the operator states it offers X (read on <date>); whether your tariff includes
 *   it is not checked"; an end reads "the operator states X ended on <date>". Never "works".
 * - "Wi-Fi calling abroad" is the operator's word, shown apart from OCD's wifi_calling_roaming.
 * - A host-only brand (no selector any source states, so no phone tells its SIMs from the host's)
 *   appears only under "brands on this network", never as a statement about the SIM.
 * - mms_ended_networks lists every network whose history holds an admitted network-wide MMS end,
 *   lapsed ones included: the MMS-ended list (X6) the app reads, with its own bundled
 *   mms-ended.json as a shrink-only fallback for the networks the published list does not cover yet.
 */

@Serializable
data class StatementSource(
    val url: String,
    val publisher: String,
    val kind: String,
    val role: String,
    val date: String? = null,
    @SerialName("read_on") val readOn: String,
    @SerialName("passage_sha256") val passageSha256: String,
)

@Serializable
data class StatementLapse(val reason: String, val since: String)

@Serializable
data class StatementSecondCheck(val on: String, val verdict: String)

@Serializable
data class StatementEvent(val on: String, val event: String, val status: String)

@Serializable
data class OperatorStatement(
    val id: String,
    @SerialName("identity_key") val identityKey: String,
    val operator: String,
    val country: String,
    val service: String,
    val status: String,
    @SerialName("previous_status") val previousStatus: String? = null,
    val lapsed: StatementLapse? = null,
    val scope: String,
    val mccmnc: List<String>,
    @SerialName("mmsc_hosts") val mmscHosts: List<String> = emptyList(),
    @SerialName("host_only") val hostOnly: Boolean,
    @SerialName("last_day") val lastDay: String? = null,
    @SerialName("first_day_without") val firstDayWithout: String? = null,
    @SerialName("checked_at") val checkedAt: String,
    @SerialName("swept_at") val sweptAt: String,
    val paraphrase: String,
    @SerialName("second_check") val secondCheck: StatementSecondCheck? = null,
    @SerialName("sets_value") val setsValue: Boolean,
    val sources: List<StatementSource>,
    val history: List<StatementEvent>,
)

@Serializable
data class MmsEndedNetwork(
    val mccmnc: String,
    val statement: String,
    @SerialName("first_day_without") val firstDayWithout: String,
)

@Serializable
data class StatementCounts(val statements: Int, val lapsed: Int, @SerialName("sets_value") val setsValue: Int)

@Serializable
data class StatementsFile(
    @SerialName("schema_version") val schemaVersion: Int,
    val description: String,
    val license: String,
    @SerialName("display_rule") val displayRule: String,
    val countries: List<String>,
    val counts: StatementCounts,
    @SerialName("mms_ended_networks") val mmsEndedNetworks: List<MmsEndedNetwork>,
    val statements: List<OperatorStatement>,
) {
    fun byId(id: String): OperatorStatement? = statements.firstOrNull { it.id == id }
}

/** One SIM selector of the identity table, exactly as a source states it, with the OCD profile it maps to. */
@Serializable
data class IdentitySelector(
    val families: List<String> = emptyList(),
    val match: JsonObject = JsonObject(emptyMap()),
    @SerialName("profile_id") val profileId: String? = null,
)

@Serializable
data class IdentityEntry(
    val country: String,
    @SerialName("host_networks") val hostNetworks: List<String>,
    @SerialName("host_only") val hostOnly: Boolean,
    val key: String,
    val kind: String,
    val name: String,
    @SerialName("operator_key") val operatorKey: String? = null,
    val selectors: List<IdentitySelector> = emptyList(),
)

@Serializable
data class IdentityFile(
    @SerialName("schema_version") val schemaVersion: Int,
    val entries: List<IdentityEntry>,
)

/** A statements or identity file this build does not read (F3), or one that is not what its schema says. */
class OperatorsFileRefused(message: String) : Exception(message)

object OperatorFiles {
    const val DIR = "generated/operators"
    const val STATEMENTS = "statements.json"
    const val IDENTITY = "carrier-identity.json"

    /** Each file's path on main and its cap; today 57 KB and 60 KB. */
    val FILES: Map<String, Long> = linkedMapOf(STATEMENTS to 4L * 1024 * 1024, IDENTITY to 4L * 1024 * 1024)

    fun path(name: String): String = "$DIR/$name"

    /** The schema_version both files are written in (schemas/operator-statements.schema.json, carrier-identity.schema.json). */
    const val SCHEMA = 1

    private val json = Json { ignoreUnknownKeys = true }
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val CODE = Regex("[0-9]{5,6}")
    val SERVICES = setOf("mms", "volte", "wifi_calling", "wifi_calling_abroad", "volte_roaming", "esim", "5g_sa", "vonr")
    val STATUSES = setOf("offered", "not_offered", "ended", "unknown")

    /**
     * statements.json read strictly enough to show: schema_version 1 (F3), CC0, every statement with
     * a known service and status, network codes of five or six digits and plain dates, and every
     * MMS-ended network naming an admitted statement on that code. Anything else refuses the file.
     */
    fun statements(text: String): StatementsFile {
        val file = decode("statements.json") { json.decodeFromString(StatementsFile.serializer(), text) }
        if (file.schemaVersion != SCHEMA) throw OperatorsFileRefused("statements.json is schema_version ${file.schemaVersion}; this build reads $SCHEMA")
        if (file.license != "CC0-1.0") throw OperatorsFileRefused("statements.json is not CC0-1.0")
        for (s in file.statements) {
            if (s.service !in SERVICES || s.status !in STATUSES) throw OperatorsFileRefused("statement ${s.id.take(80)} has an unknown service or status")
            if (s.mccmnc.isEmpty() || s.mccmnc.any { !CODE.matches(it) }) throw OperatorsFileRefused("statement ${s.id.take(80)} names a network code of another shape")
            if (listOfNotNull(s.checkedAt, s.sweptAt, s.lastDay, s.firstDayWithout, s.lapsed?.since).any { !DATE.matches(it) }) {
                throw OperatorsFileRefused("statement ${s.id.take(80)} has a date of another shape")
            }
        }
        for (n in file.mmsEndedNetworks) {
            if (!CODE.matches(n.mccmnc) || !DATE.matches(n.firstDayWithout)) throw OperatorsFileRefused("an MMS-ended network has another shape")
            val s = file.byId(n.statement) ?: throw OperatorsFileRefused("MMS-ended network ${n.mccmnc} names no statement")
            if (s.service != "mms" || n.mccmnc !in s.mccmnc) throw OperatorsFileRefused("MMS-ended network ${n.mccmnc} names a statement about something else")
        }
        return file
    }

    /** carrier-identity.json: schema_version 1 (F3), every entry with network codes of five or six digits. */
    fun identity(text: String): IdentityFile {
        val file = decode("carrier-identity.json") { json.decodeFromString(IdentityFile.serializer(), text) }
        if (file.schemaVersion != SCHEMA) throw OperatorsFileRefused("carrier-identity.json is schema_version ${file.schemaVersion}; this build reads $SCHEMA")
        for (e in file.entries) if (e.hostNetworks.any { !CODE.matches(it) }) throw OperatorsFileRefused("identity entry ${e.key.take(60)} names a network code of another shape")
        return file
    }

    private fun <T> decode(name: String, block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw OperatorsFileRefused("$name cannot be read (cut short or another shape)")
    } catch (e: IllegalArgumentException) {
        throw OperatorsFileRefused("$name cannot be read (cut short or another shape)")
    }
}

/** One network on the MMS-ended list (X6) the app reads, and where it comes from. */
data class MmsEndedEntry(
    val mccmnc: String,
    val operator: String,
    /** The operator's last day with MMS when the statement names it, else null. */
    val lastDay: String?,
    /** The first day without MMS. */
    val firstDayWithout: String?,
    /** The published statement's id, or null for the app's own bundled list. */
    val statement: String?,
) {
    val published: Boolean get() = statement != null

    /** "Telefónica states MMS ended on 2026-06-29", or the bundled list's words. */
    val plain: String
        get() = when {
            !published -> "OCD's earlier list records $operator ending it" + (firstDayWithout?.let { " on $it" } ?: "")
            lastDay != null -> "$operator states MMS ended on $lastDay"
            firstDayWithout != null -> "$operator states MMS ended; it is off since $firstDayWithout"
            else -> "$operator states MMS ended"
        }
}

/** A network the app's bundled mms-ended.json records (the shrink-only fallback). */
data class BundledMmsEnded(val operator: String, val mccmnc: List<String>, val ended: String)

object MmsEndedList {
    /**
     * The MMS-ended networks (round 32, MMS override rule 6): every network the published list
     * names, then every network of the app's own bundled list that the published list does not
     * cover yet. A network leaves the bundled list only once the published list covers it, so
     * nothing drops out of X6 between the two.
     */
    fun of(published: StatementsFile?, bundled: List<BundledMmsEnded>): List<MmsEndedEntry> {
        val out = LinkedHashMap<String, MmsEndedEntry>()
        for (n in published?.mmsEndedNetworks.orEmpty()) {
            if (n.mccmnc in out) continue
            val s = published?.byId(n.statement)
            out[n.mccmnc] = MmsEndedEntry(n.mccmnc, s?.operator ?: n.statement, s?.lastDay, n.firstDayWithout, n.statement)
        }
        for (b in bundled) for (code in b.mccmnc) {
            if (code !in out) out[code] = MmsEndedEntry(code, b.operator, null, b.ended, null)
        }
        return out.values.toList()
    }

    fun find(list: List<MmsEndedEntry>, mccmnc: String): MmsEndedEntry? = list.firstOrNull { it.mccmnc == mccmnc }

    /** The bundled networks the published list covers: they may leave mms-ended.json (it only shrinks). */
    fun coveredByPublished(published: StatementsFile, bundled: List<BundledMmsEnded>): Set<String> {
        val listed = published.mmsEndedNetworks.map { it.mccmnc }.toSet()
        return bundled.flatMap { it.mccmnc }.filter { it in listed }.toSet()
    }
}

/** What the app shows for one SIM: the statements about its operator or brand, and the brands on its network. */
data class StatementDisplay(
    /** The identity table's name for the SIM's operator or brand, when the table maps the SIM's profile. */
    val about: String?,
    val lines: List<String>,
    /** Host-only brands on the SIM's network ("brands on this network"), never statements about the SIM. */
    val brandsOnNetwork: List<String>,
) {
    val empty: Boolean get() = lines.isEmpty() && brandsOnNetwork.isEmpty()

    companion object {
        /** The fixed sentence under every list of statements. */
        const val NOTE = "What operators state on their own pages, read by OCD. A statement says what the operator sells, not what your tariff includes or what this phone supports; OCD shows it and never uses it as a setting, except an MMS end."
    }
}

object StatementWords {
    /** The service in plain words. Wi-Fi calling abroad is the operator's own word, not OCD's wifi_calling_roaming value. */
    fun service(service: String): String = when (service) {
        "mms" -> "MMS"
        "volte" -> "VoLTE (calls over 4G)"
        "wifi_calling" -> "Wi-Fi calling"
        "wifi_calling_abroad" -> "Wi-Fi calling abroad"
        "volte_roaming" -> "VoLTE while roaming"
        "esim" -> "eSIM"
        "5g_sa" -> "5G standalone (5G SA)"
        "vonr" -> "VoNR (calls over 5G)"
        else -> service
    }

    /**
     * One statement in part A's words (rule 5): an offer with its read date and "whether your
     * tariff includes it is not checked"; an end with its day; never "works". Null for an unknown
     * status without a lapse, which states nothing.
     */
    fun line(s: OperatorStatement): String? {
        val what = service(s.service)
        return when (s.status) {
            "offered" -> "The operator states it offers $what (read on ${s.checkedAt}); whether your tariff includes it is not checked"
            "ended" -> when {
                s.lastDay != null -> "The operator states $what ended on ${s.lastDay}"
                s.firstDayWithout != null -> "The operator states $what ended; it is off since ${s.firstDayWithout}"
                else -> "The operator states $what ended"
            }
            "not_offered" -> "The operator states it does not offer $what (read on ${s.checkedAt})"
            else -> s.lapsed?.let { "$what: no current statement; the operator's last one lapsed on ${it.since}" }
        }
    }
}

object StatementView {
    private val ORDER = listOf("mms", "volte", "vonr", "wifi_calling", "wifi_calling_abroad", "volte_roaming", "5g_sa", "esim")

    /**
     * The identity entry for a SIM whose OCD profiles, generic to specific, are [profileIds]: the
     * entry (never a host-only one) whose selector maps to the most specific of them; else the
     * operator whose plain selector names the SIM's network code. Null when the table has none.
     */
    fun entryFor(identity: IdentityFile?, mccmnc: String, profileIds: List<String>): IdentityEntry? {
        val entries = identity?.entries.orEmpty().filter { !it.hostOnly }
        for (id in profileIds.asReversed()) {
            entries.firstOrNull { e -> e.selectors.any { it.profileId == id } }?.let { return it }
        }
        return entries.firstOrNull { e ->
            e.kind == "operator" && mccmnc in e.hostNetworks && e.selectors.any { sel -> sel.match.keys == setOf("mccmnc") && sel.match["mccmnc"].toString().contains("\"$mccmnc\"") }
        }
    }

    /**
     * The statements to show for a SIM on [mccmnc] ([profileIds]: its OCD profiles, generic to
     * specific): those of its operator or brand from the identity table, and every network-wide
     * statement on its network code (an operator's MMS end reaches every SIM on that network); a
     * host-only brand's statements never. Then the host-only brands on the network, by name.
     */
    fun forSim(statements: StatementsFile?, identity: IdentityFile?, mccmnc: String, profileIds: List<String>): StatementDisplay {
        if (statements == null) return StatementDisplay(null, emptyList(), emptyList())
        val entry = entryFor(identity, mccmnc, profileIds)
        val about = statements.statements.filter { s ->
            !s.hostOnly && mccmnc in s.mccmnc && (s.identityKey == entry?.key || s.scope == "network")
        }
        val lines = about
            .sortedWith(compareBy<OperatorStatement>({ if (it.identityKey == entry?.key) 0 else 1 }, { ORDER.indexOf(it.service).let { i -> if (i < 0) ORDER.size else i } }, { it.id }))
            .mapNotNull { s ->
                StatementWords.line(s)?.let { line ->
                    // A network-wide statement by the host operator of an MVNO's SIM names who states it.
                    if (entry != null && s.identityKey != entry.key) line.replaceFirst("The operator", s.operator) else line
                }
            }
            .distinct()
        val brands = identity?.entries.orEmpty().filter { it.hostOnly && mccmnc in it.hostNetworks }.map { it.name }.distinct().sorted()
        return StatementDisplay(entry?.name ?: about.firstOrNull()?.operator, lines, brands)
    }
}
