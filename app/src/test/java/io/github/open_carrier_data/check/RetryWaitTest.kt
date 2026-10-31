package io.github.open_carrier_data.check

import io.github.open_carrier_data.check.core.CommandRunner
import io.github.open_carrier_data.check.core.DeepCheck
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.RetryWait
import io.github.open_carrier_data.check.core.RetryWaits
import io.github.open_carrier_data.check.core.SetupRefusal
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.core.SimSlot
import io.github.open_carrier_data.check.core.VerdictFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * OCD rule round 43, item 2: the retry wait. The dump text is DataRetryManager's section of the S20's
 * `dumpsys activity service com.android.phone` of 2026-10-31 (build H, LIDL Connect), masked with
 * S20_Project/scripts/mask-ids.sh. `phone_s20_retry_after_restore.txt` is the section as read after the
 * restore. `phone_s20_retry_case2.txt` is the same section as it stood at case 2's check (11:39:11): its
 * local log up to the second refusal, and the throttling and retry entries of that refusal, each taken
 * from the dump's own "Add throttling entry" and "Scheduled data retry" lines, which print the entry
 * exactly as the list holds it.
 */
class RetryWaitTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val case2 = requireNotNull(javaClass.getResource("/lab/phone_s20_retry_case2.txt")).readText()
    private val restored = requireNotNull(javaClass.getResource("/lab/phone_s20_retry_after_restore.txt")).readText()
    private val failure = LocalDateTime.parse("2026-10-31T11:37:44.896174")
    private fun ms(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val check = ms("2026-10-31T11:39:11")
    private val expiry = ms("2026-10-31T11:49:42.149")

    @Test
    fun `case 2's throttle, dated from its own log line, gives 11 minutes at the check`() {
        val until = RetryWaits.untilMs(case2, 0, 24962, failure, zone, check)
        assertEquals(expiry, until)
        assertEquals(". The phone waits 11 minutes before trying again", RetryWait.clause(until, check))
        assertEquals(
            "Mobile data refused by the network; this phone's modem software hides the reason. The phone waits 11 minutes before trying again",
            RetryWait.after("Mobile data refused by the network; this phone's modem software hides the reason", until, check),
        )
    }

    @Test
    fun `N is rounded up, never 0, worked out again each time, and left out under a minute`() {
        assertEquals(". The phone waits 1 minute before trying again", RetryWait.clause(expiry, expiry - 60_000))
        assertEquals(". The phone waits 2 minutes before trying again", RetryWait.clause(expiry, expiry - 60_001))
        assertNull(RetryWait.clause(expiry, expiry - 59_999))
        assertNull(RetryWait.clause(expiry, expiry + 1))
        assertNull(RetryWait.clause(null, check))
        // Later checks show the time left then.
        assertEquals(". The phone waits 5 minutes before trying again", RetryWait.clause(expiry, ms("2026-10-31T11:45:00")))
        // Once the throttle ran out, no entry counts.
        assertNull(RetryWaits.untilMs(case2, 0, 24962, failure, zone, expiry + 1))
    }

    @Test
    fun `after the restore nothing is pending`() {
        // The throttle was removed and the retry entry cancelled at 11:41:46; the dump keeps them as history.
        assertNull(RetryWaits.untilMs(restored, 0, 24962, failure, zone, check))
        assertNull(RetryWaits.untilMs(restored, 0, 24962, failure, zone, ms("2026-10-31T11:42:00")))
    }

    @Test
    fun `only the refused profile on WWAN of this phone counts`() {
        assertNull("another profile", RetryWaits.untilMs(case2, 0, 5737, failure, zone, check))
        assertNull("the other phone's section", RetryWaits.untilMs(case2, 1, 24962, failure, zone, check))
        assertNull("not read", RetryWaits.untilMs(null, 0, 24962, failure, zone, check))
        assertNull("on WLAN", RetryWaits.untilMs(case2.replace("transport=WWAN", "transport=WLAN"), 0, 24962, failure, zone, check))
    }

    @Test
    fun `a throttle counts only with its dated line, a pending retry also by the failure's line`() {
        // Without the "Add throttling entry" line the throttle does not count; the pending retry (NOT_RETRIED) still does.
        val noAdd = case2.lines().filterNot { " - Add throttling entry " in it }.joinToString("\n")
        assertEquals(expiry, RetryWaits.untilMs(noAdd, 0, 24962, failure, zone, check))
        // Without its "Scheduled data retry" line too, the failure's registry line dates the retry.
        val noLogs = noAdd.lines().filterNot { " - Scheduled data retry " in it }.joinToString("\n")
        assertEquals(expiry, RetryWaits.untilMs(noLogs, 0, 24962, failure, zone, check))
        // Without any dated line: no clause.
        assertNull(RetryWaits.untilMs(noLogs, 0, 24962, null, zone, check))
        // A retry that already fired, failed or was cancelled is no wait.
        val fired = noAdd.replace("state=NOT_RETRIED, timestamp=11:37:44.907]", "state=FAILED, timestamp=11:49:42.200]")
        assertNull(RetryWaits.untilMs(fired, 0, 24962, failure, zone, check))
    }

    @Test
    fun `a never-retry expiry prints no count`() {
        // The network's "never" (Long.MAX_VALUE) reaches the log as the retry delay before the throttle is added.
        val never = case2.replace("retryDelayMillis=717242ms", "retryDelayMillis=9223372036854775807ms")
            .replace("delay=717242ms", "delay=9223372036854775807ms")
        assertNull(RetryWaits.untilMs(never, 0, 24962, failure, zone, check))
        assertNull(RetryWaits.untilMs(case2.replace("expiration time=11:49:42.149", "expiration time=never").replace("retry time:11:49:42.149", "retry time:never"), 0, 24962, failure, zone, check))
    }

    @Test
    fun `a time of day earlier than its dated line is the next day, the print's jitter is not`() {
        val late = LocalDateTime.parse("2026-10-31T23:55:00.000")
        assertEquals(ms("2026-11-01T00:07:00"), RetryWaits.instant(LocalTime.parse("00:07:00.000"), late, zone))
        assertEquals(ms("2026-10-31T23:59:00"), RetryWaits.instant(LocalTime.parse("23:59:00.000"), late, zone))
        // A retry with delay 0 prints its time a millisecond before the line that logs it: the same day, already past.
        val logged = LocalDateTime.parse("2026-10-31T11:41:46.102705")
        assertEquals(ms("2026-10-31T11:41:46.102"), RetryWaits.instant(LocalTime.parse("11:41:46.102"), logged, zone))
        // The same jitter across midnight stays on the line's day before, never a day ahead.
        assertEquals(ms("2026-10-31T23:59:59.999"), RetryWaits.instant(LocalTime.parse("23:59:59.999"), LocalDateTime.parse("2026-11-01T00:00:00.050"), zone))
    }

    @Test
    fun `the check that decides whether the line changed ignores the clause`() {
        val first = "Mobile data refused by the network; this phone's modem software hides the reason. The phone waits 11 minutes before trying again"
        val later = "Mobile data refused by the network; this phone's modem software hides the reason. The phone waits 1 minute before trying again"
        assertEquals(RetryWait.strip(first), RetryWait.strip(later))
        assertEquals("Mobile data refused by the network; this phone's modem software hides the reason", RetryWait.strip(first))
        assertEquals("A; Phone-side: B", RetryWait.strip("A. The phone waits 3 minutes before trying again; Phone-side: B"))
    }

    @Test
    fun `read-only takes the wait only from the phone service's dump its deep check already read`() {
        val registry = requireNotNull(javaClass.getResource("/lab/registry_s20_lidl_case2_log.txt")).readText()
            .lineSequence().filter { (Regex("^\\s*(\\S+) - ").find(it)?.groupValues?.get(1) ?: "") <= "2026-10-31T11:39:11" }.joinToString("\n")
        val local = ZoneId.systemDefault()
        val nowS = LocalDateTime.parse("2026-10-31T11:39:11").atZone(local).toEpochSecond().toDouble()
        val ran = mutableListOf<ShellCommand>()
        fun runner(samsung: Boolean) = object : CommandRunner {
            override fun <T> run(command: ShellCommand, target: DeepCheckTarget, read: (Sequence<String>) -> T): T? {
                ran += command
                val output = when (command) {
                    ShellCommand.SAMSUNG_IMS -> if (samsung) "  SIM slot: [0] state: [REGISTERED] IMS Profile: [Name : VODAFONE Germany IMS VoLTE, pdn : ims]" else "Can't find service: secims"
                    ShellCommand.TELEPHONY_REGISTRY -> registry
                    ShellCommand.AOSP_IMS -> case2
                    else -> ""
                }
                return read(output.lineSequence())
            }
        }
        // A phone without Samsung's IMS: the deep check already reads the phone service's dump (AOSP_IMS).
        val aosp = DeepCheck.run(DeepCheckTarget(SimSlot.of(0)!!, null), runner(samsung = false)) { nowS }
        assertEquals(listOf(ShellCommand.SAMSUNG_IMS, ShellCommand.TELEPHONY_REGISTRY, ShellCommand.AOSP_IMS, ShellCommand.IWLAN_LOG), ran)
        assertEquals(
            listOf(SetupRefusal(VerdictFeature.MOBILE_DATA, 65536, subId = 3, waitUntilMs = LocalDateTime.parse("2026-10-31T11:49:42.149").atZone(local).toInstant().toEpochMilli())),
            aosp.refusals,
        )
        assertFalse("no dump text is kept", "DataRetryManager" in aosp.encode() || "ocdtest27" in aosp.encode())
        // Samsung's IMS: that dump is not read, so no wait and no extra command.
        ran.clear()
        val samsung = DeepCheck.run(DeepCheckTarget(SimSlot.of(0)!!, null), runner(samsung = true)) { nowS }
        assertEquals(listOf(ShellCommand.SAMSUNG_IMS, ShellCommand.TELEPHONY_REGISTRY, ShellCommand.WIFI_CALLING_SWITCH), ran)
        assertNull(samsung.refusals.single().waitUntilMs)
    }
}
