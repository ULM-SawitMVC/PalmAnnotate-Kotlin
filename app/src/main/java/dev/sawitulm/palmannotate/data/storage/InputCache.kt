package dev.sawitulm.palmannotate.data.storage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sawitulm.palmannotate.domain.model.CaptureSetPolicy
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InputCache @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("input_cache", Context.MODE_PRIVATE)

    /**
     * WS-12 — per-install identity.
     *
     * A random UUID minted on first read and then never regenerated, so it survives process
     * death, app updates and reboots but NOT a reinstall (a reinstall is a new collection
     * identity, which is the honest answer). Deliberately not derived from ANDROID_ID, the build
     * serial or any hardware identifier: those are either unavailable without privileged
     * permissions on modern Android, or personally identifying once they reach a shared dataset.
     *
     * Written with commit() rather than apply(): the id must be durable before the first
     * captureSetId derived from it is committed to a package, otherwise a crash could hand two
     * runs different install identities.
     */
    val installId: String
        get() = synchronized(this) {
            prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() } ?: run {
                val minted = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_INSTALL_ID, minted).commit()
                minted
            }
        }

    /** Public, opaque handle for this install. Stable for the life of the install. */
    val deviceToken: String get() = CaptureSetPolicy.deviceTokenFrom(installId)

    /** WS-13 — operator name, remembered between runs so it is typed once per campaign. */
    var lastOperatorName: String
        get() = prefs.getString("last_operator_name", "") ?: ""
        set(value) = prefs.edit().putString("last_operator_name", value.trim()).apply()

    /** WS-12 — whether the operator opted into device-token tree names for the last new run. */
    var lastUseNameToken: Boolean
        get() = prefs.getBoolean("last_use_name_token", false)
        set(value) = prefs.edit().putBoolean("last_use_name_token", value).apply()

    var lastVariety: String
        get() = prefs.getString("last_variety", "DAMIMAS") ?: "DAMIMAS"
        set(value) = prefs.edit().putString("last_variety", value).apply()

    var lastBlock: String
        get() = prefs.getString("last_block", "") ?: ""
        set(value) = prefs.edit().putString("last_block", value).apply()

    var lastSideCount: Int
        get() = prefs.getInt("last_side_count", 4)
        set(value) = prefs.edit().putInt("last_side_count", value.coerceIn(2, 20)).apply()

    var lastAutoId: Boolean
        get() = prefs.getBoolean("last_auto_id", true)
        set(value) = prefs.edit().putBoolean("last_auto_id", value).apply()

    var lastCaptureUsesOrbbec: Boolean
        get() = prefs.getBoolean("last_capture_uses_orbbec", false)
        set(value) = prefs.edit().putBoolean("last_capture_uses_orbbec", value).apply()

    var resumedFolderUri: String?
        get() = prefs.getString("resumed_folder_uri", null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove("resumed_folder_uri")
            else editor.putString("resumed_folder_uri", value)
            editor.apply()
        }

    private companion object {
        const val KEY_INSTALL_ID = "install_id"
    }
}
