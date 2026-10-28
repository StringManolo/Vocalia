package io.github.stringmanolo.vocalia

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class VocaliaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VocaliaA11y"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AccessibilityService conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Stub de Fase 0. En Fase 2 leeremos y accionaremos nodos.
    }

    override fun onInterrupt() {
        Log.i(TAG, "AccessibilityService interrumpido")
    }
}
