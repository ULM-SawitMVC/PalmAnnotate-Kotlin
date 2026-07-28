package dev.sawitulm.palmannotate

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.sawitulm.palmannotate.data.build.SigningIdentityPolicy

@HiltAndroidApp
class PalmAnnotateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // WS-21: state the update identity once per process. An APK signed by a throwaway debug
        // key cannot be replaced in place, and the only escape is an uninstall that deletes the
        // dataset — so it has to be visible in the log a field build always produces.
        SigningIdentityPolicy.advisory(BuildConfig.SIGNING_IDENTITY, BuildConfig.APPLICATION_ID)
            ?.let { Log.w(TAG, it) }
    }

    private companion object {
        const val TAG = "PalmAnnotate"
    }
}
