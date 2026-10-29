package io.github.stringmanolo.vocalia.tts

interface TtsEngine {
    fun init(onReady: () -> Unit, onError: (String) -> Unit)
    fun speak(text: String, interruptible: Boolean = true)
    fun stop()
    fun setSpeed(rate: Float)
    fun shutdown()
    val name: String

    /** Se invoca cuando la locución actual termina o se cancela. */
    var onComplete: (() -> Unit)?
}
