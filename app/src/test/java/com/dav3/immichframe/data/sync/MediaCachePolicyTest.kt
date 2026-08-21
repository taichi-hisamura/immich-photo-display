package com.dav3.immichframe.data.sync

import com.dav3.immichframe.domain.model.Asset
import com.dav3.immichframe.domain.model.AssetType
import com.dav3.immichframe.domain.model.CachedAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCachePolicyTest {
    @Test
    fun `unchanged image does not download again`() {
        val remote = image(id = "photo", lastModified = 123)

        assertFalse(shouldDownloadPreview(remote, cached(remote, lastModified = 123)))
    }

    @Test
    fun `new or modified image downloads preview`() {
        val remote = image(id = "photo", lastModified = 456)

        assertTrue(shouldDownloadPreview(remote, null))
        assertTrue(shouldDownloadPreview(remote, cached(remote, lastModified = 123)))
    }

    @Test
    fun `video is never a syncable asset`() {
        val image = image(id = "image", lastModified = 1)
        val gif = image(id = "gif", lastModified = 1, mimeType = "image/gif")
        val video = Asset(id = "video", type = AssetType.VIDEO, lastModified = 1, originalMimeType = "video/mp4")

        assertEquals(listOf(image, gif), selectSyncableImages(listOf(image, video, gif)))
    }

    private fun image(
        id: String,
        lastModified: Long,
        mimeType: String = "image/jpeg",
    ) = Asset(
        id = id,
        type = AssetType.IMAGE,
        lastModified = lastModified,
        originalMimeType = mimeType,
    )

    private fun cached(
        remote: Asset,
        lastModified: Long,
    ) = CachedAsset(
        id = remote.id,
        albumId = "family",
        type = AssetType.IMAGE,
        filePath = "/cache/${remote.id}",
        thumbnailPath = "/cache/${remote.id}",
        fileSize = 100,
        checksum = null,
        lastModified = lastModified,
        cachedAt = 1,
        originalMimeType = remote.originalMimeType,
    )
}
