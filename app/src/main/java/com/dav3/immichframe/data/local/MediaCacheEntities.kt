package com.dav3.immichframe.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dav3.immichframe.domain.model.AssetType
import com.dav3.immichframe.domain.model.CachedAsset

@Entity(
    tableName = "cached_assets",
    indices = [
        Index(value = ["cached_at"]),
        Index(value = ["last_modified"]),
    ],
)
data class CachedAssetEntity(
    @PrimaryKey val id: String,
    val type: AssetType,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    val checksum: String?,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "original_mime_type") val originalMimeType: String? = null,
) {
    companion object {
        fun fromDomain(domain: CachedAsset): CachedAssetEntity = CachedAssetEntity(
            id = domain.id,
            type = domain.type,
            filePath = domain.filePath,
            thumbnailPath = domain.thumbnailPath,
            fileSize = domain.fileSize,
            checksum = domain.checksum,
            lastModified = domain.lastModified,
            cachedAt = domain.cachedAt,
            originalMimeType = domain.originalMimeType,
        )

        fun toDomain(
            entity: CachedAssetEntity,
            albumId: String,
        ): CachedAsset = CachedAsset(
            id = entity.id,
            albumId = albumId,
            type = entity.type,
            filePath = entity.filePath,
            thumbnailPath = entity.thumbnailPath,
            fileSize = entity.fileSize,
            checksum = entity.checksum,
            lastModified = entity.lastModified,
            cachedAt = entity.cachedAt,
            originalMimeType = entity.originalMimeType,
        )
    }
}

@Entity(
    tableName = "album_asset_cross_refs",
    primaryKeys = ["album_id", "asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = CachedAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["asset_id"])],
)
data class AlbumAssetCrossRef(
    @ColumnInfo(name = "album_id") val albumId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
)

data class CachedAssetWithAlbum(
    @Embedded val asset: CachedAssetEntity,
    @ColumnInfo(name = "membership_album_id") val albumId: String,
)

@Entity(
    tableName = "album_sync_states",
    primaryKeys = ["album_id"],
)
data class AlbumSyncStateEntity(
    @ColumnInfo(name = "album_id") val albumId: String,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long = 0,
    @ColumnInfo(name = "last_cursor") val lastCursor: String? = null,
    @ColumnInfo(name = "asset_count") val assetCount: Int = 0,
) {
    companion object {
        fun fromDomain(domain: com.dav3.immichframe.domain.model.AlbumSyncState) = AlbumSyncStateEntity(
            albumId = domain.albumId,
            lastSyncedAt = domain.lastSyncedAt,
            lastCursor = domain.lastCursor,
            assetCount = domain.assetCount,
        )

        fun toDomain(entity: AlbumSyncStateEntity) = com.dav3.immichframe.domain.model.AlbumSyncState(
            albumId = entity.albumId,
            lastSyncedAt = entity.lastSyncedAt,
            lastCursor = entity.lastCursor,
            assetCount = entity.assetCount,
        )
    }
}
