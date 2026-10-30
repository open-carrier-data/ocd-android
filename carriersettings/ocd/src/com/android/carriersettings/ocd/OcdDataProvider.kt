/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.InputStream

/**
 * The dataset's only way in (spec 6.1, round 31 R31.11): `content://com.android.carriersettings.ocddata`,
 * method calls only, exported and directBootAware. Round 29's pinned writer, with the writer a
 * build parameter (SM5):
 * - the OCD app of this ROM's build, holding the signature|knownSigner permission pinned to its
 *   certificate, with its UID mapping to that one package with that certificate (checked on
 *   every call): every method of [Protocol.OWNER_METHODS];
 * - adb (UID 0 or 2000): [Protocol.ADB_METHODS] only, never a write of data;
 * - anyone else: SecurityException.
 * A refusal is an answer (`ok` false, `error`, `reason`), never an exception.
 */
class OcdDataProvider : ContentProvider() {
    private enum class Caller { OWNER, ADB }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("method calls only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("method calls only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("method calls only")

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: throw IllegalStateException("no context")
        val uid = Binder.getCallingUid()
        val caller = when {
            uid == ROOT_UID || uid == SHELL_UID -> Caller.ADB
            context.checkCallingPermission(Protocol.PERMISSION) == PackageManager.PERMISSION_GRANTED && OcdAndroid.callerIsOwner(context, uid) -> Caller.OWNER
            else -> throw SecurityException("only this build's OCD app writes the dataset; adb may read and clear it")
        }
        val token = Binder.clearCallingIdentity()
        return try {
            val allowed = if (caller == Caller.ADB) Protocol.ADB_METHODS else Protocol.OWNER_METHODS
            when {
                method !in Protocol.OWNER_METHODS + Protocol.ADB_METHODS -> refusal("unknown_method", "unknown method $method")
                method !in allowed -> refusal("forbidden_for_caller", "$method is not for ${if (caller == Caller.ADB) "adb" else "the owner app"}")
                else -> answer(handle(context, caller, method, arg, extras ?: Bundle()))
            }
        } catch (e: Refusal) {
            refusal(e.code, e.reason, e.waitingFor)
        } catch (e: Refused) {
            refusal(e.code, e.detail)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.e(OcdAndroid.TAG, "$method failed", e)
            refusal("storage", e.toString())
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun handle(context: Context, caller: Caller, method: String, arg: String?, extras: Bundle): Map<String, Any?> {
        val engine = OcdAndroid.engine(context)
        return when (method) {
            Protocol.STATUS -> engine.status()
            Protocol.STAGE_DATASET -> stage(engine, extras)
            Protocol.INSTALL_STATUS -> engine.installStatus(arg ?: extras.getString(Protocol.INSTALL_ID) ?: extras.getString(Protocol.JOB_ID))
            Protocol.ACTIVATE -> engine.activate(arg ?: extras.getString(Protocol.INSTALL_ID))
            Protocol.CANCEL -> {
                engine.cancel(arg ?: extras.getString(Protocol.INSTALL_ID))
                emptyMap()
            }
            Protocol.EXPLAIN -> {
                val sub = if (extras.containsKey(Protocol.SUB_ID)) extras.getInt(Protocol.SUB_ID) else arg?.toIntOrNull() ?: throw Refusal("bad_request", "no sub_id")
                mapOf(Protocol.EXPLAIN_JSON to engine.explain(sub, extras.getString(Protocol.DATASET)))
            }
            Protocol.PUT_RECORD -> engine.putRecord(extras.getString(Protocol.RECORD))
            Protocol.REMOVE_RECORD -> {
                engine.removeRecord(arg ?: extras.getString(Protocol.RECORD_KEY))
                emptyMap()
            }
            Protocol.LIST_RECORDS -> {
                val sub = if (extras.containsKey(Protocol.SUB_ID)) extras.getInt(Protocol.SUB_ID) else arg?.toIntOrNull()
                mapOf(Protocol.RECORDS to Json.write(engine.listRecords(sub)))
            }
            Protocol.APPLY_APN_RECORDS -> mapOf(Protocol.JOB_ID to engine.applyApnRecords())
            Protocol.SET_TESTER -> {
                val on = when (arg ?: extras.getString("on")) {
                    "on" -> true
                    "off" -> false
                    else -> throw Refusal("bad_request", "arg must be on or off")
                }
                engine.setTester(on, extras.getString(Protocol.REASON).orEmpty())
            }
            Protocol.VOICE_LOSS_HOLD -> engine.voiceLossHold(extras.getString(Protocol.REASON) ?: arg.orEmpty())
            Protocol.VOICE_LOSS_RELEASE -> engine.voiceLossRelease(extras.getString(Protocol.OUTCOME) ?: arg, extras.getString(Protocol.REASON).orEmpty())
            Protocol.EMERGENCY_DEFAULT_SET -> {
                val sub = if (extras.containsKey(Protocol.SUB_ID)) extras.getInt(Protocol.SUB_ID) else arg?.toIntOrNull()
                mapOf(Protocol.JOB_ID to engine.emergencyDefaultSet(sub))
            }
            Protocol.EMERGENCY_DEFAULT_RESTORE -> {
                val keep = if (extras.containsKey(Protocol.KEEP)) extras.getBoolean(Protocol.KEEP) else arg == "keep"
                mapOf(Protocol.JOB_ID to engine.emergencyDefaultRestore(keep))
            }
            Protocol.APN_CANDIDATE_SET -> {
                val sub = if (extras.containsKey(Protocol.SUB_ID)) extras.getInt(Protocol.SUB_ID) else arg?.toIntOrNull()
                mapOf(Protocol.JOB_ID to engine.apnCandidateSet(sub, extras.getString(Protocol.CANDIDATE)))
            }
            Protocol.APN_CANDIDATE_RESTORE -> mapOf(Protocol.JOB_ID to engine.apnCandidateRestore())
            Protocol.STOP -> {
                engine.stop(extras.getString(Protocol.REASON) ?: arg.orEmpty())
                mapOf("changes_halted" to true)
            }
            Protocol.RESUME -> {
                engine.resume()
                mapOf("changes_halted" to false)
            }
            Protocol.CHECK -> mapOf(Protocol.RESULT to engine.check(if (caller == Caller.ADB) "requested over adb" else "requested by the OCD app"))
            Protocol.CLEAR_DATASET -> {
                engine.clearDataset()
                emptyMap()
            }
            else -> throw Refusal("unknown_method", "unknown method $method")
        }
    }

    /** `stage_dataset`: the pin, the file names, and one read-only FD per file; the FDs are closed here. */
    private fun stage(engine: Engine, extras: Bundle): Map<String, Any?> {
        val names = runCatching { (Json.parse(extras.getString(Protocol.FILES) ?: "[]") as List<*>).map { it as String } }
            .getOrElse { throw Refusal("bad_request", "files is not a JSON array of names") }
        val streams = LinkedHashMap<String, InputStream>()
        try {
            for (key in extras.keySet()) {
                if (!key.startsWith(Protocol.FD_PREFIX)) continue
                val fd = extras.getParcelable(key, ParcelFileDescriptor::class.java) ?: throw Refusal("bad_request", "$key is not a file descriptor")
                streams[key.removePrefix(Protocol.FD_PREFIX)] = ParcelFileDescriptor.AutoCloseInputStream(fd)
            }
            return engine.stage(extras.getString(Protocol.PIN), names, streams, extras.getString(Protocol.F5_READ), extras.getString(Protocol.FIRST_USE))
        } finally {
            streams.values.forEach { runCatching { it.close() } }
        }
    }

    private fun answer(map: Map<String, Any?>): Bundle = Bundle().apply {
        putBoolean(Protocol.OK, true)
        for ((k, v) in map) {
            when (v) {
                null -> Unit
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                is String -> putString(k, v.take(Protocol.MAX_ANSWER_CHARS))
                is Map<*, *>, is List<*> -> putString(k, Json.write(v).take(Protocol.MAX_ANSWER_CHARS))
                else -> putString(k, v.toString())
            }
        }
    }

    private fun refusal(code: String, reason: String, waitingFor: String? = null): Bundle = Bundle().apply {
        putBoolean(Protocol.OK, false)
        putString(Protocol.ERROR, code)
        putString(Protocol.REASON, reason.take(240))
        waitingFor?.let { putString(Protocol.WAITING_FOR, it) }
    }

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
    }
}

/**
 * The composed APN file for system_server's ApnDbInstallReceiver (spec 7.3):
 * `content://com.android.carriersettings.ocdapn/apns/<sha512-12>.xml`. Not exported; read
 * only, for the system UID and this app only.
 */
class OcdApnProvider : ContentProvider() {
    private val name = Regex("^[0-9a-f]{12}\\.xml$")

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/xml"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = Binder.getCallingUid()
        if (uid != SYSTEM_UID && uid != android.os.Process.myUid()) throw SecurityException("only the system reads the APN file")
        if (mode != "r") throw SecurityException("read only")
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "apns" || !name.matches(segments[1])) throw java.io.FileNotFoundException("no such APN file")
        val file = java.io.File(OcdAndroid.engine(context!!).apnFile(segments[1]).path)
        if (!file.isFile) throw java.io.FileNotFoundException("no such APN file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        const val SYSTEM_UID = 1000
    }
}
