package com.kiki.spotifymixer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kiki.spotifymixer.data.local.dao.PlaybackHistoryDao
import com.kiki.spotifymixer.data.local.dao.PlaylistDao
import com.kiki.spotifymixer.data.local.dao.PlaylistTrackDao
import com.kiki.spotifymixer.data.local.dao.SettingDao
import com.kiki.spotifymixer.data.local.dao.TrackDao
import com.kiki.spotifymixer.data.local.entity.PlaybackHistoryEntity
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.data.local.entity.PlaylistTrackCrossRef
import com.kiki.spotifymixer.data.local.entity.SettingEntity
import com.kiki.spotifymixer.data.local.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        SettingEntity::class,
        PlaybackHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun settingDao(): SettingDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao

    companion object {
        private const val DATABASE_NAME = "spotify_mixer.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN popularity INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE tracks ADD COLUMN artist_popularity INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE tracks ADD COLUMN is_hidden_gem INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN hidden_gem_type TEXT DEFAULT NULL")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
