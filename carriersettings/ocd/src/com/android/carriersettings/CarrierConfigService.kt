/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Open Carrier Data
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings

import android.os.PersistableBundle
import android.service.carrier.CarrierIdentifier
import android.service.carrier.CarrierService
import android.util.Log
import com.android.carriersettings.ocd.DeviceOverride
import com.android.carriersettings.ocd.OcdAndroid
import com.android.carriersettings.ocd.SimId
import com.android.internal.util.XmlUtils

/**
 * OCD's CarrierService (OCD rules rounds 30 and 31, vendor/extra carriersettings/ocd): each SIM's
 * bundle comes from OCD's dataset alone, built by [com.android.carriersettings.ocd.Engine] in the
 * order of round 31's item "keys". No Google carrier file is read. The CarrierIdentifier holds
 * the IMSI and GIDs; it is used in memory to match and is never logged.
 *
 * The ROM's device override applies last, after OCD's bundle, also when the dataset fails to load
 * (round 31, SM3: device quirks belong to the ROM's device tree, and OCD keeps no device-only
 * values). This is permanent: an AOSP device tree's CarrierConfig vendor.xml when the image ships
 * one, then LineageOS's fixed key and the device override XML, exactly as LineageOS's service
 * applies them (check_upstream.sh proves those lines are LineageOS's).
 */
class CarrierConfigService : CarrierService() {
    companion object {
        const val TAG = "CarrierConfigService"
        private val internalConfigOverride = PersistableBundle().apply {
            putBoolean("imsvoice.audio_evs_support_bool", false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLoadConfig(id: CarrierIdentifier?): PersistableBundle? {
        throw UnsupportedOperationException("Not supported")
    }

    override fun onLoadConfig(subscriptionId: Int, id: CarrierIdentifier?): PersistableBundle? {
        Log.i(TAG, "onLoadConfig: $subscriptionId")
        val sim = runCatching { OcdAndroid.simOf(this, subscriptionId, id) }.getOrNull()
        val bundle = try {
            OcdAndroid.toBundle(OcdAndroid.engine(this).load(subscriptionId, sim ?: error("the SIM could not be read")))
        } catch (e: Throwable) {
            Log.e(TAG, "OCD's dataset could not be served", e)
            PersistableBundle()
        }
        // An AOSP device tree's override, CarrierConfig's vendor.xml, when the image ships one (SM3).
        DeviceOverride.applyAosp(this, if (id == null) SimId.NO_SIM else sim, bundle)
        bundle.putAll(internalConfigOverride)
        // Device constraints must take precedence over the protobuf settings.
        runCatching {
            resources.getXml(R.xml.carrier_config_override).use { parser ->
                XmlUtils.beginDocument(parser, "carrier_config")
                bundle.putAll(PersistableBundle.restoreFromXml(parser))
            }
        }.onFailure {
            Log.e(TAG, "Unable to parse config override", it)
        }
        return bundle
    }
}
