package dev.sawitulm.palmannotate

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PalmAnnotateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
