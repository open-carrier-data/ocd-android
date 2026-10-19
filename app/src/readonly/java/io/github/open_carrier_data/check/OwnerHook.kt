package io.github.open_carrier_data.check

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * The owner build's two entry points, as the debug and release builds see
 * them: they do nothing. These builds stay read-only (round 25: "the release
 * variant read-only"); the owner build's own OwnerHook lives in src/owner,
 * which no other build type compiles.
 */
object OwnerHook {
    /** False here; true only in the owner build. */
    const val PRESENT = false

    /** Called once when the app's screen starts. */
    @Suppress("UNUSED_PARAMETER")
    fun onAppStart(context: Context) = Unit

    /** A card at the top of the check screen. */
    @Composable
    fun Entry() = Unit
}
