package com.dav3.immichframe.data.local

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MediaCacheMigrationTest {
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE cached_assets (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    album_id TEXT NOT NULL,
                                    type TEXT NOT NULL,
                                    file_path TEXT NOT NULL,
                                    thumbnail_path TEXT,
                                    file_size INTEGER NOT NULL,
                                    checksum TEXT,
                                    last_modified INTEGER NOT NULL,
                                    cached_at INTEGER NOT NULL,
                                    original_mime_type TEXT
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
    }

    @After
    fun tearDown() {
        helper.close()
    }

    @Test
    fun `migration preserves preview row and album membership`() {
        val db = helper.writableDatabase
        db.execSQL(
            """
            INSERT INTO cached_assets (
                id, album_id, type, file_path, thumbnail_path, file_size,
                checksum, last_modified, cached_at, original_mime_type
            ) VALUES ('photo', 'family', 'IMAGE', '/cache/photo',
                '/cache/photo', 123, NULL, 456, 789, 'image/jpeg')
            """.trimIndent(),
        )

        MediaCacheDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT id, file_size, last_modified FROM cached_assets").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("photo", cursor.getString(0))
            assertEquals(123L, cursor.getLong(1))
            assertEquals(456L, cursor.getLong(2))
        }
        db.query("SELECT album_id, asset_id FROM album_asset_cross_refs").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("family", cursor.getString(0))
            assertEquals("photo", cursor.getString(1))
        }
    }
}
