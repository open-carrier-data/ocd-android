package io.github.open_carrier_data.check.owner

import android.content.Context
import android.os.Build
import io.github.open_carrier_data.check.owner.core.OwnerState
import io.github.open_carrier_data.check.owner.core.RunResult
import io.github.open_carrier_data.check.owner.core.Settled
import io.github.open_carrier_data.check.owner.core.SettledLevel
import java.io.File
import java.security.KeyStore

/** What a report names from this phone. The app posts nothing itself: a report goes only when the person submits it (round 47, item 2). */
object OwnerAutoReports {
    /** The ROM build as reports name it (OCD round 29): Build.ID and the build's UTC time in seconds. */
    fun publicBuild(): String? = Settled.publicBuild(Build.ID.orEmpty(), Build.TIME)

    /** The settled results for [run] on this phone now ([Settled.forRun]). */
    fun settled(context: Context, state: OwnerState, run: RunResult): Map<String, SettledLevel> =
        Settled.forRun(run, state, OwnerBasis.current(context, state).rom, publicBuild(), System.currentTimeMillis())

    /** Round 47, item 2: a phone that stored a report token before this build deletes the file and its Keystore key, unread. */
    fun forgetOldToken(noBackupDir: File) {
        if (!File(noBackupDir, "report-token.bin").delete()) return
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("ocd-report-token") }
    }
}
