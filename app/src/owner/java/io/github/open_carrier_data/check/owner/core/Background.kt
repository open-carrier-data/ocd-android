package io.github.open_carrier_data.check.owner.core

/**
 * The owner build's background as plain functions: what the screen and the
 * Settings entry say about it, and when the owner hears of it at all.
 */
object QuietSchedule {
    /** The schedule line on the owner screen. */
    fun line(on: Boolean, registered: Boolean, state: OwnerState, nowMs: Long, format: (Long) -> String): String = when {
        !on -> "off"
        !registered -> "on, not registered yet (registered at the next boot or when this screen opens)"
        else -> buildString {
            append("on: a light check whenever the phone lies unused; trials at night (02:00 to 05:00) on the charger, screen off an hour, one feature a night")
            state.lastCheckAtMs?.let { append("; last check ").append(format(it)) }
            state.onOnlyUntilMs?.takeIf { it > nowMs }?.let { append("; only on values until ").append(format(it)).append(" (new ROM build or data release)") }
        }
    }
}

/** The summary line under "Open Carrier Data" in Settings > Network & internet: the status and the last result. */
object SettingsSummary {
    fun line(state: OwnerState, privileged: Boolean, format: (Long) -> String): String {
        val parts = mutableListOf<String>()
        if (state.stop != null) return "Stopped after a stop event; open to review"
        val kept = state.overrides.count { it.hold == Hold.KEPT } + state.apns.count { it.hold == Hold.KEPT }
        val trials = state.overrides.count { it.hold == Hold.TRIAL } + state.apns.count { it.hold == Hold.TRIAL }
        parts += when {
            trials > 0 -> "Test running"
            kept > 0 -> "$kept kept"
            else -> "Phone's own carrier settings"
        }
        val result = state.lastResult
        parts += when {
            result != null -> "${format(result.atMs)}: ${result.text}"
            state.runs.isNotEmpty() -> "last test ${format(state.runs.last().startedMs)}"
            else -> "not tested yet"
        }
        if (!privileged) parts += "no privileged access"
        return parts.joinToString(" · ")
    }
}

/** A notification the owner gets: only when a value changed on the phone or something failed. */
data class Notice(val title: String, val text: String, val failed: Boolean)

/**
 * When the owner hears of the background (the owner, 2026-10-20: "so the
 * user does not notice it that much"): never for routine work (a check, a
 * trial that changed nothing, a re-apply after boot, a deferral, an
 * interruption); a normal notification when a kept value came or went, or
 * when something failed (a stop event, a baseline that did not come back).
 * A live trial or keep shows only as the quiet notification with "Revert all".
 */
object Notices {
    private fun kept(state: OwnerState): Set<Triple<Int, String, Boolean>> =
        state.overrides.filter { it.hold == Hold.KEPT }.map { Triple(it.slot, it.key, it.value) }.toSet()

    private fun keptApns(state: OwnerState): Set<Pair<String, String>> =
        state.apns.filter { it.hold == Hold.KEPT }.map { it.apnKey to it.label }.toSet()

    /** The changes between two states, as lines. */
    fun changes(before: OwnerState, after: OwnerState): List<String> = buildList {
        for ((slot, key, value) in kept(after) - kept(before)) add("${label(key)} ${if (value) "on" else "off"} kept on slot ${slot + 1}")
        for ((slot, key, _) in kept(before) - kept(after)) {
            if (kept(after).none { it.first == slot && it.second == key }) add("${label(key)} back to the phone's own value on slot ${slot + 1}")
        }
        for ((_, label) in keptApns(after) - keptApns(before)) add("APN $label kept as the preferred APN")
        for ((key, label) in keptApns(before) - keptApns(after)) if (keptApns(after).none { it.first == key }) add("APN $label removed")
    }

    fun failures(before: OwnerState, after: OwnerState): List<String> = buildList {
        if (after.stop != null && after.stop != before.stop) add("stopped: ${after.stop.take(120)}")
        val newFailures = after.entries.filter { e -> e.failures > 0 && before.entries.none { it.sim == e.sim && it.entry == e.entry && it.atMs == e.atMs } }
        for (e in newFailures) add("${e.entry} did not finish cleanly (${e.outcome})")
    }

    /** Null for routine work. */
    fun of(before: OwnerState, after: OwnerState): Notice? {
        val failed = failures(before, after)
        val changed = changes(before, after)
        if (failed.isEmpty() && changed.isEmpty()) return null
        return Notice(
            title = if (failed.isNotEmpty()) "Open Carrier Data: needs a look" else "Open Carrier Data: carrier settings changed",
            text = (failed + changed).joinToString("; "),
            failed = failed.isNotEmpty(),
        )
    }

    private fun label(key: String): String = OwnerFeature.ofKey(key)?.label ?: key
}

/** The one line a finished run leaves for the Settings entry. */
object RunSummary {
    fun line(run: RunResult, notice: Notice?): String {
        if (notice != null) return notice.text
        val what = (run.features.map { it.feature.label } + if (run.apns.any { it.status == "inserted" }) listOf("APN rows") else emptyList()).distinct()
        val refused = run.features.mapNotNull { it.refused }.filterNot { it.startsWith(RunInterrupted.INTERRUPTED) }
        return buildString {
            append(if (run.background) "night test" else "test")
            if (what.isNotEmpty()) append(" of ").append(what.joinToString())
            append(if (refused.isEmpty()) ": nothing changed" else ": nothing changed (${refused.distinct().joinToString()})")
        }
    }
}

/**
 * Retries a start that can fail for a moment (Shizuku's user service: a bind
 * right after the previous service process exited loses its record, "unable
 * to find token"). [block] gets the attempt number from 1 and returns null on
 * a failure; between attempts [pause] runs (the caller unbinds and waits).
 */
suspend fun <T : Any> retrying(attempts: Int, pause: suspend (attempt: Int) -> Unit, block: suspend (attempt: Int) -> T?): T? {
    for (attempt in 1..attempts) {
        block(attempt)?.let { return it }
        if (attempt < attempts) pause(attempt)
    }
    return null
}
