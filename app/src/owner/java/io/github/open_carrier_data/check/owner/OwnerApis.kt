package io.github.open_carrier_data.check.owner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.feature.MmTelFeature
import androidx.annotation.RequiresApi
import io.github.open_carrier_data.check.owner.core.ImsApi
import io.github.open_carrier_data.check.owner.core.RegistrationEvent
import io.github.open_carrier_data.check.owner.core.RegistrationWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * The phone and telecom services' binders, called directly (privileged
 * route). The managers' system-API wrappers (TelephonyManager
 * isInEmergencySmsMode and getEmergencyCallbackMode, TelecomManager
 * isInEmergencyCall) answer false when the service is missing or the call
 * fails; an emergency guard must not read that as "no emergency". Here a
 * missing binder, a failed lookup or a failed call is null, and the refusals
 * fail closed on it. The app is on the hidden-API allowlist
 * (privapp-permissions file), which the private getters need.
 */
object SystemBinders {
    const val TELEPHONY = "com.android.internal.telephony.ITelephony"
    const val TELECOM = "com.android.internal.telecom.ITelecomService"

    /** TelephonyManager's private static getITelephony(); null without the phone service. */
    fun telephony(): Any? = runCatching {
        TelephonyManager::class.java.getDeclaredMethod("getITelephony").apply { isAccessible = true }.invoke(null)
    }.getOrNull()

    /** TelecomManager's private getTelecomService(); null without the telecom service. */
    fun telecom(context: Context): Any? = runCatching {
        val manager = context.getSystemService(TelecomManager::class.java) ?: return null
        TelecomManager::class.java.getDeclaredMethod("getTelecomService").apply { isAccessible = true }.invoke(manager)
    }.getOrNull()

    /**
     * Calls a boolean method of the AIDL interface [iface] on [binder]. Int
     * arguments only. Null when the binder is null or the call throws
     * (RemoteException, SecurityException, a missing method).
     */
    fun call(binder: Any?, iface: String, method: String, vararg args: Int): Boolean? {
        binder ?: return null
        return runCatching {
            val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
            Class.forName(iface).getMethod(method, *types).invoke(binder, *args.toTypedArray()) as? Boolean
        }.getOrNull()
    }
}

/**
 * One slot's IMS through ImsMmTelManager (privileged route, with
 * READ_PRIVILEGED_PHONE_STATE): the registration state and transport, the
 * MmTel capabilities (a capability callback, which Android answers at once
 * with the current status) and the user's three switches. Each answer waits
 * at most [TIMEOUT_MS]; a missing one is null.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object ImsApiReader {
    private const val TIMEOUT_MS = 5_000L
    private val direct = Executor { it.run() }

    @SuppressLint("MissingPermission")
    suspend fun read(context: Context, subId: Int): ImsApi {
        val mmtel = runCatching { context.getSystemService(ImsManager::class.java)?.getImsMmTelManager(subId) }.getOrNull()
            ?: return ImsApi(null, null, null, null, null)
        val state = answer { done -> mmtel.getRegistrationState(direct, done) }
        val transport = answer { done -> mmtel.getRegistrationTransportType(direct, done) }
        val caps = capabilities(mmtel)
        return ImsApi(
            registrationState = state,
            transportType = transport,
            voice = caps?.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE),
            video = caps?.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO),
            sms = caps?.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_SMS),
            volteByUser = runCatching { mmtel.isAdvancedCallingSettingEnabled }.getOrNull(),
            vtByUser = runCatching { mmtel.isVtSettingEnabled }.getOrNull(),
            wfcByUser = runCatching { mmtel.isVoWiFiSettingEnabled }.getOrNull(),
        )
    }

    private suspend fun answer(ask: (Consumer<Int>) -> Unit): Int? {
        val result = CompletableDeferred<Int>()
        if (runCatching { ask { result.complete(it) } }.isFailure) return null
        return withTimeoutOrNull(TIMEOUT_MS) { result.await() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun capabilities(mmtel: ImsMmTelManager): MmTelFeature.MmTelCapabilities? {
        val result = CompletableDeferred<MmTelFeature.MmTelCapabilities>()
        val callback = object : ImsMmTelManager.CapabilityCallback() {
            override fun onCapabilitiesStatusChanged(capabilities: MmTelFeature.MmTelCapabilities) {
                result.complete(capabilities)
            }
        }
        if (runCatching { mmtel.registerMmTelCapabilityCallback(direct, callback) }.isFailure) return null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { result.await() }
        } finally {
            runCatching { mmtel.unregisterMmTelCapabilityCallback(callback) }
        }
    }
}

/**
 * IMS registrations of one subscription through RegistrationManager's
 * callback (READ_PRECISE_PHONE_STATE or READ_PRIVILEGED_PHONE_STATE). Android
 * answers at once with the current state; the runner counts only events after
 * its change, so a fresh REGISTER over LTE is told from a kept registration.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ImsRegistrationWatch private constructor(private val mmtel: ImsMmTelManager) : RegistrationWatch {
    private val events = java.util.concurrent.CopyOnWriteArrayList<RegistrationEvent>()
    private val callback = object : android.telephony.ims.RegistrationManager.RegistrationCallback() {
        override fun onRegistered(attributes: android.telephony.ims.ImsRegistrationAttributes) {
            events += RegistrationEvent(System.currentTimeMillis(), true, attributes.transportType)
        }

        override fun onUnregistered(info: android.telephony.ims.ImsReasonInfo) {
            events += RegistrationEvent(System.currentTimeMillis(), false, null)
        }
    }

    override fun events(): List<RegistrationEvent> = events.toList()

    override fun close() {
        runCatching { mmtel.unregisterImsRegistrationCallback(callback) }
    }

    companion object {
        private val direct = Executor { it.run() }

        @SuppressLint("MissingPermission")
        fun start(context: Context, subId: Int): RegistrationWatch? {
            val mmtel = runCatching { context.getSystemService(ImsManager::class.java)?.getImsMmTelManager(subId) }.getOrNull() ?: return null
            val watch = ImsRegistrationWatch(mmtel)
            return if (runCatching { mmtel.registerImsRegistrationCallback(direct, watch.callback) }.isSuccess) watch else null
        }
    }
}

/**
 * Why video calling cannot work on this build, from static checks (the
 * report of 2026-10-20): PHH IMS has no video (it masks the capability out,
 * has no video call provider and offers audio only), and Android's video gate
 * also needs the device's config_device_vt_available, which the S20 tree
 * leaves off. Empty: no static reason, so a trial may tell.
 */
object VideoStatic {
    const val IMS_SERVICE = "android.telephony.ims.ImsService"

    /** The packages that implement Android's ImsService; empty when not readable. */
    fun imsPackages(context: Context): List<String> = runCatching {
        context.packageManager.queryIntentServices(android.content.Intent(IMS_SERVICE), 0).map { it.serviceInfo.packageName }
    }.getOrDefault(emptyList())

    fun reasons(context: Context): List<String> = buildList {
        val stacks = imsPackages(context)
        if (stacks.any { it.startsWith("me.phh.ims") }) add("ims_stack_phh_no_video")
        val flag = runCatching {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_device_vt_available", "bool", "android")
            if (id == 0) null else res.getBoolean(id)
        }.getOrNull()
        if (flag == false) add("device_vt_flag_off")
    }
}
