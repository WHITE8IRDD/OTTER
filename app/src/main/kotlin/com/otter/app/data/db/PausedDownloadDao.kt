package com.otter.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PausedDownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PausedDownload)

    @Delete
    suspend fun delete(item: PausedDownload)

    @Query("DELETE FROM paused_downloads WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("SELECT * FROM paused_downloads ORDER BY pausedAt DESC")
    fun getAll(): Flow<List<PausedDownload>>

    @Query("SELECT * FROM paused_downloads WHERE url = :url")
    suspend fun getByUrl(url: String): PausedDownload?
}
