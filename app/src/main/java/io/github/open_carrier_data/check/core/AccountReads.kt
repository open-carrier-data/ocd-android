package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * OCD rule round 42, items 4 and 5: the two account-state reads, as pure logic.
 *
 * Item 4, balance USSD: a per-brand table of exact free read-only codes, sent only
 * when the SIM's own provider name (EF_SPN) identifies the brand (a follow-up to the
 * ruling: a network code alone named the wrong code for host-only brands), a refusal
 * of every dangerous form, detection of a menu reply (never automated), and a text
 * match of a balance or volume, masked before it is kept or shown.
 *
 * Item 5, carrier SMS: a per-network allowlist of the carrier's own senders, and
 * a classification of the carrier's plan messages into a fixed class set. Only
 * the class is kept here; the reader keeps the class, the network and the time,
 * never the text, the sender or a number.
 *
 * Neither holds the phone. Long digit runs are masked everywhere ([Mask]).
 */

/** Masks runs of digits that could be a phone number, IMSI or ICCID; a short balance figure stays. */
object Mask {
    private val LONG_RUN = Regex("\\d{7,}")

    fun digits(text: String): String = LONG_RUN.replace(text) { "#".repeat(it.value.length) }
}

/** The account-state read and, for a balance, the masked figure to show. */
@Serializable
data class AccountRead(val state: AccountState, val balance: String? = null, val note: String? = null)

/**
 * Item 4, one row of the balance-code table: a brand, the home networks its SIMs carry, the exact
 * SIM provider names (EF_SPN, as TelephonyManager.getSimOperatorName reads it) that identify its SIMs,
 * and its documented free read-only balance code (null: the brand documents none). [spnSource] and
 * [codeSource] name where each comes from; a row needs both.
 */
data class BalanceBrand(
    val brand: String,
    val networks: Set<String>,
    val spns: Set<String>,
    val code: String?,
    val spnSource: String,
    val codeSource: String,
    /** For a brand without a code: where its balance shows instead. */
    val elsewhere: String? = null,
)

/** What the balance read does for one SIM: send the brand's code, or send nothing and say why. */
sealed interface BalanceChoice {
    data class Send(val brand: BalanceBrand, val code: String) : BalanceChoice

    /** Nothing is sent. [reason] is the record; [hint] names the documented code to dial by hand, or where the balance shows. */
    data class NotSent(val reason: String, val hint: String) : BalanceChoice {
        val line: String get() = "$reason; $hint"
    }
}

/**
 * Item 4, keyed by brand. A code is sent only when the SIM's own provider name (EF_SPN) positively
 * names one brand of this table on the SIM's home network, matched exactly (case and spelling as
 * the sources give them; only surrounding blanks are trimmed). A host network never picks a code
 * by itself: 26203/26207 carry ALDI TALK (*100#) beside o2 (*101#), 26201 carries Kaufland mobil
 * (*144#) and fraenk (no code) beside Telekom and congstar, and 26202 carries LIDL Connect, otelo and
 * CallYa beside Vodafone. An unknown or ambiguous brand gets nothing sent and a hint with the
 * codes the research documents for that network, for the person to dial by hand.
 *
 * The SPN strings come from OCD's carrier identity (AOSP's carrier_list carrier IDs, Samsung OMC,
 * LineageOS overlays) and, for LIDL Connect, from the owner's own SIM; the codes from
 * S20_Project/research_notes/Telling carrier failure causes apart/germany_plan_channels.md, section 1.
 * No source names an EF_SPN for an o2-network brand (o2, Blau, ALDI TALK, fonic, netzclub), for
 * Kaufland mobil or for otelo, so none of them is identified and none gets a code sent.
 *
 * A reply is classified once ([classify]); a menu reply is never answered.
 */
object BalanceUssd {
    /** The record when no code is sent because no single brand of the table matched the SIM. */
    const val NOT_IDENTIFIED = "balance code not sent: brand not identified"

    /** The balance-code table. Small on purpose: a brand joins only with an SPN source and a code source. */
    val BRANDS: List<BalanceBrand> = listOf(
        BalanceBrand(
            brand = "Telekom",
            networks = setOf("26201", "26206"),
            spns = setOf("Telekom.de", "T-Mobile D", "Business", "Privat", "T-Campus"),
            code = "*100#",
            spnSource = "AOSP carrier_list carrier ID 3 (Telekom); Samsung OMC (OCD carrier identity de.telekom)",
            codeSource = "teltarif Prepaid-Guthaben abfragen (Apr 2024); Finanztip Handy-Guthaben (Mar 2025)",
        ),
        BalanceBrand(
            brand = "congstar",
            networks = setOf("26201", "26206"),
            spns = setOf("congstar", "congstar.de"),
            code = "*100#",
            spnSource = "AOSP carrier_list carrier ID 2092 (congstar); Samsung OMC; LineageOS device overlays (OCD carrier identity de.congstar)",
            codeSource = "teltarif Prepaid-Guthaben abfragen (Apr 2024); GIGA congstar Datenvolumen (balance only)",
        ),
        BalanceBrand(
            brand = "fraenk",
            networks = setOf("26201", "26206"),
            spns = setOf("fraenk"),
            code = null,
            spnSource = "AOSP carrier_list carrier ID 2557 (fraenk); Samsung OMC (OCD carrier identity de.fraenk)",
            codeSource = "smartweb fraenk Datenvolumen: app only, no code",
            elsewhere = "fraenk shows the balance only in its app",
        ),
        BalanceBrand(
            brand = "Vodafone",
            networks = setOf("26202"),
            spns = setOf("Vodafone", "Vodafone.de", "vodafone.de"),
            code = "*100#",
            spnSource = "AOSP carrier_list carrier ID 25 (Vodafone)",
            codeSource = "teltarif Prepaid-Guthaben abfragen (Apr 2024, CallYa); Finanztip Handy-Guthaben (Mar 2025); Handyhase Vodafone USSD codes (2023)",
        ),
        BalanceBrand(
            brand = "LIDL Connect",
            networks = setOf("26202"),
            spns = setOf("LIDL Connect"),
            code = "*100#",
            spnSource = "read on the owner's S20 and A50 (OCD rule round 7 notes; OCD Lab README 2026-10-18); OCD Lab call_targets.json by_spn",
            codeSource = "LIDL Connect price lists of 2016 and 2019 (free); teltarif (Apr 2024); GIGA (11.09.2025); sent by hand on the S20 2026-10-30",
        ),
    )

    /**
     * Per host network: the codes the research documents for the brands it carries, named for the
     * person to dial by hand when the brand is not identified. Never sent by the app.
     */
    private val HOST_HINTS: Map<String, String> = mapOf(
        "26201" to TELEKOM_HOSTED,
        "26206" to TELEKOM_HOSTED,
        "26202" to "dial it by hand: Vodafone CallYa, LIDL Connect and otelo *100#",
        "26203" to O2_HOSTED,
        "26207" to O2_HOSTED,
    )

    private const val TELEKOM_HOSTED =
        "dial it by hand: Telekom, congstar, Penny Mobil, ja!mobil, NORMA Connect and EDEKA smart *100#; Kaufland mobil *144#; fraenk has no code (app only)"
    private const val O2_HOSTED =
        "dial it by hand: o2 prepaid, Blau, fonic and netzclub *101#; ALDI TALK and Ay Yildiz *100#"
    private const val NO_HINT = "no balance code is documented for this network"

    /** The hint for a SIM whose brand is not identified on [mccmnc]. */
    fun hint(mccmnc: String): String = HOST_HINTS[mccmnc] ?: NO_HINT

    /** The table rows that match this SIM: its home network and its exact provider name. */
    fun brands(mccmnc: String, spn: String?): List<BalanceBrand> {
        val name = spn?.trim().orEmpty()
        if (name.isEmpty()) return emptyList()
        return BRANDS.filter { mccmnc in it.networks && name in it.spns }
    }

    /** What to do for the SIM with home network [mccmnc] and provider name [spn]. */
    fun choose(mccmnc: String, spn: String?): BalanceChoice {
        // No match, or more than one row: the brand is not identified, whatever the codes would be.
        val brand = brands(mccmnc, spn).singleOrNull() ?: return BalanceChoice.NotSent(NOT_IDENTIFIED, hint(mccmnc))
        val code = brand.code
            ?: return BalanceChoice.NotSent("balance code not sent: ${brand.brand} has no balance code", brand.elsewhere ?: hint(mccmnc))
        if (!allowed(mccmnc, spn, code)) return BalanceChoice.NotSent(NOT_IDENTIFIED, hint(mccmnc))
        return BalanceChoice.Send(brand, code)
    }

    /** The only shape a balance code may have: a star, three digits, a hash. */
    private val SHAPE = Regex("^\\*[0-9]{3}#$")

    /**
     * Codes never sent even if a table listed them: a credit advance (*123#), UltraCard set-up
     * (*133#), switching incoming calls on or off (*124#, o2) and the own number (*135#).
     */
    private val DANGEROUS = Regex("^\\*(123|124|133|135)#$")

    /**
     * True when [code] is the exact code of the one brand this SIM is identified as, has the plain
     * balance-query shape (so no second '*' carrying a top-up, voucher or booking argument, and no
     * '#'-first service code) and is none of the dangerous codes.
     */
    fun allowed(mccmnc: String, spn: String?, code: String): Boolean {
        val brand = brands(mccmnc, spn).singleOrNull() ?: return false
        if (brand.code == null || code != brand.code) return false
        if (!SHAPE.matches(code)) return false
        if (code.count { it == '*' } > 1) return false
        if (DANGEROUS.matches(code)) return false
        return true
    }

    private val MENU = Regex("(?i)(wählen sie|bitte wählen|1[ .)]|drücken sie|select one|menü)")
    private val ZERO = Regex("(?i)(0[,.]00|kein guthaben|aufgebraucht|0\\s?MB\\b|verbraucht)")
    private val AMOUNT = Regex("(\\d{1,4})[,.](\\d{2})\\s?(EUR|€)")
    private val LOW = Regex("(?i)(guthaben|balance)")

    /**
     * Classifies one USSD reply. A menu reply is recorded as "the balance is one step down" and never
     * answered. A balance text is masked and read as blocked (at or near zero), low, or ok.
     */
    fun classify(reply: String): AccountRead {
        if (MENU.containsMatchIn(reply) && AMOUNT.find(reply) == null) {
            return AccountRead(AccountState.UNKNOWN, note = "menu: the balance is one step down; pick it by hand")
        }
        if (ZERO.containsMatchIn(reply)) {
            return AccountRead(AccountState.BLOCKED, balance = AMOUNT.find(reply)?.value?.let(Mask::digits))
        }
        val amount = AMOUNT.find(reply) ?: return AccountRead(AccountState.UNKNOWN)
        val euros = amount.groupValues[1].toIntOrNull() ?: 0
        val cents = amount.groupValues[2].toIntOrNull() ?: 0
        val total = euros * 100 + cents
        val masked = Mask.digits(amount.value)
        return when {
            total <= 15 -> AccountRead(AccountState.BLOCKED, masked) // 0.10 EUR: no data option can be active (the LIDL case).
            total < 100 && LOW.containsMatchIn(reply) -> AccountRead(AccountState.LOW, masked)
            else -> AccountRead(AccountState.OK, masked)
        }
    }
}

/** Item 5. The carrier's own senders per network, and the classification of their plan messages. */
object CarrierSms {
    /** One class of carrier plan message, with the account state it implies. Only the class is kept. */
    @Serializable
    enum class PlanClass(val wire: String, val account: AccountState) {
        OPTION_PAUSED("option_paused", AccountState.BLOCKED),
        VOLUME_USED_UP("volume_used_up", AccountState.BLOCKED),
        CREDIT_EMPTY("credit_empty", AccountState.BLOCKED),
        NEARLY_USED_UP("nearly_used_up", AccountState.LOW),
        CREDIT_LOW("credit_low", AccountState.LOW),
        OPTION_ACTIVE("option_active", AccountState.OK),
        TOP_UP_DONE("top_up_done", AccountState.OK),
    }

    /**
     * Per home network: the senders the carrier uses for plan messages (alphanumeric names or short
     * codes). Only a message from one of these is ever classified. Seeds; widened as sources confirm.
     */
    val SENDERS: Map<String, Set<String>> = mapOf(
        "26201" to setOf("Telekom", "CongStar", "congstar"),
        "26202" to setOf("Vodafone", "LIDL Connect", "LIDLConnect", "otelo"),
        "26203" to setOf("o2", "Blau", "Aldi Talk", "AldiTalk"),
        "26207" to setOf("o2", "Blau"),
    )

    fun fromSender(mccmnc: String, sender: String): Boolean =
        SENDERS[mccmnc].orEmpty().any { it.equals(sender.trim(), ignoreCase = true) }

    private val RULES: List<Pair<Regex, PlanClass>> = listOf(
        Regex("(?i)(pausiert|ruhend|deaktiviert|paused|gesperrt)") to PlanClass.OPTION_PAUSED,
        Regex("(?i)(datenvolumen (ist )?(auf)?gebraucht|volumen verbraucht|used up|aufgebraucht)") to PlanClass.VOLUME_USED_UP,
        Regex("(?i)(kein guthaben|guthaben (ist )?(0|aufgebraucht|leer)|credit.*(0|empty))") to PlanClass.CREDIT_EMPTY,
        Regex("(?i)(80\\s?%|90\\s?%|fast aufgebraucht|nearly|bald aufgebraucht)") to PlanClass.NEARLY_USED_UP,
        Regex("(?i)(guthaben niedrig|low (credit|balance)|laden sie .* auf)") to PlanClass.CREDIT_LOW,
        Regex("(?i)(aufgeladen|aufladung|top.?up|guthaben.*gutgeschrieben)") to PlanClass.TOP_UP_DONE,
        Regex("(?i)(option.{0,15}aktiv|aktiviert|wieder (verfügbar|aktiv)|active again|freigeschaltet)") to PlanClass.OPTION_ACTIVE,
    )

    /** The class of one carrier message, or null when none matches. The raw text is never kept by the caller. */
    fun classify(body: String): PlanClass? = RULES.firstOrNull { it.first.containsMatchIn(body) }?.second
}
