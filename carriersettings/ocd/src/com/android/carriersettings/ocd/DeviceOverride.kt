/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.android.carriersettings.R
import com.android.internal.util.XmlUtils
import org.xmlpull.v1.XmlPullParser
import java.util.regex.Pattern

/**
 * The ROM's device override, in the form its device trees ship it (OCD rule round 31, item
 * "keys", SM3), applied by CarrierConfigService last, after OCD's bundle, on every path:
 * - LineageOS: its fixed key and the `carrier_config_override.xml` overlay of CarrierSettings's
 *   CarrierSettingsConfig overlayable. CarrierConfigService applies these with LineageOS's own
 *   lines (check_upstream.sh); this object reads the same resource for `explain`, `status` and
 *   `put_record`'s refusal.
 * - AOSP: CarrierConfig's `vendor.xml` (`vendor_no_sim.xml` without a SIM) as the image's
 *   `com.android.carrierconfig` holds it, with a device tree's overlay applied, filtered as AOSP's
 *   DefaultCarrierConfigService filters it. CarrierConfigService applies it just before
 *   LineageOS's lines. With no such package, or LineageOS's empty file, it adds nothing.
 * The SIM's identifiers are used in memory only.
 */
object DeviceOverride {
    const val AOSP_PACKAGE = "com.android.carrierconfig"

    /** LineageOS's fixed key, as CarrierConfigService's internalConfigOverride holds it (check_upstream.sh proves that block). */
    val LINEAGE_FIXED: Map<String, Any> = mapOf("imsvoice.audio_evs_support_bool" to false)

    /** The device override XML through this package's resources (its overlay applied); null when unreadable. */
    fun overrideXml(context: Context): PersistableBundle? = runCatching {
        context.resources.getXml(R.xml.carrier_config_override).use { parser ->
            XmlUtils.beginDocument(parser, "carrier_config")
            PersistableBundle.restoreFromXml(parser)
        }
    }.getOrNull()

    /**
     * AOSP's vendor.xml for [sim] (`vendor_no_sim.xml` for no SIM); with [sim] null only the
     * fragments without carrier filters. Null when the image has no CarrierConfig or no such file.
     */
    fun aospVendor(context: Context, sim: SimId?): PersistableBundle? {
        val res = try {
            context.packageManager.getResourcesForApplication(AOSP_PACKAGE)
        } catch (e: Exception) {
            return null
        }
        val noSim = sim?.noSim == true
        val id = res.getIdentifier(if (noSim) "vendor_no_sim" else "vendor", "xml", AOSP_PACKAGE)
        if (id == 0) return null
        val skuId = res.getIdentifier("sku_filter", "string", AOSP_PACKAGE)
        val sku = if (skuId != 0) runCatching { res.getString(skuId) }.getOrDefault("") else ""
        val out = PersistableBundle()
        res.getXml(id).use { parser ->
            while (true) {
                val event = parser.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                if (event == XmlPullParser.START_TAG && parser.name == "carrier_config") {
                    if (!filters(parser, sim, sku)) continue
                    out.putAll(PersistableBundle.restoreFromXml(parser))
                }
            }
        }
        return out
    }

    /** CarrierConfigService's AOSP step: vendor.xml into [bundle], just before LineageOS's fixed key and override XML. */
    fun applyAosp(context: Context, sim: SimId?, bundle: PersistableBundle) {
        runCatching { aospVendor(context, sim ?: return)?.let(bundle::putAll) }
            .onFailure { android.util.Log.e(OcdAndroid.TAG, "CarrierConfig's vendor.xml could not be read", it) }
    }

    /**
     * AOSP's checkFilters. A carrier filter holds for no SIM (AOSP passes no identifier then)
     * and never for [sim] null (the unconditional part only).
     */
    private fun filters(parser: XmlPullParser, sim: SimId?, sku: String): Boolean {
        val vendorSku = systemProperty("ro.boot.product.vendor.sku")
        val hardwareSku = systemProperty("ro.boot.product.hardware.sku")
        val carrier: SimId? = sim?.takeIf { !it.noSim }
        val unconditional = sim == null
        for (i in 0 until parser.attributeCount) {
            val value = parser.getAttributeValue(i) ?: ""
            val ok = when (parser.getAttributeName(i)) {
                "mcc" -> !unconditional && (carrier == null || value == carrier.mcc)
                "mnc" -> !unconditional && (carrier == null || value == carrier.mnc)
                "gid1" -> !unconditional && (carrier == null || value.equals(carrier.gid1, ignoreCase = true))
                "gid2" -> !unconditional && (carrier == null || value.equals(carrier.gid2, ignoreCase = true))
                "spn" -> !unconditional && (carrier == null || matchOnSp(value, carrier.spn))
                "imsi" -> !unconditional && (carrier == null || (carrier.imsi != null && Pattern.compile(value, Pattern.CASE_INSENSITIVE).matcher(carrier.imsi).matches()))
                "cid" -> !unconditional && (carrier == null || value.toIntOrNull().let { it != null && (it == carrier.carrierId || it == carrier.specificCarrierId) })
                "device" -> value.equals(Build.DEVICE, ignoreCase = true)
                "vendorSku" -> value.equals(vendorSku, ignoreCase = true)
                "hardwareSku" -> value.equals(hardwareSku, ignoreCase = true)
                "board" -> value.equals(Build.BOARD, ignoreCase = true)
                "sku" -> value.equals(sku, ignoreCase = true)
                "name" -> true
                else -> false
            }
            if (!ok) return false
        }
        return true
    }

    private fun matchOnSp(xml: String, spn: String?): Boolean =
        if (xml.equals("null", ignoreCase = true)) spn.isNullOrEmpty() else spn != null && Pattern.compile(xml, Pattern.CASE_INSENSITIVE).matcher(spn).matches()

    private fun systemProperty(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    /**
     * What the phone applies last for [sim], typed, in its order (vendor.xml, LineageOS's fixed
     * key, the override XML; a later one wins), with the source each key comes from. Null when
     * the override XML cannot be read.
     */
    fun effective(context: Context, sim: SimId?): Map<String, OverrideValue>? {
        val xml = overrideXml(context) ?: return null
        val out = LinkedHashMap<String, OverrideValue>()
        runCatching { aospVendor(context, sim) }.getOrNull()?.let { putTyped(out, it, "device_override:aosp_vendor_xml") }
        for ((k, v) in LINEAGE_FIXED) out[k] = OverrideValue(Types.typeOfAndroid(v) ?: KeyType.STRING, v, "lineageos_fixed")
        putTyped(out, xml, "device_override")
        return out
    }

    private fun putTyped(out: MutableMap<String, OverrideValue>, bundle: PersistableBundle, source: String) {
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            val raw = bundle.get(key) ?: continue
            out[key] = typed(raw)?.let { (t, v) -> OverrideValue(t, v, source) } ?: OverrideValue(KeyType.STRING, raw.toString(), source)
        }
    }

    private fun typed(raw: Any): Pair<KeyType, Any>? = when (raw) {
        is Boolean -> KeyType.BOOL to raw
        is Int -> KeyType.INT to raw
        is Long -> KeyType.LONG to raw
        is Double -> KeyType.DOUBLE to raw
        is String -> KeyType.STRING to raw
        is IntArray -> KeyType.INT_ARRAY to raw.toList()
        is Array<*> -> if (raw.all { it is String }) KeyType.STRING_ARRAY to raw.map { it as String } else null
        is PersistableBundle -> {
            val inner = LinkedHashMap<String, Inner>()
            for (k in raw.keySet()) {
                @Suppress("DEPRECATION")
                val v = raw.get(k) ?: continue
                typed(v)?.let { (t, value) -> inner[k] = Inner(t, value) }
            }
            KeyType.BUNDLE to inner
        }
        else -> null
    }
}
