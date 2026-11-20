package it.diy.hudnav

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Istruzioni vocali, in italiano. Si disattiva dal pannello. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val prefs = ctx.getSharedPreferences("hud_ctrl", Context.MODE_PRIVATE)
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false
    var onReady: ((Boolean) -> Unit)? = null

    var enabled: Boolean
        get() = prefs.getBoolean("voice", true)
        set(value) {
            prefs.edit().putBoolean("voice", value).apply()
        }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val lang = tts.setLanguage(Locale.ITALIAN)
        ready = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
        onReady?.invoke(ready)
    }

    fun say(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hud")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
