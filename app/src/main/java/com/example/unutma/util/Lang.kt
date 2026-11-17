package com.example.unutma.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.unutma.data.AppLanguage
import com.example.unutma.data.Prefs
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's language. Everything the user reads is written in English in the code; when Portuguese (Portugal)
 * is chosen, [tr] swaps it for the Portuguese text at the moment it is shown (see [PT_PT] and [PT_PATTERNS]).
 * Anything without a translation simply stays in English.
 */
object Lang {
    /** Compose state, so every screen redraws the moment the language changes. */
    var isPt by mutableStateOf(false)
        private set

    private val cache = ConcurrentHashMap<String, String>()
    private val lower: Map<String, String> by lazy { PT_PT.entries.associate { it.key.lowercase() to it.value } }

    /** The locale dates, months and weekdays use. */
    val locale: Locale get() = if (isPt) Locale("pt", "PT") else Locale.UK

    fun set(language: AppLanguage) {
        isPt = language == AppLanguage.PORTUGUESE
        Locale.setDefault(if (isPt) pt else systemLocale)
        cache.clear()
    }

    /** The phone's own locale, remembered before we ever change the default. */
    private val systemLocale: Locale = Locale.getDefault()

    /** Called once when the app process starts (also by widgets and reminders, which run without the screen). */
    fun init(ctx: Context) = set(Prefs.get(ctx).current.language)

    /** Translates [s] when Portuguese is on; otherwise (or without a translation) returns it unchanged. */
    fun tr(s: String): String {
        if (!isPt || s.isBlank()) return s
        return cache.getOrPut(s) { translate(s) }
    }

    private fun translate(s: String): String {
        PT_PT[s]?.let { return it }
        // Keep spaces around the text ("Remind me " → "Lembrar-me ").
        val trimmed = s.trim()
        if (trimmed != s) {
            PT_PT[trimmed]?.let { return s.takeWhile { it.isWhitespace() } + it + s.takeLastWhile { it.isWhitespace() } }
        }
        // Headings written in capitals ("TOMORROW", "ALL DONE") are looked up in their normal form.
        if (trimmed == trimmed.uppercase() && trimmed.any { it.isLetter() }) {
            lower[trimmed.lowercase()]?.let { return it.uppercase(pt) }
        }
        for ((re, make) in PT_PATTERNS) {
            val m = re.matchEntire(s) ?: continue
            return make(m)
        }
        return s
    }

    private val pt = Locale("pt", "PT")

    /** Plural helper for the English builders ("1 note" / "2 notes"). */
    fun plural(n: Int, one: String, many: String) = if (n == 1) one else many
}
