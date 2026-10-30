/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 *
 * Compile-check stand-ins only (ocd/test/run.sh ANDROID_JAR); never built into the app. They
 * stand for the generated R class and for the hidden PersistableBundle.restoreFromXml that
 * LineageOS's override lines in the service call, with just the members the glue uses.
 */
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.android.carriersettings

import android.os.PersistableBundle

object R {
    object array {
        const val ocd_owner_certs = 0x7f010000
    }

    object xml {
        const val carrier_config_override = 0x7f020000
    }

    object string {
        const val ocd_writer_package = 0x7f030000
    }
}

/** Stands for the hidden PersistableBundle.restoreFromXml in run.sh's copy of the service. */
fun restoreFromXmlStandIn(parser: org.xmlpull.v1.XmlPullParser): PersistableBundle = PersistableBundle()
