package io.github.stringmanolo.vocalia

import android.app.Application
import io.github.stringmanolo.vocalia.util.CrashHandler

class VocaliaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
