/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

/**
 * The install interface between the OCD owner app and OcdCarrierSettings (spec section 6):
 * `content://com.android.carriersettings.ocddata`, method calls only. The owner app's job
 * builds against the same names.
 */
object Protocol {
    const val AUTHORITY = "com.android.carriersettings.ocddata"
    const val APN_AUTHORITY = "com.android.carriersettings.ocdapn"
    const val PERMISSION = "com.android.carriersettings.permission.WRITE_OCD_DATASET"
    /** The owner flavor of the OCD app, the writer's default (OCD_WRITER_PACKAGE). */
    const val OWNER_PACKAGE = "io.github.open_carrier_data.check.owner"
    /** OCD's plain app flavor, the writer on another ROM (round 31 SM5). */
    const val PLAIN_PACKAGE = "io.github.open_carrier_data.check"
    /**
     * Round 31: safe mode and set_emergency_gate are gone; the tester, the voice-loss hold and the
     * default emergency APN test are new. Round 32's APN candidate methods are additive and keep
     * protocol 3: an app that knows 3 and not them gets `unknown_method` from an older build, and
     * an OCD app 0.4.2 reads no protocol above 3 (it would stop handing data over).
     */
    const val VERSION = 3

    // Methods (6.3).
    const val STATUS = "status"
    const val STAGE_DATASET = "stage_dataset"
    const val INSTALL_STATUS = "install_status"
    const val ACTIVATE = "activate"
    const val CANCEL = "cancel"
    const val EXPLAIN = "explain"
    const val PUT_RECORD = "put_record"
    const val REMOVE_RECORD = "remove_record"
    const val LIST_RECORDS = "list_records"
    const val APPLY_APN_RECORDS = "apply_apn_records"
    const val STOP = "stop"
    const val RESUME = "resume"
    const val CHECK = "check"
    const val CLEAR_DATASET = "clear_dataset"
    const val SET_TESTER = "set_tester"
    const val VOICE_LOSS_HOLD = "voice_loss_hold"
    const val VOICE_LOSS_RELEASE = "voice_loss_release"
    const val EMERGENCY_DEFAULT_SET = "emergency_default_set"
    const val EMERGENCY_DEFAULT_RESTORE = "emergency_default_restore"
    /** Round 32 (D8 and D7 rule 4; rounds 33, 34 and 36): one SIM's temporary APN candidate set, and its restore. */
    const val APN_CANDIDATE_SET = "apn_candidate_set"
    const val APN_CANDIDATE_RESTORE = "apn_candidate_restore"

    /**
     * What adb (UID 0 or 2000) may call: reads, clear_dataset, resume and check; never a write
     * of data (stage_dataset, activate, put_record, apply_apn_records, the tester, the hold, the
     * test sets and their restores).
     */
    val ADB_METHODS = setOf(STATUS, INSTALL_STATUS, EXPLAIN, LIST_RECORDS, CLEAR_DATASET, RESUME, CHECK)
    val OWNER_METHODS = setOf(
        STATUS, STAGE_DATASET, INSTALL_STATUS, ACTIVATE, CANCEL, EXPLAIN, PUT_RECORD, REMOVE_RECORD, LIST_RECORDS,
        APPLY_APN_RECORDS, STOP, RESUME, CHECK, SET_TESTER, VOICE_LOSS_HOLD, VOICE_LOSS_RELEASE, EMERGENCY_DEFAULT_SET, EMERGENCY_DEFAULT_RESTORE,
        APN_CANDIDATE_SET, APN_CANDIDATE_RESTORE,
    )

    // Extras.
    const val OK = "ok"
    const val ERROR = "error"
    const val REASON = "reason"
    const val PIN = "pin"
    const val FILES = "files"
    const val FD_PREFIX = "fd."
    const val F5_READ = "f5_read"
    const val FIRST_USE = "first_use"
    const val KEEP = "keep"
    /** `apn_candidate_set`'s candidate: the app's ApnSetCandidate as JSON. */
    const val CANDIDATE = "candidate"
    const val OUTCOME = "outcome"
    const val INSTALL_ID = "install_id"
    const val JOB_ID = "job_id"
    const val STATE = "state"
    const val CHECKS = "checks"
    const val CHANGES = "changes"
    const val APN = "apn"
    const val WAITING_FOR = "waiting_for"
    const val SUB_ID = "sub_id"
    const val DATASET = "dataset"
    const val RECORD = "record"
    const val RECORD_KEY = "record_key"
    const val RECORDS = "records"
    const val EFFECT = "effect"
    const val BY = "by"
    const val SERVING = "serving"
    const val RESULT = "result"
    const val EXPLAIN_JSON = "explain"
    const val PROTOCOL = "protocol"

    /** Answers stay below 256 KiB (6.2). */
    const val MAX_ANSWER_CHARS = 120 * 1024
}

/** Thrown by the engine for the provider: answered as `ok=false` with the code (6.5) and a reason. */
class Refusal(val code: String, val reason: String, val waitingFor: String? = null) : Exception(reason)
