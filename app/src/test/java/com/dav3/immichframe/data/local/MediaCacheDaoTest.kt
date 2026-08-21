package com.dav3.immichframe.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dav3.immichframe.domain.model.AssetType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MediaCacheDaoTest {
    private lateinit var database: MediaCacheDatabase
    private lateinit var dao: CachedAssetDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MediaCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.cachedAssetDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `one cached file can belong to multiple albums`() = runBlocking {
        dao.insert(asset(id = "shared"))
        dao.insertMemberships(
            listOf(
                AlbumAssetCrossRef(albumId = "common", assetId = "shared"),
                AlbumAssetCrossRef(albumId = "family", assetId = "shared"),
            ),
        )

        assertEquals(listOf("shared"), dao.getByAlbumId("common").map { it.asset.id })
        assertEquals(listOf("shared"), dao.getByAlbumId("family").map { it.asset.id })
        assertEquals(1, dao.getAllAssets().size)
    }

    @Test
    fun `removing one album membership does not orphan shared file`() = runBlocking {
        dao.insert(asset(id = "shared"))
        dao.insertMemberships(
            listOf(
                AlbumAssetCrossRef(albumId = "common", assetId = "shared"),
                AlbumAssetCrossRef(albumId = "family", assetId = "shared"),
            ),
        )

        dao.deleteMemberships("common", listOf("shared"))

        assertTrue(dao.getByAlbumId("common").isEmpty())
        assertEquals(1, dao.getByAlbumId("family").size)
        assertTrue(dao.getOrphanedAssets().isEmpty())
    }

    @Test
    fun `asset becomes orphan only after final membership is removed`() = runBlocking {
        dao.insert(asset(id = "shared"))
        dao.insertMemberships(
            listOf(
                AlbumAssetCrossRef(albumId = "common", assetId = "shared"),
                AlbumAssetCrossRef(albumId = "family", assetId = "shared"),
            ),
        )

        dao.deleteMemberships("common", listOf("shared"))
        dao.deleteMemberships("family", listOf("shared"))

        assertEquals(listOf("shared"), dao.getOrphanedAssets().map { it.id })
    }

    @Test
    fun `updating cached metadata preserves all memberships`() = runBlocking {
        dao.insert(asset(id = "shared", lastModified = 1))
        dao.insertMemberships(
            listOf(
                AlbumAssetCrossRef(albumId = "common", assetId = "shared"),
                AlbumAssetCrossRef(albumId = "family", assetId = "shared"),
            ),
        )

        dao.insert(asset(id = "shared", lastModified = 2))

        assertEquals(2, dao.getByAlbumId("common").single().asset.lastModified)
        assertEquals(2, dao.getByAlbumId("family").single().asset.lastModified)
    }

    private fun asset(
        id: String,
        lastModified: Long = 1,
    ): CachedAssetEntity = CachedAssetEntity(
        id = id,
        type = AssetType.IMAGE,
        filePath = "/cache/$id",
        thumbnailPath = "/cache/$id",
        fileSize = 100,
        checksum = null,
        lastModified = lastModified,
        cachedAt = 1,
        originalMimeType = "image/jpeg",
    )
}
