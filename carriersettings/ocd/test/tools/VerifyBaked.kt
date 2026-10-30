/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.io.File
import kotlin.system.exitProcess

/**
 * The phone's own reader over a baked dataset, off the phone (ocd/test/verify-baked.sh, run by
 * S20_Project scripts/ocd-pin-main.py before it commits a pin): checks 1 to 10 as the build's
 * dataset, the serving index written and read back, and bundles for a few SIMs. Round 31: no
 * carrier-less record is served (the S20's values come from the device override XML, which
 * check_build.sh checks in the image), and group A serves the Google lane or a read value only.
 * Never built into the app.
 *
 *   VerifyBaked DATASET_DIR OCD_BUILD_JSON [PLATFORM_APN_VERSION]
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: VerifyBaked DATASET_DIR OCD_BUILD_JSON [PLATFORM_APN_VERSION]")
        exitProcess(2)
    }
    val dir = File(args[0])
    val cell = BuildCell.parse(File(args[1]).readBytes())?.first ?: run {
        System.err.println("ocd-build.json is unreadable")
        exitProcess(2)
    }
    val verifier = Verifier(null, { DefaultType.Absent }, args.getOrNull(2)?.toInt() ?: 8, null, emptyList())
    val dataset = try {
        verifier.verify(dir, role = "build")
    } catch (e: Refused) {
        verifier.checks.forEach { println("check ${it.n}: ${if (it.ok) "ok" else "FAILED"} ${it.detail}") }
        println("REFUSED ${e.code}: ${e.detail}")
        exitProcess(1)
    }
    verifier.checks.forEach { println("check ${it.n}: ${if (it.ok) "ok" else "FAILED"} ${it.detail}") }
    val index = File(System.getProperty("java.io.tmpdir"), "ocd-verify-baked-${ProcessHandle.current().pid()}")
    try {
        ServingIndex.write(dataset, index, "build", cell, "verify")
        val data = ServingIndex.read(index)
        println("index: ${dataset.profiles.networks.size} networks, ${data.records.size} records for this cell, group A ${data.groupA.size} keys")
        var failed = false
        for (sim in listOf(SimId.NO_SIM, SimId("262", "07", imsi = "262070000000001"), SimId("262", "02", imsi = "262020000000001"), SimId("999", "99"))) {
            val built = BundleBuilder.build(BuildInput(sim, data, emptyList(), emptySet(), { null }, "2026-10-25", cell))
            println("${if (sim.noSim) "no SIM" else sim.network}: ${built.version}, ${built.values.size} keys, fill ${built.fill?.entry ?: "none"}")
            if (!built.version.endsWith("+ocd${dataset.commit.take(7)}")) failed = true
        }
        // Round 31: the reader serves no carrier-less record; the device override XML sets these after it.
        val o2 = BundleBuilder.build(BuildInput(SimId("262", "07", imsi = "262070000000001"), data, emptyList(), emptySet(), { null }, "2026-10-25", cell))
        val deviceRecords = o2.sources.filterValues { it.startsWith("device_record:") || it.startsWith("measured_record:") }
        println("records served to an o2 SIM: ${deviceRecords.size}${if (deviceRecords.isNotEmpty()) " ${deviceRecords.keys.sorted()}" else ""}")
        if (o2.sources.values.any { it.startsWith("device_record:") }) {
            println("FAILED: a carrier-less device record is served")
            failed = true
        }
        val wrongA = o2.groupA.filter { k -> o2.sources[k]?.let { it != "group_a:google" && it != "group_a:android_default" && !it.startsWith("group_a:read:") && it != "no_emergency_number" } == true }
        if (wrongA.isNotEmpty()) {
            println("FAILED: group A keys from another source: $wrongA")
            failed = true
        }
        val groupA = "imsemergency.skip_audio_dedicated_bearer_wait_timer_for_emergency_bool"
        println("group A $groupA: ${o2.values[groupA] ?: "absent"} (${o2.sources[groupA]}); ${data.groupAReads.size} group A reads for this cell")
        if (failed) {
            println("FAILED: the baked dataset does not serve what the build needs")
            exitProcess(1)
        }
        println("OK: the phone's reader accepts the baked dataset ${dataset.commit.take(12)} (${dataset.datasetDigest.take(19)}...)")
    } finally {
        Files.deleteTree(index)
    }
}
