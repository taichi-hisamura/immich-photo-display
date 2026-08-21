package com.dav3.immichframe.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CachedAssetEntity::class, AlbumAssetCrossRef::class, AlbumSyncStateEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MediaCacheDatabase : RoomDatabase() {
    abstract fun cachedAssetDao(): CachedAssetDao
    abstract fun albumSyncStateDao(): AlbumSyncStateDao

    companion object {
        @Volatile
        private var instance: MediaCacheDatabase? = null

        fun getDatabase(context: Context): MediaCacheDatabase = instance ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                MediaCacheDatabase::class.java,
                "media_cache_db",
            )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
            this.instance = instance
            instance
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_assets RENAME TO cached_assets_v2")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_assets (
                        id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        thumbnail_path TEXT,
                        file_size INTEGER NOT NULL,
                        checksum TEXT,
                        last_modified INTEGER NOT NULL,
                        cached_at INTEGER NOT NULL,
                        original_mime_type TEXT,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO cached_assets (
                        id, type, file_path, thumbnail_path, file_size, checksum,
                        last_modified, cached_at, original_mime_type
                    )
                    SELECT
                        id, type, file_path, thumbnail_path, file_size, checksum,
                        last_modified, cached_at, original_mime_type
                    FROM cached_assets_v2
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS album_asset_cross_refs (
                        album_id TEXT NOT NULL,
                        asset_id TEXT NOT NULL,
                        PRIMARY KEY(album_id, asset_id),
                        FOREIGN KEY(asset_id) REFERENCES cached_assets(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO album_asset_cross_refs (album_id, asset_id)
                    SELECT album_id, id FROM cached_assets_v2
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE cached_assets_v2")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_assets_cached_at ON cached_assets(cached_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_assets_last_modified ON cached_assets(last_modified)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_album_asset_cross_refs_asset_id " +
                        "ON album_asset_cross_refs(asset_id)",
                )
            }
        }
    }
}
