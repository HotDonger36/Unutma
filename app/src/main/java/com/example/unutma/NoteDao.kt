package com.example.unutma

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isArchived = 0")
    fun observeActive(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isArchived = 1 ORDER BY deletedAt DESC, id DESC")
    fun observeArchived(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observe(id: Int): Flow<Note?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Int): Note?

    @Query("SELECT * FROM notes WHERE isArchived = 0")
    suspend fun activeNow(): List<Note>

    @Query("SELECT * FROM notes")
    suspend fun all(): List<Note>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("UPDATE notes SET labelId = NULL WHERE labelId = :labelId")
    suspend fun clearLabel(labelId: Int)
}

@Dao
interface LabelDao {
    @Query("SELECT * FROM labels ORDER BY position, id")
    fun observeAll(): Flow<List<Label>>

    @Query("SELECT * FROM labels ORDER BY position, id")
    suspend fun all(): List<Label>

    @Insert
    suspend fun insert(label: Label): Long

    @Update
    suspend fun update(label: Label)

    @Delete
    suspend fun delete(label: Label)
}
