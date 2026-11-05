package com.example.unutma.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

enum class SortOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    TITLE_AZ("Title A–Z"),
    TITLE_ZA("Title Z–A"),
    UPCOMING("Coming up first"),
    CUSTOM("My order (drag notes)"),
}

enum class TextSize(val label: String, val scale: Float) {
    SMALL("Small", 0.9f), MEDIUM("Medium", 1f), LARGE("Large", 1.15f)
}

/** How long a deleted note waits in the archive before it is removed for good. */
val ARCHIVE_OPTIONS: List<Pair<Int, String>> = listOf(
    1 to "1 day", 3 to "3 days", 7 to "1 week", 14 to "2 weeks", 30 to "1 month", ARCHIVE_FOREVER to "Forever",
)

/** archiveDays value meaning "never remove deleted notes". */
const val ARCHIVE_FOREVER = 0

fun archiveLabel(days: Int): String = ARCHIVE_OPTIONS.firstOrNull { it.first == days }?.second ?: "$days days"

data class Settings(
    val themeMode: ThemeMode = ThemeMode.DARK,   // the app starts in dark mode
    val accentKey: String = "PURPLE",
    val textSize: TextSize = TextSize.MEDIUM,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val archiveDays: Int = 7,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val widgetTextSize: TextSize = TextSize.MEDIUM,
    /** Pinned notes go in the main notification list (not "Silent"), where Android shows the top one opened up. */
    val pinExpanded: Boolean = true,
)

/**
 * App settings, saved in SharedPreferences and exposed as a StateFlow so every screen
 * updates the moment something changes. Also readable from notification receivers and widgets.
 */
class Prefs private constructor(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state
    val current: Settings get() = _state.value

    private fun load(): Settings = Settings(
        themeMode = enumOr(sp.getString("theme_mode", null), ThemeMode.DARK),
        accentKey = migrateAccent(sp.getString("accent_key", "PURPLE") ?: "PURPLE"),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        sortOrder = when (val s = sp.getString("sort_order", null)) {
            null, "DEFAULT", "COLOR" -> SortOrder.NEWEST
            else -> enumOr(s, SortOrder.NEWEST)
        },
        archiveDays = sp.getInt("archive_days", 7),
        sound = sp.getBoolean("notif_sound", true),
        vibration = sp.getBoolean("notif_vibration", true),
        widgetTextSize = enumOr(sp.getString("widget_text_size", null), TextSize.MEDIUM),
        // On by default: pinned notes then sit with your main notifications, above the "Silent" group.
        pinExpanded = sp.getBoolean("pin_expanded", true),
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_state.value)
        sp.edit()
            .putString("theme_mode", s.themeMode.name)
            .putString("accent_key", s.accentKey)
            .putString("text_size", s.textSize.name)
            .putString("sort_order", s.sortOrder.name)
            .putInt("archive_days", s.archiveDays)
            .putBoolean("notif_sound", s.sound)
            .putBoolean("notif_vibration", s.vibration)
            .putString("widget_text_size", s.widgetTextSize.name)
            .putBoolean("pin_expanded", s.pinExpanded)
            .apply()
        _state.value = s
    }

    fun flag(key: String): Boolean = sp.getBoolean(key, false)
    fun setFlag(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()

    // Small key/value store for widgets ("which note does widget 12 show?").
    fun widgetNote(widgetId: Int): Int = sp.getInt("widget_$widgetId", -1)
    fun setWidgetNote(widgetId: Int, noteId: Int) = sp.edit().putInt("widget_$widgetId", noteId).apply()
    fun clearWidget(widgetId: Int) = sp.edit()
        .remove("widget_$widgetId").remove("widget_size_$widgetId").remove("widget_label_$widgetId").apply()

    /** A widget's own text size (set in its Widget settings), or the default from Settings. */
    fun widgetTextSize(widgetId: Int): TextSize =
        sp.getString("widget_size_$widgetId", null)?.let { n -> TextSize.entries.firstOrNull { it.name == n } } ?: current.widgetTextSize
    fun setWidgetTextSize(widgetId: Int, size: TextSize) = sp.edit().putString("widget_size_$widgetId", size.name).apply()

    /** Sections folded away on the notes screen ("none" = no label, "l<id>" = a label). */
    fun collapsedSections(): Set<String> = sp.getStringSet("collapsed_sections", null)?.toSet() ?: emptySet()
    fun setCollapsedSections(keys: Set<String>) = sp.edit().putStringSet("collapsed_sections", keys).apply()

    /** Which label the notes widget shows (-1 = all notes). */
    fun widgetLabel(widgetId: Int): Int = sp.getInt("widget_label_$widgetId", -1)
    fun setWidgetLabel(widgetId: Int, labelId: Int) = sp.edit().putInt("widget_label_$widgetId", labelId).apply()

    // Days (local epoch days) on which a checklist had every item ticked, for the streak widget.
    fun doneDays(noteId: Int): Set<Long> =
        sp.getStringSet("done_days_$noteId", null)?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    fun setDoneDays(noteId: Int, days: Set<Long>) =
        sp.edit().putStringSet("done_days_$noteId", days.map { it.toString() }.toSet()).apply()
    fun clearDoneDays(noteId: Int) = sp.edit().remove("done_days_$noteId").apply()

    /** Your own order of notes in a section ("none" or "l<labelId>"), as note ids. */
    fun noteOrder(section: String): List<Int> =
        sp.getString("note_order_$section", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
    fun setNoteOrder(section: String, ids: List<Int>) = sp.edit().putString("note_order_$section", ids.joinToString(",")).apply()

    fun bestStreak(noteId: Int): Int = sp.getInt("best_streak_$noteId", 0)
    fun setBestStreak(noteId: Int, n: Int) = sp.edit().putInt("best_streak_$noteId", n).apply()

    /** Plain string values (routine progress is kept as a small JSON object). null removes it. */
    fun raw(key: String): String? = sp.getString(key, null)
    fun setRaw(key: String, value: String?) = sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()

    companion object {
        private const val PREFS = "unutma_prefs"
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context).also { instance = it } }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
            try { if (name == null) fallback else enumValueOf<T>(name) } catch (_: Exception) { fallback }

        /** Old accent keys from the previous settings screen map onto the new palette. */
        private fun migrateAccent(key: String): String = when (key) {
            "YELLOW" -> "AMBER"
            "ORANGE", "RED" -> "CORAL"
            "TEAL" -> "BLUE"
            else -> key
        }
    }
}
