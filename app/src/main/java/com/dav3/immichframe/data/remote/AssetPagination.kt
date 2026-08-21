package com.dav3.immichframe.data.remote

/**
 * Fetch every page returned by Immich's metadata search endpoint.
 *
 * Immich encodes [SearchAssetsDto.nextPage] as a string. A malformed or
 * repeated cursor is treated as an error instead of silently returning an
 * incomplete album, because an incomplete result must never drive cache
 * reconciliation and delete valid offline media.
 */
internal suspend fun fetchAllAssetDtos(
    albumId: String,
    pageSize: Int = 1000,
    fetchPage: suspend (SearchMetadataRequest) -> SearchMetadataResponse,
): List<AssetDto> {
    require(pageSize in 1..1000) { "Immich search page size must be in 1..1000" }

    val result = mutableListOf<AssetDto>()
    val fetchedPages = mutableSetOf<Int>()
    var page = 1

    while (true) {
        check(fetchedPages.add(page)) { "Immich returned a repeated search page: $page" }

        val response = fetchPage(
            SearchMetadataRequest(
                albumIds = listOf(albumId),
                size = pageSize,
                page = page,
            ),
        )
        result += response.assets.items

        val nextPage = response.assets.nextPage ?: break
        page = nextPage.toIntOrNull()
            ?: error("Immich returned an invalid nextPage value: $nextPage")
        check(page > 0) { "Immich returned a non-positive nextPage value: $page" }
    }

    return result.distinctBy(AssetDto::id)
}
