package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/*
 * `dumpsys carrier_config` and `cmd phone cc` output, ported from OCD Lab
 * (lab_parsers.py parse_cc_dump, effective_config, overrides_present,
 * cc_fingerprint, cc_diff, parse_cc_command). The dump is held in memory for
 * the revert proof only; it never reaches the disk. Only fingerprints, the
 * five switches and redacted differences are logged.
 */

/** CarrierConfigLoader's layers per phone, lowest first. */
object CcLayer {
    const val DEFAULT_APP = "mConfigFromDefaultApp"
    const val CARRIER_APP = "mConfigFromCarrierApp"
    const val PERSISTENT_OVERRIDE = "mPersistentOverrideConfigs"
    const val OVERRIDE = "mOverrideConfigs"
    val ALL = listOf(DEFAULT_APP, CARRIER_APP, PERSISTENT_OVERRIDE, OVERRIDE)
    val OVERRIDES = listOf(PERSISTENT_OVERRIDE, OVERRIDE)
}

/** One parsed dump: Android's defaults, every phone's layers (null stays null, an empty bundle is empty) and the no-SIM bundle. */
data class CcDump(
    val defaults: Map<String, String>,
    val phones: Map<Int, Map<String, Map<String, String>?>>,
    val noSim: Map<String, String>?,
    val platformPackage: String?,
) {
    /** What getConfigForSubId returns for the phone: defaults, then each layer in order. */
    fun effective(phone: Int?): Map<String, String> {
        val merged = LinkedHashMap(defaults)
        if (phone == null) {
            noSim?.let(merged::putAll)
            return merged
        }
        val layers = phones[phone].orEmpty()
        for (layer in CcLayer.ALL) layers[layer]?.let(merged::putAll)
        return merged
    }

    /** What the phone's own layers give, without the two override layers. */
    fun effectiveBelowOverrides(phone: Int): Map<String, String> {
        val merged = LinkedHashMap(defaults)
        val layers = phones[phone].orEmpty()
        for (layer in listOf(CcLayer.DEFAULT_APP, CcLayer.CARRIER_APP)) layers[layer]?.let(merged::putAll)
        return merged
    }

    /** Keys in a test-override layer of the phone, per layer; only layers that hold keys. */
    fun overrides(phone: Int): Map<String, Map<String, String>> =
        CcLayer.OVERRIDES.mapNotNull { layer -> phones[phone]?.get(layer)?.takeIf { it.isNotEmpty() }?.let { layer to it } }.toMap()

    /** A digest over every bundle. With [normalizeEmpty], a null layer and an empty bundle are the same (what clear-values leaves). */
    fun fingerprint(normalizeEmpty: Boolean): String {
        val text = StringBuilder()
        fun bundle(name: String, value: Map<String, String>?) {
            val shown = if (normalizeEmpty) value.orEmpty() else value
            text.append(name).append('=')
            if (shown == null) {
                text.append("null")
            } else {
                text.append('{')
                for ((k, v) in shown.toSortedMap()) text.append(k.length).append(':').append(k).append(v.length).append(':').append(v).append(';')
                text.append('}')
            }
            text.append('\n')
        }
        bundle("defaults", defaults)
        for ((phone, layers) in phones.toSortedMap()) for (layer in CcLayer.ALL) bundle("phone$phone.$layer", layers[layer])
        bundle("no_sim", noSim)
        text.append("platform_package=").append(platformPackage)
        return sha256(text.toString())
    }

    companion object {
        private val ENTRY = Regex("^([A-Za-z0-9_.\\-]+) =(?: (.*))?$")
        private val PHONE = Regex("^Phone Id = (\\d+)$")
        private val LAYER = Regex("^(mConfigFromDefaultApp|mConfigFromCarrierApp|mPersistentOverrideConfigs|mOverrideConfigs|mNoSimConfig) :(.*)$")

        fun parse(lines: Sequence<String>): CcDump {
            val defaults = LinkedHashMap<String, String>()
            val phones = sortedMapOf<Int, MutableMap<String, MutableMap<String, String>?>>()
            var noSim: MutableMap<String, String>? = null
            var platform: String? = null
            var current: MutableMap<String, String>? = null
            var phone: Int? = null
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("Default Values from CarrierConfigManager")) {
                    current = defaults
                    continue
                }
                val phoneHeader = PHONE.matchEntire(line)
                if (phoneHeader != null) {
                    val id = phoneHeader.groupValues[1].toInt()
                    phone = id
                    phones[id] = CcLayer.ALL.associateWith<String, MutableMap<String, String>?> { null }.toMutableMap()
                    current = null
                    continue
                }
                val layer = LAYER.matchEntire(line)
                if (layer != null) {
                    val bundle: MutableMap<String, String>? = if (layer.groupValues[2].trim() == "null") null else LinkedHashMap()
                    if (layer.groupValues[1] == "mNoSimConfig") {
                        noSim = bundle
                    } else {
                        phone?.let { phones.getValue(it)[layer.groupValues[1]] = bundle }
                    }
                    current = bundle
                    continue
                }
                if (line.startsWith("mPlatformCarrierConfigPackage=")) {
                    platform = line.substringAfter('=').ifEmpty { null }
                    current = null
                    continue
                }
                if (line.startsWith("mNumPhones=") || line.startsWith("CarrierConfigLoader local log=") || line.startsWith("Cached config files")) {
                    current = null
                    continue
                }
                val bundle = current ?: continue
                ENTRY.matchEntire(line)?.let { bundle[it.groupValues[1]] = it.groupValues[2] }
            }
            return CcDump(defaults, phones.mapValues { it.value.toMap() }, noSim, platform)
        }

        fun parse(text: String): CcDump = parse(text.lineSequence())

        private val SENT = Regex("^mHasSentConfigChange=\\[(.*)]$")

        /** CarrierConfigLoader's `mHasSentConfigChange`: per phone, whether the config for the SIM now in the slot was loaded and announced. */
        fun configSent(text: String): Map<Int, Boolean> {
            val line = text.lineSequence().map { it.trim() }.firstNotNullOfOrNull { SENT.matchEntire(it) } ?: return emptyMap()
            return line.groupValues[1].split(',').map { it.trim() }.withIndex()
                .mapNotNull { (i, v) -> v.toBooleanStrictOrNull()?.let { i to it } }.toMap()
        }

        /**
         * Whether CarrierSettings (the platform's carrier config package) has served its config for the SIM in [phone]:
         * its layer is there and the loader announced it. Null when the dump is unreadable.
         */
        fun served(text: String?, phone: Int): Boolean? {
            if (text == null || "Phone Id = " !in text) return null
            val layer = parse(text).phones[phone]?.get(CcLayer.DEFAULT_APP)
            return layer != null && configSent(text)[phone] == true
        }

        /** Every key that differs between two dumps, per bundle. Values of the five switches are kept; any other value is dropped. */
        fun diff(before: CcDump, after: CcDump): List<CcChange> {
            val changes = mutableListOf<CcChange>()
            fun compare(where: String, a: Map<String, String>?, b: Map<String, String>?) {
                if (a == null && b != null && b.isEmpty()) {
                    changes += CcChange(where, null, "null", "empty")
                    return
                }
                if (a != null && a.isEmpty() && b == null) {
                    changes += CcChange(where, null, "empty", "null")
                    return
                }
                val x = a.orEmpty()
                val y = b.orEmpty()
                for (key in (x.keys + y.keys).toSortedSet()) {
                    if (x[key] != y[key]) changes += CcChange(where, key, shown(key, x[key]), shown(key, y[key]))
                }
            }
            compare("defaults", before.defaults, after.defaults)
            for (phone in (before.phones.keys + after.phones.keys).toSortedSet()) {
                for (layer in CcLayer.ALL) compare("phone$phone.$layer", before.phones[phone]?.get(layer), after.phones[phone]?.get(layer))
            }
            compare("no_sim", before.noSim, after.noSim)
            return changes
        }

        private fun shown(key: String, value: String?): String? = when {
            value == null -> null
            key in KeyGuard.ALLOWED -> value
            else -> "<changed>"
        }
    }
}

/** One difference between two dumps. `key` null means a whole layer went from null to empty or back. */
@Serializable
data class CcChange(val where: String, val key: String?, val before: String?, val after: String?)

/** The outcome of one `cmd phone cc` run, in the words TelephonyShellCommand prints. */
@Serializable
data class CcResult(val ok: Boolean, val reason: String?, val previous: String? = null, val new: String? = null) {
    companion object {
        private val PREVIOUS = Regex("Previous value: \\n?\\s*(\\S+)\\s+\\w+\\s+(.*)")
        private val NEW = Regex("New value: \\n?\\s*(\\S+)\\s+\\w+\\s+(.*)")

        fun parse(output: String, exitCode: Int): CcResult {
            val text = output.trim()
            val reason = when {
                "Permission denied" in text -> "permission_denied"
                "No valid subscription found" in text -> "no_valid_subscription"
                "Invalid phoneId" in text -> "invalid_phone_id"
                "is not a valid key" in text -> "unknown_key"
                "cannot be invoked by shell" in text -> "shell_uid_refused"
                "Exception" in text -> "exception"
                exitCode != 0 -> "exit_$exitCode"
                else -> null
            }
            return CcResult(
                ok = reason == null,
                reason = reason,
                previous = PREVIOUS.find(text)?.groupValues?.get(2)?.trim(),
                new = NEW.find(text)?.groupValues?.get(2)?.trim(),
            )
        }
    }
}

/** The revert proof (lab Session.verify): the dump after a revert against the snapshot before the first change. */
@Serializable
data class RevertProof(
    val ok: Boolean,
    val fingerprintBefore: String,
    val fingerprintAfter: String,
    val fingerprintNormalizedEqual: Boolean,
    val effectiveValuesEqual: Boolean,
    val keptValuesHold: Boolean,
    val differences: List<CcChange>,
    val unexpectedDifferences: List<CcChange>,
) {
    companion object {
        /**
         * The only differences accepted: in the slot's two override layers, a
         * null layer that is now empty (what clear-values leaves), or a key in
         * [keep] (values the owner build holds on purpose, re-applied after
         * the clear). Every effective value outside [keep] must equal the
         * snapshot's, and every kept key must hold its value. A key in
         * [dropped] was kept at the snapshot and is let go now: it must be
         * back at the phone's own value (the layers below the overrides).
         */
        fun of(before: CcDump, after: CcDump, phone: Int, keep: Map<String, Boolean>, dropped: Set<String> = emptySet()): RevertProof {
            val diff = CcDump.diff(before, after)
            val layers = CcLayer.OVERRIDES.map { "phone$phone.$it" }.toSet()
            val unexpected = diff.filterNot { it.where in layers && (it.key == null || it.key in keep || it.key in dropped) }
            val a = before.effective(phone)
            val own = before.effectiveBelowOverrides(phone)
            val b = after.effective(phone)
            val valuesEqual = (a.keys + b.keys).filter { it !in keep }.all { key -> (if (key in dropped) own[key] else a[key]) == b[key] }
            val keptHold = keep.all { (key, value) -> b[key]?.trim()?.lowercase() == value.toString() }
            return RevertProof(
                ok = unexpected.isEmpty() && valuesEqual && keptHold,
                fingerprintBefore = before.fingerprint(normalizeEmpty = false),
                fingerprintAfter = after.fingerprint(normalizeEmpty = false),
                fingerprintNormalizedEqual = before.fingerprint(true) == after.fingerprint(true),
                effectiveValuesEqual = valuesEqual,
                keptValuesHold = keptHold,
                differences = diff,
                unexpectedDifferences = unexpected,
            )
        }
    }
}

internal fun sha256(text: String): String =
    "sha256:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun String?.asBool(): Boolean? = when (this?.trim()?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
}
