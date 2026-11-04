package io.github.open_carrier_data.check.owner.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/*
 * Round 47, item 3: main goes straight to the phone. Phones cannot be pushed to, so "as soon as it
 * exists" means the next check of a 15-minute job. The job reads generated/ocd-dataset/manifest.json
 * at the branch name on the raw host, which costs no API call, and only a difference starts the
 * normal pull by SHA ([MainPin], [DatasetCheck]). The read is a hint and no data is taken from it, so
 * F1 to F5 and the never-moves-back rule stay as they are. This object decides; the job in OwnerWork.kt
 * only reads the phone and the network. It runs on a ROM with skip-notify ([DatasetStatus.skipsUnchanged]);
 * elsewhere every activation reloads every SIM, and the quiet job keeps its daily pull and its idle moment.
 */

/** The two digests of a dataset, as manifest.json names them. */
@Serializable
data class ManifestHint(val datasetDigest: String, val dataDigest: String)

/** An ETag, stored only once a pull for its digests finished; a commit still in validate gets none, so it is retried. */
@Serializable
data class ManifestMark(val etag: String, val hint: ManifestHint)

/** A wait that doubles: [failures] in a row, and nothing is tried before [untilMs]. */
@Serializable
data class MainWait(val failures: Int, val untilMs: Long)

/** The wait before the next pull for a digest that a finished pull did not bring. */
@Serializable
data class MainRetry(val datasetDigest: String, val wait: MainWait)

@Serializable
data class MainCheckState(
    val mark: ManifestMark? = null,
    /** The wait after a 403 or 429 from the raw host or the API. */
    val limit: MainWait? = null,
    val retry: MainRetry? = null,
    val lastMeteredCheckAtMs: Long? = null,
)

/** How the conditional read ended. [Fresh.hint] null: the answer names no digests this build can read. */
sealed interface ManifestRead {
    data object Same : ManifestRead

    data class Fresh(val etag: String?, val hint: ManifestHint?) : ManifestRead
}

/** The default network and what limits a pull on it. A null is unreadable and closes what it guards. */
data class MainNet(
    /** NET_CAPABILITY_NOT_METERED, not "Wi-Fi connected": a phone's hotspot is metered. Null: no network. */
    val unmetered: Boolean?,
    val roaming: Boolean?,
    val dataSaver: Boolean?,
)

/** What the phone holds: the pulled set, else the installed one, and whether the app has a main pin at all. */
data class HeldSet(val pinned: Boolean, val pulled: ManifestHint?, val installed: ManifestHint?) {
    fun has(hint: ManifestHint): Boolean = pinned && (pulled ?: installed) == hint
}

object MainCheck {
    /** WorkManager's floor. The flex window is the job's jitter: a run falls in the period's last 5 minutes. */
    const val PERIOD_MINUTES = 15L
    const val FLEX_MINUTES = 5L

    /** At the branch name, unlike every other file of a pull: a hint only. */
    const val MANIFEST_URL = "${DataSource.RAW}/${DataSource.BRANCH}/${DatasetFiles.MANIFEST_PATH}"

    val METERED_CHECK_EVERY_MS: Long = TimeUnit.HOURS.toMillis(6)
    val METERED_PULL_EVERY_MS: Long = TimeUnit.DAYS.toMillis(1)

    /** status.pull_again with nothing new on main: as often as the quiet job asked before round 47. */
    val PULL_AGAIN_EVERY_MS: Long = TimeUnit.HOURS.toMillis(6)

    /** A manifest that cannot be read must not stop updates: the old daily pull is the floor, and its checks decide. */
    val UNREADABLE_PULL_EVERY_MS: Long = TimeUnit.HOURS.toMillis(20)

    /** Shorter than the job's period, so the first retry is the next run. */
    val BACKOFF_FROM_MS: Long = TimeUnit.MINUTES.toMillis(10)
    val BACKOFF_MAX_MS: Long = TimeUnit.HOURS.toMillis(6)

    /** Retry-After is honoured past [BACKOFF_MAX_MS], but no wrong date or clock stops the updates for longer than this. */
    val RETRY_AFTER_MAX_MS: Long = TimeUnit.DAYS.toMillis(1)

    private val lenient = Json { ignoreUnknownKeys = true }

    /** Whether the job reads the manifest now. Off a metered network: no read while roaming or with Data Saver on, as no pull could follow. */
    fun mayRead(m: MainCheckState, net: MainNet, nowMs: Long): Boolean = when {
        net.unmetered == null -> false
        waiting(m.limit, nowMs) -> false
        net.unmetered -> true
        net.roaming != false || net.dataSaver != false -> false
        else -> elapsed(m.lastMeteredCheckAtMs, METERED_CHECK_EVERY_MS, nowMs)
    }

    /** The digests of a manifest, read leniently: a member this build does not know is F3's to refuse, on the pull by SHA. */
    fun hint(text: String): ManifestHint? = runCatching {
        val o = lenient.parseToJsonElement(text) as JsonObject
        fun digest(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(DatasetManifest.DIGEST::matches)
        ManifestHint(digest("dataset_digest") ?: return null, digest("data_digest") ?: return null)
    }.getOrNull()

    /** The digests behind [read]: a 304 means those of the stored [ManifestMark]. */
    private fun hintOf(read: ManifestRead, m: MainCheckState): ManifestHint? = when (read) {
        is ManifestRead.Same -> m.mark?.hint
        is ManifestRead.Fresh -> read.hint
    }

    /** The phone holds what main names now. Round 48: the week report's "last pulled main". */
    fun holds(read: ManifestRead, m: MainCheckState, held: HeldSet): Boolean = hintOf(read, m)?.let(held::has) == true

    /**
     * Whether the read starts a pull. A difference does, whatever the answer's code: a 304 whose stored digests
     * the phone does not hold is retried too. [pullAgain] (CarrierSettings asks) lifts only the daily limit on a
     * metered network, never roaming or Data Saver, as a pull abroad cannot be installed there and may cost money.
     */
    fun pullDue(read: ManifestRead, m: MainCheckState, held: HeldSet, net: MainNet, pullAgain: Boolean, lastPullAtMs: Long?, lastMeteredPullAtMs: Long?, nowMs: Long): Boolean {
        val hint = hintOf(read, m)
        val wanted = when {
            read is ManifestRead.Same && hint == null -> true
            hint == null -> elapsed(lastPullAtMs, UNREADABLE_PULL_EVERY_MS, nowMs)
            held.has(hint) -> false
            else -> !waiting(m.retry?.takeIf { it.datasetDigest == hint.datasetDigest }?.wait, nowMs)
        } || (pullAgain && elapsed(lastPullAtMs, PULL_AGAIN_EVERY_MS, nowMs))
        return when {
            !wanted || net.unmetered == null -> false
            net.unmetered -> true
            net.roaming != false || net.dataSaver != false -> false
            else -> pullAgain || elapsed(lastMeteredPullAtMs, METERED_PULL_EVERY_MS, nowMs)
        }
    }

    /**
     * After a run that read the manifest, with what the phone holds now. The ETag is stored only when the phone
     * holds its digests. A pull ([pulled]) that did not bring them (validate still running, a refused commit)
     * waits, doubling, so a commit that always fails does not cost a full download each run; a new digest
     * starts over. The limit ends here, not on the raw read alone, or an API that still refuses would never double.
     */
    fun settled(m: MainCheckState, read: ManifestRead, held: HeldSet, pulled: Boolean, nowMs: Long): MainCheckState {
        val hint = hintOf(read, m)
        val has = hint != null && held.has(hint)
        val mark = if (has && read is ManifestRead.Fresh && read.etag != null) ManifestMark(read.etag, hint!!) else m.mark
        val retry = when {
            has -> null
            hint == null || !pulled -> m.retry
            else -> MainRetry(hint.datasetDigest, next(m.retry?.takeIf { it.datasetDigest == hint.datasetDigest }?.wait, null, nowMs))
        }
        return m.copy(mark = mark, retry = retry, limit = if (has || pulled) m.limit?.takeIf { it.untilMs > nowMs } else m.limit)
    }

    /** A 403 or 429 from either host. A pull makes several calls, so a refusal within the running wait doubles nothing. */
    fun limited(prev: MainWait?, retryAfterMs: Long?, nowMs: Long): MainWait {
        if (prev == null || !waiting(prev, nowMs)) return next(prev, retryAfterMs, nowMs)
        return prev.copy(untilMs = maxOf(prev.untilMs, nowMs + (retryAfterMs ?: 0).coerceAtMost(RETRY_AFTER_MAX_MS)))
    }

    /** One failure more: the doubled wait up to [BACKOFF_MAX_MS], or Retry-After when that is longer. */
    fun next(prev: MainWait?, retryAfterMs: Long?, nowMs: Long): MainWait {
        val failures = (prev?.failures ?: 0) + 1
        val doubled = (BACKOFF_FROM_MS shl (failures - 1).coerceAtMost(20)).coerceAtMost(BACKOFF_MAX_MS)
        return MainWait(failures, nowMs + maxOf(doubled, (retryAfterMs ?: 0).coerceAtMost(RETRY_AFTER_MAX_MS)))
    }

    /** Retry-After in its two forms, seconds or an HTTP date, in milliseconds from now; null when absent or unreadable. */
    fun retryAfterMs(header: String?, nowMs: Long): Long? {
        val text = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return it.coerceIn(0, RETRY_AFTER_MAX_MS / 1000) * 1000 }
        return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs }.getOrNull()?.coerceAtLeast(0)
    }

    /** A wait far past what any rule sets is a clock that went back, and it is over. */
    private fun waiting(wait: MainWait?, nowMs: Long): Boolean = wait != null && nowMs < wait.untilMs && wait.untilMs - nowMs <= RETRY_AFTER_MAX_MS

    private fun elapsed(lastMs: Long?, everyMs: Long, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs >= everyMs || nowMs < lastMs

    /** The quiet job pulls only without skip-notify: its daily pull, unchanged. With it, this object's job does. */
    fun quietJobPulls(status: DatasetStatus?): Boolean = status?.skipsUnchanged != true

    /**
     * Whether the job queues the dataset step: a pulled set waits to go in, and the phone may take it. Each run
     * of the job is one moment to try; the step itself still asks [ActivationMoment]. An install that ended
     * goes again by the rules of [DatasetRetry], on the quiet job's pass.
     */
    fun activationWaits(pulled: PulledDataset?, install: InstallView?, status: DatasetStatus, stopped: Boolean, screenOn: Boolean?, roaming: Boolean?): Boolean {
        val digest = pulled?.datasetDigest ?: return false
        val ended = install?.datasetDigest == digest && install.final && install.state != InstallState.CANCELLED && install.state != InstallState.SUPERSEDED
        return status.skipsUnchanged && status.installed?.datasetDigest != digest && digest !in status.voiceLoss.blocked && !ended &&
            !status.voiceLoss.hold && !status.changesHalted.on && !stopped && screenOn == false && roaming == false
    }
}

/**
 * When a new dataset goes in on a ROM with skip-notify: the idle moment without the 2-minute wait after the
 * screen went off and without the battery condition. The screen off, no call and none in the last 10 minutes,
 * no media or VoIP audio, the emergency modes, the 60 minutes after an emergency call, the first hour after a
 * restart and roaming all stay, as they are [IdleMoment]'s own. Tests keep the whole idle moment.
 */
object ActivationMoment {
    val DROPPED = setOf(IdleBlock.SCREEN_ON_RECENTLY, IdleBlock.BATTERY_LOW)

    /** The first condition that does not hold; null: activate. */
    fun blocker(i: IdleInputs): IdleBlock? = IdleMoment.blockers(i).firstOrNull { it !in DROPPED }
}
