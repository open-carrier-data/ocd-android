package io.github.open_carrier_data.check.core

import kotlinx.serialization.Serializable

/*
 * OCD rule round 42, items 4 and 5: the two account-state reads, as pure logic.
 *
 * Item 4, balance USSD: a per-network allowlist of exact free read-only codes, a
 * refusal of every dangerous form, detection of a menu reply (never automated),
 * and a text match of a balance or volume, masked before it is kept or shown.
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
 * Item 4. An allowlisted balance code per network, and the classification of its one reply. A menu
 * reply is never answered: the session ends and the read says the balance is one step down.
 */
object BalanceUssd {
    /** Per home network (MCC+MNC): the exact free, read-only balance code, with a two-source "free" confirmation. */
    val CODES: Map<String, String> = mapOf(
        "26201" to "*100#", // Telekom
        "26202" to "*100#", // Vodafone (and LIDL Connect, Vodafone platform)
        "26203" to "*101#", // Telefónica / o2
        "26207" to "*101#", // Telefónica / o2
    )

    /** Forms that are never sent: a second '*' (a booking or top-up argument), a credit advance, or a service code. */
    private val DANGEROUS = Regex("^\\*(123|133)#$")

    /** True when [code] is the exact allowlisted code for [mccmnc] and carries no dangerous form. */
    fun allowed(mccmnc: String, code: String): Boolean {
        val listed = CODES[mccmnc] ?: return false
        if (code != listed) return false
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
