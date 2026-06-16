package dev.sawitulm.palmannotate.data.storage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InputCache @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("input_cache", Context.MODE_PRIVATE)

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
}
