package com.example.unutma

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Note::class, Label::class], version = 4, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun labelDao(): LabelDao

    companion object {
        @Volatile private var INSTANCE: NoteDatabase? = null

        /**
         * Version 3 → 4: adds the new note columns and the labels table.
         * Existing notes are kept; they get creation/update times that preserve their order.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN labelId INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN reminderAt INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN nagMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN countdownAt INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN expiresAt INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN resetMode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE notes ADD COLUMN lastResetAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN pinnedToBar INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")
                // Newest note = now, each older note one minute earlier, so "Newest first" still works.
                val now = System.currentTimeMillis()
                db.execSQL(
                    "UPDATE notes SET " +
                        "updatedAt = $now - ((SELECT MAX(id) FROM notes) - id) * 60000, " +
                        "createdAt = $now - ((SELECT MAX(id) FROM notes) - id) * 60000"
                )
                // Notes that were archived before keep being archived (never auto-purged).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `labels` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`color` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): NoteDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoteDatabase::class.java,
                    "notes_db"
                )
                    .addMigrations(MIGRATION_3_4)
                    // Only for very old test databases (versions 1–2). Version 3 data migrates safely.
                    .fallbackToDestructiveMigrationFrom(true, 1, 2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
