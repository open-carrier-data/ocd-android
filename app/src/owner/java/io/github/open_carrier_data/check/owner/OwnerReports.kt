package io.github.open_carrier_data.check.owner

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.open_carrier_data.check.owner.core.AutoReport
import io.github.open_carrier_data.check.owner.core.DataSource
import io.github.open_carrier_data.check.owner.core.IssuePoster
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.PostResult
import io.github.open_carrier_data.check.owner.core.RecordReport
import io.github.open_carrier_data.check.owner.core.SourceReview
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.Settled
import io.github.open_carrier_data.check.owner.core.SettledLevel
import io.github.open_carrier_data.check.owner.core.TokenForm
import io.github.open_carrier_data.check.owner.core.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/**
 * The owner's fine-grained GitHub token for automatic reports (OCD round 29): Issues read and
 * write on open-carrier-data/open-carrier-data only, created by the owner and entered on his
 * phone. It lives only in this app's credential-encrypted storage (`no_backup`, readable only
 * after the first unlock; the app has no backup), encrypted with an AES-GCM key in Android
 * Keystore that never leaves it. It is never logged, never in the state or a report, and
 * "Remove token" deletes the file and the key. Revoking it on GitHub works as well.
 */
object OwnerToken {
    private const val FILE = "report-token.bin"
    private const val KEY_ALIAS = "ocd-report-token"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE)

    fun isSet(context: Context): Boolean = file(context).isFile

    /** Saves [text] if it has a fine-grained token's form; the reason it was not saved otherwise. Never echoes the text. */
    fun save(context: Context, text: String): String? {
        TokenForm.problem(text)?.let { return it }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            val sealed = cipher.iv.size.toByte().let { byteArrayOf(it) } + cipher.iv + cipher.doFinal(text.trim().toByteArray(Charsets.UTF_8))
            val target = file(context)
            val partial = File(target.parentFile, "$FILE.partial")
            partial.writeBytes(sealed)
            if (!partial.renameTo(target)) throw IOException("could not store the token")
            null
        }.getOrElse { "The token could not be stored on this phone" }
    }

    /** The token, or null when none is set or it cannot be read (a removed key, a damaged file). */
    fun read(context: Context): String? = runCatching {
        val sealed = file(context).takeIf { it.isFile }?.readBytes() ?: return null
        val ivSize = sealed[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false) ?: return null, GCMParameterSpec(GCM_TAG_BITS, sealed, 1, ivSize))
        cipher.doFinal(sealed, 1 + ivSize, sealed.size - 1 - ivSize).toString(Charsets.UTF_8)
    }.getOrNull()

    fun remove(context: Context) {
        file(context).delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

/**
 * Creates one issue on OCD's public repository with the owner's token (REST API
 * `POST /repos/open-carrier-data/open-carrier-data/issues`). The token goes only into the
 * Authorization header, to api.github.com over HTTPS, and never into a message, the log or
 * an exception text.
 */
class GitHubIssues(private val token: String) : IssuePoster {
    override fun post(title: String, body: String): PostResult = post(title, body, AutoReport.LABEL)

    /**
     * One issue with [label]: the pointer report's (`ocd-check-report`), a measured record's for the
     * data side's owner-only intake (`ocd-device-record`, round 30), or a source review's.
     */
    fun post(title: String, body: String, label: String): PostResult {
        require(label in LABELS) { "label" }
        val payload = JsonObject(
            mapOf(
                "title" to JsonPrimitive(title),
                "body" to JsonPrimitive(body),
                "labels" to JsonArray(listOf(JsonPrimitive(label))),
            ),
        ).toString().toByteArray(Charsets.UTF_8)
        val connection = try {
            URL(API).openConnection() as HttpsURLConnection
        } catch (e: IOException) {
            return PostResult.Failed("no connection")
        }
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "ocd-check-owner")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_CREATED -> {
                    val text = connection.inputStream.use { it.readBytes().take(MAX_ANSWER).toByteArray().decodeToString() }
                    val number = runCatching { Json.parseToJsonElement(text).jsonObject["number"]?.jsonPrimitive?.intOrNull }.getOrNull()
                    if (number != null) PostResult.Created(number) else PostResult.Failed("GitHub's answer had no issue number")
                }
                403, 429 -> if (connection.getHeaderField("x-ratelimit-remaining") == "0" || connection.getHeaderField("retry-after") != null) {
                    PostResult.Failed("GitHub's rate limit (HTTP $code)")
                } else {
                    PostResult.Refused(code)
                }
                401, 404, 410, 422 -> PostResult.Refused(code)
                else -> PostResult.Failed("HTTP $code")
            }
        } catch (e: IOException) {
            // The message of an I/O error never holds a header; only its class is kept.
            PostResult.Failed("no connection (${e.javaClass.simpleName})")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val API = "https://api.github.com/repos/${DataSource.REPOSITORY}/issues"
        private const val MAX_ANSWER = 256 * 1024
        val LABELS = setOf(AutoReport.LABEL, RecordReport.LABEL, SourceReview.LABEL)
    }
}

/** The automatic reports step: what is due, sent with the owner's token, recorded. */
object OwnerAutoReports {
    /** The ROM build as reports name it (OCD round 29): Build.ID and the build's UTC time in seconds. */
    fun publicBuild(): String? = Settled.publicBuild(Build.ID.orEmpty(), Build.TIME)

    /** The settled results for [run] on this phone now ([Settled.forRun]). */
    fun settled(context: Context, state: OwnerState, run: RunResult): Map<String, SettledLevel> =
        Settled.forRun(run, state, OwnerBasis.current(context, state).rom, publicBuild(), System.currentTimeMillis())

    /** Sends what is due; returns one line for the log, or null when nothing was due. */
    fun run(context: Context, store: FileStateStore): String? {
        // Round 31 (SM11): only the owner build sends, with the owner's token; a ROM build sends nothing by itself.
        if (!OwnerTester.policy(store.read()).sendsWithToken) return null
        if (!OwnerToken.isSet(context)) return null
        val state = store.read()
        if (state.autoRefusedAtMs != null) return null
        val now = System.currentTimeMillis()
        val due = AutoReport.plan(state, { OwnerData.callsTested(context, state) }, { settled(context, state, it) }, now)
        // Round 30: measured records (one issue each) and source reviews, for the data side's owner-only intake.
        val records = RecordReport.due(state.pendingRecords.mapNotNull { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }, state.recordsSent, now)
        val reviews = state.pendingReviews.filter { it.key !in state.reviewsSent }
        if (due.isEmpty() && records.isEmpty() && reviews.isEmpty()) return null
        val token = OwnerToken.read(context) ?: return "automatic report: the stored token cannot be read; enter it again".also {
            store.update { s -> s.copy(autoLine = "The stored token can't be read. Enter it again") }
        }
        val poster = GitHubIssues(token)
        var line: String? = null
        for (item in due) {
            val result = poster.post(item.issue.title, item.issue.body)
            store.update { s -> AutoReport.after(s, item, result, System.currentTimeMillis()) }
            line = when (result) {
                is PostResult.Created -> "sent #${result.number}"
                is PostResult.Refused -> return "refused (HTTP ${result.code})"
                is PostResult.Failed -> throw IOException(result.why)
            }
        }
        for (record in records) {
            val issue = RecordReport.issue(record)
            when (val result = poster.post(issue.title, issue.body, RecordReport.LABEL)) {
                is PostResult.Created -> {
                    store.update { s -> s.copy(recordsSent = RecordReport.record(s.recordsSent, record, System.currentTimeMillis(), result.number), autoLine = "Record sent as #${result.number}") }
                    line = "record ${issue.recordKey} sent #${result.number}"
                }
                is PostResult.Refused -> {
                    store.update { s -> s.copy(autoRefusedAtMs = System.currentTimeMillis(), autoLine = "GitHub refused the token (HTTP ${result.code}). Enter a new one") }
                    return "refused (HTTP ${result.code})"
                }
                is PostResult.Failed -> throw IOException(result.why)
            }
        }
        for (review in reviews) {
            when (val result = poster.post(review.title, review.body, SourceReview.LABEL)) {
                is PostResult.Created -> {
                    store.update { s -> s.copy(reviewsSent = (s.reviewsSent + review.key).takeLast(SourceReview.MAX * 4), pendingReviews = s.pendingReviews.filterNot { it.key == review.key }) }
                    line = "source review #${result.number}"
                }
                is PostResult.Refused -> return "refused (HTTP ${result.code})"
                is PostResult.Failed -> throw IOException(result.why)
            }
        }
        return line
    }
}
