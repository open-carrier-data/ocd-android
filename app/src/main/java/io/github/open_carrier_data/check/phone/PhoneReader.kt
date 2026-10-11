@file:Suppress("DEPRECATION") // PhoneStateListener and getConfigForSubId(int) serve Android 10 to 13.

package io.github.open_carrier_data.check.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.telephony.AccessNetworkConstants
import android.telephony.CarrierConfigManager
import android.telephony.CellSignalStrengthNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import io.github.open_carrier_data.check.core.CarrierKeys
import io.github.open_carrier_data.check.core.CarrierSettingsReading
import io.github.open_carrier_data.check.core.DataConnection
import io.github.open_carrier_data.check.core.DeviceInfo
import io.github.open_carrier_data.check.core.Gid1Status
import io.github.open_carrier_data.check.core.NetworkReading
import io.github.open_carrier_data.check.core.PhoneReadings
import io.github.open_carrier_data.check.core.RomFamily
import io.github.open_carrier_data.check.core.SimReading
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/** A SIM the user can pick when more than one is active. */
data class SimChoice(val subId: Int, val slotIndex: Int, val label: String)

/**
 * Reads the phone through public SDK calls only. Read-only: no call here
 * changes a setting, a carrier config value or an APN, and the app holds no
 * permission that could.
 *
 * Values are reduced to the allowlisted fields of [PhoneReadings] at once;
 * no raw object (ServiceState, PersistableBundle, NetworkInfo) is kept.
 */
class PhoneReader(private val context: Context) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val carrierConfig = context.getSystemService(CarrierConfigManager::class.java)

    fun hasPhonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun hasTelephony(): Boolean {
        val feature = if (Build.VERSION.SDK_INT >= 33) {
            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION
        } else {
            PackageManager.FEATURE_TELEPHONY
        }
        return telephony != null && context.packageManager.hasSystemFeature(feature)
    }

    /** Active SIMs. Without the permission only the default data SIM is known. */
    @SuppressLint("MissingPermission")
    fun simChoices(): List<SimChoice> {
        if (!hasTelephony()) return emptyList()
        if (hasPhonePermission()) {
            val list = runCatching { subscriptions?.activeSubscriptionInfoList }.getOrNull().orEmpty()
            if (list.isNotEmpty()) {
                return list.sortedBy { it.simSlotIndex }.map { info ->
                    val tm = telephony.createForSubscriptionId(info.subscriptionId)
                    val name = runCatching { tm.simOperatorName }.getOrNull().orEmpty()
                        .ifBlank { runCatching { tm.simOperator }.getOrNull().orEmpty() }
                    SimChoice(info.subscriptionId, info.simSlotIndex, "Slot ${info.simSlotIndex + 1}: ${name.ifBlank { "SIM" }}")
                }
            }
        }
        val subId = defaultSubId() ?: return emptyList()
        return listOf(SimChoice(subId, SubscriptionManager.getSlotIndex(subId), "Default data SIM"))
    }

    private fun defaultSubId(): Int? =
        listOf(SubscriptionManager.getDefaultDataSubscriptionId(), SubscriptionManager.getDefaultSubscriptionId())
            .firstOrNull { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    suspend fun read(subId: Int?): PhoneReadings = withContext(Dispatchers.IO) {
        val permission = hasPhonePermission()
        val device = deviceInfo()
        val today = LocalDate.now()
        if (!hasTelephony()) {
            return@withContext PhoneReadings(today, device, false, permission, null, null, null, null)
        }
        val id = subId ?: defaultSubId()
        val tm = if (id != null) telephony.createForSubscriptionId(id) else telephony
        val simReady = runCatching { tm.simState == TelephonyManager.SIM_STATE_READY }.getOrDefault(false)
        val sim = if (simReady) readSim(tm, id, permission) else null
        PhoneReadings(
            checkedOn = today,
            device = device,
            hasTelephony = true,
            permissionGranted = permission,
            sim = sim,
            network = if (simReady) readNetwork(tm, permission) else null,
            carrierSettings = if (simReady && id != null) readCarrierSettings(id, permission) else null,
            dataConnections = if (simReady) readDataConnections(id) else null,
        )
    }

    private fun deviceInfo() = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        codename = Build.DEVICE.orEmpty(),
        androidMajor = Build.VERSION.RELEASE.orEmpty().takeWhile(Char::isDigit).toIntOrNull() ?: 0,
        romFamily = RomFamily.detect(Build.DISPLAY, Build.FINGERPRINT, Build.PRODUCT, Build.TYPE, Build.TAGS),
    )

    @SuppressLint("MissingPermission")
    private fun readSim(tm: TelephonyManager, subId: Int?, permission: Boolean): SimReading {
        val gid1 = if (!permission) {
            null to Gid1Status.NOT_PERMITTED
        } else {
            try {
                val value = tm.groupIdLevel1
                if (value.isNullOrEmpty()) null to Gid1Status.EMPTY else value to Gid1Status.READ
            } catch (_: SecurityException) {
                null to Gid1Status.NOT_PERMITTED
            } catch (_: RuntimeException) {
                null to Gid1Status.UNAVAILABLE
            }
        }
        return SimReading(
            slotIndex = subId?.let { SubscriptionManager.getSlotIndex(it) }?.takeIf { it >= 0 },
            homeMccMnc = runCatching { tm.simOperator }.getOrNull()?.takeIf { it.isNotBlank() },
            spn = runCatching { tm.simOperatorName }.getOrNull() ?: "",
            carrierId = runCatching { tm.simCarrierId.toLong() }.getOrNull()?.takeIf { it >= 0 },
            carrierName = runCatching { tm.simCarrierIdName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() },
            specificCarrierId = runCatching { tm.simSpecificCarrierId.toLong() }.getOrNull()?.takeIf { it >= 0 },
            specificCarrierName = runCatching { tm.simSpecificCarrierIdName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() },
            gid1 = gid1.first,
            gid1Status = gid1.second,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun readNetwork(tm: TelephonyManager, permission: Boolean): NetworkReading {
        val radio = readRadio(tm, permission)
        val registrations = radio.serviceState?.let(::wwanPacketType)
        return NetworkReading(
            servingMccMnc = runCatching { tm.networkOperator }.getOrNull()
                ?.takeIf { it.length in 5..6 && it.all(Char::isDigit) },
            roaming = runCatching { tm.isNetworkRoaming }.getOrNull(),
            dataNetworkType = if (permission) runCatching { tm.dataNetworkType }.getOrNull() else null,
            voiceNetworkType = if (permission) runCatching { tm.voiceNetworkType }.getOrNull() else null,
            psWwanType = registrations?.first,
            iconOverride = radio.iconOverride,
            nrSignalReported = runCatching {
                tm.signalStrength?.getCellSignalStrengths(CellSignalStrengthNr::class.java)?.isNotEmpty()
            }.getOrNull(),
            inService = radio.serviceState?.let { ss ->
                ss.state == ServiceState.STATE_IN_SERVICE || registrations?.second == true
            },
        )
    }

    /** The access technology of the packet-switched mobile registration, and whether it is registered. */
    private fun wwanPacketType(ss: ServiceState): Pair<Int, Boolean>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val nri = ss.networkRegistrationInfoList.firstOrNull {
            it.domain and NetworkRegistrationInfo.DOMAIN_PS != 0 &&
                it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN
        } ?: return null
        val registered = if (Build.VERSION.SDK_INT >= 34) {
            nri.isNetworkRegistered
        } else {
            @Suppress("DEPRECATION")
            nri.isRegistered
        }
        return nri.accessNetworkTechnology to registered
    }

    private class Radio(val serviceState: ServiceState?, val iconOverride: Int?)

    /**
     * One ServiceState and one TelephonyDisplayInfo from a short-lived listener.
     * The ServiceState a listener gets without location permission is
     * sanitized by Android: no cell identities, no operator names.
     */
    private suspend fun readRadio(tm: TelephonyManager, permission: Boolean): Radio =
        withContext(Dispatchers.Main) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) radioByCallback(tm) else radioByListener(tm, permission)
            }.getOrElse { Radio(null, null) }
        }

    @RequiresApi(31)
    private suspend fun radioByCallback(tm: TelephonyManager): Radio {
        val serviceState = CompletableDeferred<ServiceState>()
        val display = CompletableDeferred<TelephonyDisplayInfo>()
        val callback = object : TelephonyCallback(),
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.DisplayInfoListener {
            override fun onServiceStateChanged(state: ServiceState) {
                serviceState.complete(state)
            }

            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                display.complete(info)
            }
        }
        tm.registerTelephonyCallback(context.mainExecutor, callback)
        try {
            val state = withTimeoutOrNull(LISTEN_TIMEOUT_MS) { serviceState.await() }
            val info = withTimeoutOrNull(LISTEN_TIMEOUT_MS / 2) { display.await() }
            return Radio(state, info?.overrideNetworkType)
        } finally {
            tm.unregisterTelephonyCallback(callback)
        }
    }

    private suspend fun radioByListener(tm: TelephonyManager, permission: Boolean): Radio {
        val serviceState = CompletableDeferred<ServiceState?>()
        val display = CompletableDeferred<Int>()
        val listener = object : PhoneStateListener(context.mainExecutor) {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onServiceStateChanged(state: ServiceState?) {
                serviceState.complete(state)
            }

            @RequiresApi(30) // registered for only on Android 11, see events below
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                display.complete(info.overrideNetworkType)
            }
        }
        // On Android 11 the display info event needs READ_PHONE_STATE; Android 10 has none.
        val events = if (Build.VERSION.SDK_INT == 30 && permission) {
            PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
        } else {
            PhoneStateListener.LISTEN_SERVICE_STATE
        }
        tm.listen(listener, events)
        try {
            val state = withTimeoutOrNull(LISTEN_TIMEOUT_MS) { serviceState.await() }
            val icon = if (events != PhoneStateListener.LISTEN_SERVICE_STATE) {
                withTimeoutOrNull(LISTEN_TIMEOUT_MS / 2) { display.await() }
            } else {
                null
            }
            return Radio(state, icon)
        } finally {
            tm.listen(listener, PhoneStateListener.LISTEN_NONE)
        }
    }

    /** The allowlisted CarrierConfig keys, read and reduced at once. */
    private fun readCarrierSettings(subId: Int, permission: Boolean): CarrierSettingsReading {
        val unreadable = CarrierSettingsReading(false, null, emptyMap(), null)
        if (!permission || carrierConfig == null) return unreadable
        val bundle = try {
            if (Build.VERSION.SDK_INT >= 34) {
                val keys = CarrierKeys.all + CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL
                carrierConfig.getConfigForSubId(subId, *keys.toTypedArray())
            } else {
                carrierConfig.getConfigForSubId(subId)
            }
        } catch (_: RuntimeException) {
            null
        }
        if (bundle == null || bundle.isEmpty) return unreadable
        val booleans = CarrierKeys.booleans
            .filter { bundle.containsKey(it.key) }
            .associate { it.key to bundle.getBoolean(it.key) }
        return CarrierSettingsReading(
            readable = true,
            identifiedCarrier = CarrierConfigManager.isConfigForIdentifiedCarrier(bundle),
            booleans = booleans,
            nrAvailabilities = bundle.getIntArray(CarrierKeys.NR_AVAILABILITIES)?.toList(),
        )
    }

    /**
     * Mobile data connections the app can see, with ACCESS_NETWORK_STATE only:
     * the APN name (NetworkInfo extra info) and the APN types the network
     * serves. An emergency connection is skipped entirely.
     */
    private fun readDataConnections(subId: Int?): List<DataConnection> {
        val cm = connectivity ?: return emptyList()
        return runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList()).mapNotNull { network ->
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return@mapNotNull null
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) return@mapNotNull null
            if (Build.VERSION.SDK_INT >= 30 && subId != null) {
                val specifier = caps.networkSpecifier as? TelephonyNetworkSpecifier
                if (specifier != null && specifier.subscriptionId != subId) return@mapNotNull null
            }
            val info = runCatching { cm.getNetworkInfo(network) }.getOrNull()
            DataConnection(
                apn = info?.extraInfo?.trim()?.takeIf { it.isNotEmpty() },
                serves = APN_TYPES.filter { (capability, _) -> caps.hasCapability(capability) }.map { it.second },
                setUpOver = info?.subtype,
            )
        }
    }

    private companion object {
        const val LISTEN_TIMEOUT_MS = 1500L

        /** NetworkCapabilities.NET_CAPABILITY_* to APN type names; EIMS is left out on purpose. */
        val APN_TYPES = listOf(
            NetworkCapabilities.NET_CAPABILITY_INTERNET to "internet",
            NetworkCapabilities.NET_CAPABILITY_MMS to "mms",
            NetworkCapabilities.NET_CAPABILITY_SUPL to "supl",
            NetworkCapabilities.NET_CAPABILITY_DUN to "dun",
            NetworkCapabilities.NET_CAPABILITY_FOTA to "fota",
            NetworkCapabilities.NET_CAPABILITY_IMS to "ims",
            NetworkCapabilities.NET_CAPABILITY_CBS to "cbs",
            NetworkCapabilities.NET_CAPABILITY_IA to "ia",
            NetworkCapabilities.NET_CAPABILITY_XCAP to "xcap",
            NetworkCapabilities.NET_CAPABILITY_MCX to "mcx",
        )
    }
}
