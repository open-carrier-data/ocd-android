package io.github.open_carrier_data.check.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The owner code also ships in the rom build, which other ROMs run on Android 10 to 15 (minSdk 29).
 * `lintOwner` and `lintRom` found newer API calls in round 42's code that only `lintDebug` ran past
 * in CI. Each now holds on every Android the code can run on; CI lints every build type that ships.
 */
class ApiLevelTest {
    private fun source(path: String) = File("src/owner/java/io/github/open_carrier_data/check/owner/$path").readText()

    @Test
    fun `a request for one SIM's network names that SIM on Android 10 too`() {
        val apis = source("OwnerApis.kt")
        val helper = apis.substring(apis.indexOf("fun NetworkRequest.Builder.forSubscription("), apis.indexOf("object ImsReasonReader"))
        // TelephonyNetworkSpecifier is API 30; Android 10 names the SIM by its subscription ID as a string.
        assertTrue(helper.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {\n        setNetworkSpecifier(TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build())"))
        assertTrue(helper.contains("} else {\n        setNetworkSpecifier(subId.toString())"))
        // The ladder's cellular network and the MMS network are both pinned to the SIM, never the default data SIM.
        for (path in listOf("OwnerProbe.kt", "OwnerMessages.kt")) {
            val code = source(path)
            assertTrue(path, code.contains(".addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)"))
            assertEquals(path, 1, Regex("\\.forSubscription\\(sim\\.subId\\)").findAll(code).count())
            assertFalse(path, code.contains("TelephonyNetworkSpecifier.Builder()"))
        }
        // The MMS check still says why it did not run on Android 10, as before.
        assertTrue(source("OwnerMessages.kt").contains("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return MmsCheck(sim.key, now, \"refused_android_too_old\")"))
    }

    @Test
    fun `the IMS reason reader needs what the verdict's gate gives, and the SIP overload stays on Android 14`() {
        val apis = source("OwnerApis.kt")
        assertTrue(apis.contains("@RequiresApi(Build.VERSION_CODES.S)\nobject ImsReasonReader {"))
        assertTrue(apis.contains("@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)\n            fun onUnregistered(info: android.telephony.ims.ImsReasonInfo, details: android.telephony.ims.SipDetails)"))
        val work = source("OwnerWork.kt")
        val diagnose = work.substring(work.indexOf("private suspend fun diagnoseCauses("))
        val gate = diagnose.indexOf("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {")
        assertTrue(gate >= 0)
        assertTrue(gate < diagnose.indexOf("ImsReasonReader.read(context, sim.subId)"))
        // Older Android gets a "not run" line per SIM under the verdict's own tag, never silence.
        val older = diagnose.substring(gate, diagnose.indexOf("return", gate))
        assertTrue(older.contains("quietLog(store, \"diagnosis:${'$'}{sim.slot}\", \"slot ${'$'}{sim.slot + 1}: ${'$'}DIAGNOSIS_NOT_RUN_OLD_ANDROID\")"))
        assertEquals("cause diagnosis not run: needs Android 12 or newer", OwnerWorker.DIAGNOSIS_NOT_RUN_OLD_ANDROID)
    }

    @Test
    fun `the ladder reads the Private DNS broken bit without the passive read's Android 12 calls`() {
        val passive = source("OwnerPassiveRead.kt")
        // Not the whole object: only the read and its helpers need Android 11 or 12.
        assertFalse(passive.contains("@RequiresApi(Build.VERSION_CODES.S)\nobject OwnerPassiveRead"))
        assertTrue(passive.contains("@RequiresApi(Build.VERSION_CODES.S)\n    @SuppressLint(\"MissingPermission\")\n    suspend fun read("))
        val bit = passive.substring(passive.indexOf("fun privateDnsBroken("))
        assertTrue(bit.startsWith("fun privateDnsBroken(caps: NetworkCapabilities): Boolean = runCatching {\n        NetworkCapabilities::class.java.getMethod(\"isPrivateDnsBroken\")"))
        assertFalse(passive.substring(passive.lastIndexOf("*/", passive.indexOf("fun privateDnsBroken(")), passive.indexOf("fun privateDnsBroken(")).contains("RequiresApi"))
    }

    @Test
    fun `CI lints every build type that ships`() {
        val ci = File("../.github/workflows/test.yml").readText()
        for (task in listOf("lintDebug", "lintRelease", "lintOwner", "lintRom")) assertTrue(task, Regex("\\b$task\\b").containsMatchIn(ci))
        // Linting the owner build packages and signs nothing (round 25 OB1): those tasks stay blocked in CI.
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("task.name in setOf(\"assembleOwner\", \"packageOwner\", \"bundleOwner\", \"signOwnerBundle\", \"packageOwnerBundle\")"))
    }
}
