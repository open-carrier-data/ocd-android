package io.github.open_carrier_data.check.owner

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.open_carrier_data.check.owner.core.SettingsSummary

/**
 * The live summary under "Open Carrier Data" in Settings > Network &
 * internet, the owner screen's status sentence ("Everything is signed in with
 * your phone's own settings", "1 improvement active: VoLTE switched on"):
 * Settings calls `getDynamicSummary` on this authority (the
 * `com.android.settings.summary_uri` meta-data of [OwnerActivity]). It
 * answers only the system (Settings runs as the system uid), root, the
 * shell and the app itself, and only with the one line; nothing else is
 * readable here.
 */
class OwnerSettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid !in setOf(Process.SYSTEM_UID, ROOT_UID, SHELL_UID, Process.myUid())) return null
        val context = context ?: return null
        return when (method) {
            METHOD_SUMMARY -> {
                // The owner screen's status sentence, as plain as on the screen.
                val line = runCatching {
                    SettingsSummary.line(FileStateStore(context).read(), OwnerAccess.privilegedGranted(context), OwnerData.simViews(context))
                }.getOrDefault("Carrier settings checks")
                Bundle().apply { putString(KEY_SUMMARY, line) }
            }
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** SettingsLib's EntriesProvider.METHOD_GET_DYNAMIC_SUMMARY and TileUtils.META_DATA_PREFERENCE_SUMMARY. */
        const val METHOD_SUMMARY = "getDynamicSummary"
        const val KEY_SUMMARY = "com.android.settings.summary"
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2000
    }
}
