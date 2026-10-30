package io.github.open_carrier_data.check.owner

import io.github.open_carrier_data.check.BuildConfig
import io.github.open_carrier_data.check.owner.core.DataAnswer
import io.github.open_carrier_data.check.owner.core.DatasetStatus
import io.github.open_carrier_data.check.owner.core.DatasetWords
import io.github.open_carrier_data.check.owner.core.OcdDataProtocol
import io.github.open_carrier_data.check.owner.core.OlderApp
import io.github.open_carrier_data.check.owner.core.OwnerState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S20 on 2026-10-28: the round 31 ROM (20261028) carried this app's round 31 build (64e559c) in
 * /system_ext, but the round 30 build (e5240ec), installed over adb on 2026-10-26, kept running from
 * /data/app. Both said versionCode 4, and Android drops an update of a system app only when the
 * system copy's versionCode is higher. The round 30 build reads protocol 2 only, took CarrierSettings's
 * protocol 3 for "no installer", and every "Test now" ran the round 29 trial chain instead of handing
 * the dataset over: CarrierSettings served the build's dataset (`no_dataset`) after the app was enabled
 * again, and the page said "Testing" and "This ROM has no installer".
 */
class OlderAppTest {
    private fun answer(json: String) = DataAnswer(Json.parseToJsonElement(json).jsonObject)

    /**
     * The versionCodes of the owner builds that went onto a phone: vendor/extra's `ocd/OcdCheckOwner.apk`
     * and the `adb install -r` updates over it. A build that goes onto a phone adds its own here; the
     * next build must be higher, or a ROM's newer system copy stays shadowed by the older update.
     */
    private val shipped = mapOf(
        "f480995 (#29, round 30, protocol 2)" to 4,
        "b16ede2 (#31, round 30, protocol 2)" to 4,
        "e5240ec (#32, round 30, protocol 2; the update on the S20 since 2026-10-26)" to 4,
        "64e559c (#33, round 31, protocol 3; in ROM 20261028)" to 4,
        "c66a75c (#34, 0.4.1; vendor/extra main and the update on the S20 since 2026-10-28)" to 5,
    )

    @Test
    fun `a ROM's system copy of this build replaces every older update on the phone`() {
        val code = shipped.values.max()
        val newest = shipped.entries.last { it.value == code }.key
        assertTrue(
            "versionCode ${BuildConfig.VERSION_CODE} is not above $code ($newest): the older update in /data/app would keep running instead of this build",
            BuildConfig.VERSION_CODE > code,
        )
    }

    @Test
    fun `a provider newer than this app is not a ROM without an installer`() {
        val newer = OcdDataProtocol.PROTOCOLS.max() + 1
        val status = DatasetStatus.parse(answer("""{"ok":true,"protocol":$newer,"serving":"build","serving_reason":"no_dataset"}"""), 1)
        // Nothing of a protocol this app does not read is used.
        assertFalse(status.available)
        val words = DatasetWords.of(status, OwnerState()).lines.joinToString("\n")
        assertFalse(words, "no installer" in words)
        assertTrue(words, "older than the ROM's" in words)
        assertTrue(words, "uninstall" in words.lowercase())
    }

    @Test
    fun `Test now refuses on a provider newer than this app instead of running the old trial chain`() {
        val newer = OcdDataProtocol.PROTOCOLS.max() + 1
        val refusal = OlderApp.refusal(DatasetStatus.parse(answer("""{"ok":true,"protocol":$newer}"""), 1))
        assertNotNull(refusal)
        assertTrue(refusal!!, "protocol $newer" in refusal && "older than the ROM's" in refusal)
        // Every protocol this app reads hands the dataset over as before.
        for (p in OcdDataProtocol.PROTOCOLS) assertNull(OlderApp.refusal(DatasetStatus.parse(answer("""{"ok":true,"protocol":$p}"""), 1)))
        // A ROM without the provider (round 29) or one that refused this app keeps the read-only checks and the night tests.
        assertNull(OlderApp.refusal(DatasetStatus.parse(DataAnswer.absent("not on this ROM build"), 1)))
        assertNull(OlderApp.refusal(DatasetStatus.parse(answer("""{"ok":false,"error":"not_writer","reason":"certificate"}"""), 1)))
        assertNull(OlderApp.refusal(DatasetStatus.parse(answer("""{"ok":true,"protocol":1}"""), 1)))
        assertNull(OlderApp.refusal(null))
        // A status stored by an earlier build (without the field) reads as before.
        assertEquals(null, Json { ignoreUnknownKeys = true }.decodeFromString(DatasetStatus.serializer(), """{"available":false,"why":"x"}""").newerProtocol)
    }
}
