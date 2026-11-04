package io.github.open_carrier_data.check.owner.core

/*
 * The owner build's background as plain functions: what the notifications
 * say about it, and when the owner hears of it at all.
 */

/** A notification the owner gets: only when a value changed on the phone or something failed. */
data class Notice(val title: String, val text: String, val failed: Boolean)

/**
 * When the owner hears of the background (the owner, 2026-10-20: "so the
 * user does not notice it that much"): never for routine work (a check, a
 * trial that changed nothing, a re-apply after boot, a deferral, an
 * interruption); a normal notification when a kept value came or went, or
 * when something failed (a stop event, a baseline that did not come back).
 * A live trial or keep shows only as the quiet notification with "Stop test"
 * or "Undo changes". Titles never repeat the app's name: the notification
 * header shows it.
 */
object Notices {
    const val CHANGED_TITLE = "Carrier settings changed"
    const val FAILED_TITLE = "A test failed — your own settings are back"
    const val PAUSED_TITLE = "Changes paused: a setting didn't go back"

    private fun kept(state: OwnerState): Set<Triple<Int, String, Boolean>> =
        state.overrides.filter { it.hold == Hold.KEPT }.map { Triple(it.slot, it.key, it.value) }.toSet()

    private fun keptApns(state: OwnerState): Set<String> =
        state.apns.filter { it.hold == Hold.KEPT }.map { it.apnKey }.toSet()

    /** The changes between two states, one plain item each ("VoLTE switched on", "access point name added"). */
    fun changes(before: OwnerState, after: OwnerState): List<String> = buildList {
        for ((_, key, value) in kept(after) - kept(before)) add("${label(key)} switched ${if (value) "on" else "off"}")
        for ((slot, key, _) in kept(before) - kept(after)) {
            if (kept(after).none { it.first == slot && it.second == key }) add("${label(key)} back to your own setting")
        }
        repeat((keptApns(after) - keptApns(before)).size) { add("access point name added") }
        repeat((keptApns(before) - keptApns(after)).size) { add("access point name removed") }
    }

    /** What failed, for the run's record and the log (technical). */
    fun failures(before: OwnerState, after: OwnerState): List<String> = buildList {
        if (after.stop != null && after.stop != before.stop) add("stopped: ${after.stop.take(120)}")
        for (e in newFailures(before, after)) add("${e.entry} did not finish cleanly (${e.outcome})")
    }

    private fun newFailures(before: OwnerState, after: OwnerState): List<EntryRecord> =
        after.entries.filter { e -> e.failures > 0 && before.entries.none { it.sim == e.sim && it.entry == e.entry && it.atMs == e.atMs } }

    /** The notification for changes, the items joined: "VoLTE switched on, access point name added". */
    fun changed(items: List<String>): Notice = Notice(CHANGED_TITLE, plainList(items), failed = false)

    /** Null for routine work. */
    fun of(before: OwnerState, after: OwnerState): Notice? {
        if (after.stop != null && after.stop != before.stop) return Notice(PAUSED_TITLE, "See the log in Details before resuming", failed = true)
        val failed = newFailures(before, after).map { e -> e.feature?.label ?: "Access point name" }.distinct()
        if (failed.isNotEmpty()) return Notice(FAILED_TITLE, joinAnd(failed), failed = true)
        val changed = changes(before, after)
        return if (changed.isEmpty()) null else changed(changed)
    }

    private fun plainList(items: List<String>): String {
        val counted = items.groupingBy { it }.eachCount().map { (item, n) ->
            if (n > 1 && item.startsWith("access point name ")) "$n access point names ${item.substringAfterLast(' ')}" else item
        }
        return counted.joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    private fun label(key: String): String = OwnerFeature.ofKey(key)?.label ?: key
}

/** The one line a finished run leaves in the state and the log: what changed or failed, else what it tested. */
object RunSummary {
    fun line(run: RunResult): String {
        if (run.failures.isNotEmpty() || run.changes.isNotEmpty()) return (run.failures + run.changes).joinToString("; ")
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
