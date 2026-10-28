package io.github.stringmanolo.vocalia
import io.github.stringmanolo.vocalia.util.VocaliaLogger

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class VocaliaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VocaliaA11y"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        VocaliaLogger.i(TAG, "AccessibilityService conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stub de Fase 0. En Fase 2 leeremos y accionaremos nodos.
    }

    override fun onInterrupt() {
        VocaliaLogger.i(TAG, "AccessibilityService interrumpido")
    }
}
