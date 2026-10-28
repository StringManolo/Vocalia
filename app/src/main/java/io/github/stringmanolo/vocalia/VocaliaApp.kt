package io.github.stringmanolo.vocalia

import android.app.Application
import io.github.stringmanolo.vocalia.util.CrashHandler
import io.github.stringmanolo.vocalia.util.InternalDebug

class VocaliaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InternalDebug.init(this)
        InternalDebug.mark("VocaliaApp.onCreate START")
        try {
            CrashHandler.install(this)
            InternalDebug.mark("CrashHandler installed")
        } catch (t: Throwable) {
            InternalDebug.mark("CrashHandler install FAILED: ${t.message}")
        }
    }
}
