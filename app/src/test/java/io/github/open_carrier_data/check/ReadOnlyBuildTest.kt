package io.github.open_carrier_data.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Round 25 and the owner direction of 2026-10-19: the default (debug) and
 * release builds stay read-only and unchanged. The owner build's code lives
 * in src/owner, which neither compiles; they get the no-op hook from
 * src/readonly. Run with testDebugUnitTest and testReleaseUnitTest. Round 31
 * adds the `rom` build type (the build other ROMs ship), which compiles
 * src/owner as the owner build does, under its own policy (BuildConfig.TESTER).
 */
class ReadOnlyBuildTest {
    @Test
    fun `the hook matches the build type`() {
        assertEquals(BuildConfig.TESTER.isNotEmpty(), OwnerHook.PRESENT)
        assertEquals(BuildConfig.OWNER, BuildConfig.BUILD_TYPE == "owner")
        assertEquals(BuildConfig.OWNER, BuildConfig.APPLICATION_ID.endsWith(".owner"))
        assertEquals(BuildConfig.OWNER, BuildConfig.TESTER == "owner")
        assertEquals(BuildConfig.BUILD_TYPE == "rom", BuildConfig.TESTER == "rom")
        // The ROM build takes the read-only app's package (spec R31.11, OCD_WRITER_PACKAGE); only the owner build has its own.
        if (BuildConfig.TESTER != "owner") assertEquals("io.github.open_carrier_data.check", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `debug and release contain none of the owner build's classes`() {
        assumeTrue(BuildConfig.TESTER.isEmpty())
        for (name in listOf(
            "io.github.open_carrier_data.check.owner.OwnerActivity",
            "io.github.open_carrier_data.check.owner.OwnerShellService",
            "io.github.open_carrier_data.check.owner.OwnerWorker",
            "io.github.open_carrier_data.check.owner.core.OwnerSession",
            "io.github.open_carrier_data.check.owner.core.KeyGuard",
            "androidx.work.WorkManager",
        )) {
            val loaded = runCatching { Class.forName(name) }.isSuccess
            assertFalse("$name is on the ${BuildConfig.BUILD_TYPE} classpath", loaded)
        }
    }

    @Test
    fun `the read-only hook does nothing`() {
        val hook = File("src/readonly/java/io/github/open_carrier_data/check/OwnerHook.kt").readText()
        assertTrue(hook.contains("const val PRESENT = false"))
        assertTrue(hook.contains("fun onAppStart(context: Context) = Unit"))
        assertTrue(hook.contains("fun Entry() = Unit"))
        assertEquals(listOf("OwnerHook.kt"), File("src/readonly/java").walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun `main code reaches the owner build only through the hook`() {
        for (file in File("src/main/java").walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            assertFalse("${file.name} imports owner code", Regex("import io\\.github\\.open_carrier_data\\.check\\.owner\\.").containsMatchIn(text))
        }
        val main = File("src/main/AndroidManifest.xml").readText()
        // Round 32 U1: INTERNET only for the data pull (AllowlistTest checks who may open a connection).
        assertTrue("android.permission.INTERNET" in main)
        assertFalse(".owner." in main)
    }
}
