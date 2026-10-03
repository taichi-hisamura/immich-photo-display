package com.dav3.immichframe.ui.slideshow

import com.dav3.immichframe.domain.model.Asset
import com.dav3.immichframe.domain.model.AssetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowPlaybackTest {
    @Test
    fun `image becomes ready only for matching success callback`() {
        val photo = Asset(id = "current", type = AssetType.IMAGE)

        assertFalse(isAssetReadyForPlayback(photo, null))
        assertFalse(isAssetReadyForPlayback(photo, "outgoing"))
        assertTrue(isAssetReadyForPlayback(photo, "current"))
    }

    @Test
    fun `video is immediately ready without an image callback`() {
        assertTrue(isAssetReadyForPlayback(Asset(id = "video", type = AssetType.VIDEO), null))
    }

    @Test
    fun `normal cache always supersedes an old fallback photo`() {
        val cached = Asset(id = "cached", type = AssetType.IMAGE)
        val fallback = Asset(id = "fallback", type = AssetType.IMAGE)

        assertTrue(fallbackForDisplay(listOf(cached), fallback) == null)
        assertTrue(fallbackForDisplay(emptyList(), fallback) == fallback)
    }
}
