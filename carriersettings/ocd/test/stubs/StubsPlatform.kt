/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 *
 * Compile-check stand-in only (ocd/test/run.sh ANDROID_JAR): the platform's hidden XmlUtils.
 */
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.android.internal.util

import org.xmlpull.v1.XmlPullParser

object XmlUtils {
    fun beginDocument(parser: XmlPullParser, firstElementName: String) {}
}
