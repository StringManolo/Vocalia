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

    /** Pausa la reproducción en caliente. El siguiente chunk no se reproduce hasta resume(). */
    fun pause()

    /** Reanuda la reproducción tras una pausa. */
    fun resume()

    /** true si el engine está pausado. */
    fun isPaused(): Boolean
}
