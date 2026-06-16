package dev.sawitulm.palmannotate.data.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.exportFolderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "export_folder"
)

/**
 * Persists the user-selected SAF (Storage Access Framework) export folder.
 *
 * The URI grant must be taken persistently via
 * [android.content.ContentResolver.takePersistableUriPermission] by the UI
 * immediately after the folder is picked.
 */
@Singleton
class ExportFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.exportFolderDataStore

    companion object {
        private val EXPORT_FOLDER_URI_KEY = stringPreferencesKey("export_folder_uri")
    }

    val folderUri: Flow<Uri?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[EXPORT_FOLDER_URI_KEY]?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        }

    val folderName: Flow<String?> = folderUri.map { uri ->
        uri?.lastPathSegment?.substringAfterLast('/')
    }

    val isConfigured: Flow<Boolean> = folderUri.map { it != null }

    suspend fun saveFolder(uri: Uri) {
        dataStore.edit { preferences ->
            preferences[EXPORT_FOLDER_URI_KEY] = uri.toString()
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(EXPORT_FOLDER_URI_KEY)
        }
    }
}
