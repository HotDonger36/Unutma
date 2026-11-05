package com.example.unutma

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Which kind of note the FAB created. A note can still mix text and checkboxes. */
const val NOTE_TYPE_TEXT = "TEXT"
const val NOTE_TYPE_LIST = "LIST"

const val RESET_NONE = ""
const val RESET_DAILY = "DAILY"
const val RESET_WEEKLY = "WEEKLY"

/**
 * One note.
 *
 * `description` holds the note body. New notes store it as JSON blocks (see data/Blocks.kt);
 * notes saved by older versions of the app are plain text or "1|item" lines and are read
 * transparently, so nothing is lost when upgrading.
 *
 * All times are epoch milliseconds. `null` means "not set".
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String = "",
    val description: String = "",
    val type: String = NOTE_TYPE_TEXT,
    val color: String = "",          // legacy colour key, kept so old rows still load
    val isPinned: Boolean = false,   // pinned to the top of its section
    val isArchived: Boolean = false,

    // ── added in database version 4 ──
    val labelId: Int? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val reminderAt: Long? = null,
    val nagMinutes: Int = 0,         // 0 = remind once; otherwise repeat until marked done
    val countdownAt: Long? = null,
    val expiresAt: Long? = null,
    val deletedAt: Long? = null,     // set when deleted: the note waits in the archive, then is purged
    val resetMode: String = RESET_NONE,
    val lastResetAt: Long = 0L,
    val pinnedToBar: Boolean = false, // shown as an ongoing notification
    val isLocked: Boolean = false,
)

/** A coloured label. Each label becomes a coloured section on the notes screen. */
@Entity(tableName = "labels")
data class Label(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String = "",
    val color: String = "PURPLE",    // key into LABEL_COLOURS (ui/theme/Color.kt)
    val position: Int = 0,
)
