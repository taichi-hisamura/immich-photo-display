package com.dav3.immichframe.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssetPaginationTest {
    @Test
    fun `fetches every page and preserves request page numbers`() = runBlocking {
        val requestedPages = mutableListOf<Int>()

        val result = fetchAllAssetDtos(albumId = "album", pageSize = 1000) { request ->
            requestedPages += request.page
            when (request.page) {
                1 -> response(items = 1000, nextPage = "2", idOffset = 0)
                2 -> response(items = 1, nextPage = null, idOffset = 1000)
                else -> error("unexpected page")
            }
        }

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(1001, result.size)
        assertEquals("asset-1000", result.last().id)
    }

    @Test
    fun `deduplicates assets that occur on adjacent pages`() = runBlocking {
        val result = fetchAllAssetDtos(albumId = "album") { request ->
            when (request.page) {
                1 -> SearchMetadataResponse(
                    assets = SearchAssetsDto(
                        items = listOf(AssetDto(id = "shared")),
                        nextPage = "2",
                    ),
                )
                2 -> SearchMetadataResponse(
                    assets = SearchAssetsDto(
                        items = listOf(AssetDto(id = "shared"), AssetDto(id = "new")),
                    ),
                )
                else -> error("unexpected page")
            }
        }

        assertEquals(listOf("shared", "new"), result.map(AssetDto::id))
    }

    @Test
    fun `rejects repeated next page instead of returning a partial album`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fetchAllAssetDtos(albumId = "album") {
                    SearchMetadataResponse(
                        assets = SearchAssetsDto(nextPage = "1"),
                    )
                }
            }
        }
    }

    @Test
    fun `rejects malformed next page instead of returning a partial album`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fetchAllAssetDtos(albumId = "album") {
                    SearchMetadataResponse(
                        assets = SearchAssetsDto(nextPage = "not-a-page"),
                    )
                }
            }
        }
    }

    private fun response(
        items: Int,
        nextPage: String?,
        idOffset: Int,
    ): SearchMetadataResponse = SearchMetadataResponse(
        assets = SearchAssetsDto(
            items = List(items) { AssetDto(id = "asset-${it + idOffset}") },
            nextPage = nextPage,
        ),
    )
}
