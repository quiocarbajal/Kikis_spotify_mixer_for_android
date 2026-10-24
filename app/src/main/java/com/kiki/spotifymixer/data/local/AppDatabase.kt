package com.kiki.spotifymixer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kiki.spotifymixer.data.local.dao.ArtistBlacklistDao
import com.kiki.spotifymixer.data.local.dao.PlaybackHistoryDao
import com.kiki.spotifymixer.data.local.dao.PlaylistDao
import com.kiki.spotifymixer.data.local.dao.PlaylistTrackDao
import com.kiki.spotifymixer.data.local.dao.SettingDao
import com.kiki.spotifymixer.data.local.dao.TrackDao
import com.kiki.spotifymixer.data.local.entity.ArtistBlacklistEntity
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
        PlaybackHistoryEntity::class,
        ArtistBlacklistEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun settingDao(): SettingDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun artistBlacklistDao(): ArtistBlacklistDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS artist_blacklist (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL COLLATE NOCASE,
                        added_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artist_blacklist_name ON artist_blacklist(name)")
                db.execSQL("ALTER TABLE tracks ADD COLUMN primary_artist TEXT DEFAULT NULL")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
