package com.uviewer_android.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun getAllFavorites(): Flow<List<FavoriteItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(item: FavoriteItem)

    @Delete
    suspend fun deleteFavorite(item: FavoriteItem)

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun deleteFavoriteByPath(path: String)
}

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY lastAccessed DESC LIMIT 20")
    fun getRecentFiles(): Flow<List<RecentFile>>

    @Query("SELECT * FROM recent_files ORDER BY lastAccessed DESC LIMIT 1")
    fun getMostRecentFile(): Flow<RecentFile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecent(file: RecentFile)

    @Query("DELETE FROM recent_files")
    suspend fun deleteAll()

    @Query("DELETE FROM recent_files WHERE path NOT IN (SELECT path FROM recent_files ORDER BY lastAccessed DESC LIMIT 20)")
    suspend fun deleteExcess()
}

@Dao
interface WebDavServerDao {
    @Query("SELECT * FROM webdav_servers")
    fun getAllServers(): Flow<List<WebDavServer>>

    @Query("SELECT * FROM webdav_servers WHERE id = :id")
    suspend fun getById(id: Int): WebDavServer?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: WebDavServer): Long

    @Delete
    suspend fun deleteServer(server: WebDavServer)
}
