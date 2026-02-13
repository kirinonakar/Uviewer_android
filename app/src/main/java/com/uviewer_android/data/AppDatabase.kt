package com.uviewer_android.data

import android.content.Context
import androidx.room.*

@Database(entities = [FavoriteItem::class, RecentFile::class, WebDavServer::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun recentFileDao(): RecentFileDao
    abstract fun webDavServerDao(): WebDavServerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "uviewer_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
