package com.otter.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.otter.app.data.model.DownloadItem
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadItem): Long

    @Delete
    suspend fun delete(item: DownloadItem)

    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun getAllDownloads(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadItem?

    @Query("DELETE FROM downloads")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM downloads")
    fun getDownloadCount(): Flow<Int>

    @Query("DELETE FROM downloads WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)
}
