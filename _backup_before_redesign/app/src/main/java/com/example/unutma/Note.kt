package com.example.unutma

import androidx.room.Entity
import androidx.room.PrimaryKey

const val NOTE_TYPE_TEXT = "TEXT"
const val NOTE_TYPE_LIST = "LIST"

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String = "",
    val description: String = "",
    val type: String = NOTE_TYPE_TEXT,
    val color: String = "",      // "" | "RED" | "ORANGE" | "YELLOW" | "GREEN" | "TEAL" | "BLUE" | "PURPLE" | "PINK"
    val isPinned: Boolean = false,
    val isArchived: Boolean = false
)
