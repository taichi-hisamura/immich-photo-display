package com.dav3.immichframe.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedAssetDao {
    @Upsert
    suspend fun insertAll(assets: List<CachedAssetEntity>)

    @Upsert
    suspend fun insert(asset: CachedAssetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemberships(memberships: List<AlbumAssetCrossRef>)

    @Query(
        """
        SELECT cached_assets.*, album_asset_cross_refs.album_id AS membership_album_id
        FROM cached_assets
        INNER JOIN album_asset_cross_refs
            ON cached_assets.id = album_asset_cross_refs.asset_id
        WHERE album_asset_cross_refs.album_id = :albumId
        """,
    )
    suspend fun getByAlbumId(albumId: String): List<CachedAssetWithAlbum>

    @Query(
        """
        SELECT cached_assets.*, album_asset_cross_refs.album_id AS membership_album_id
        FROM cached_assets
        INNER JOIN album_asset_cross_refs
            ON cached_assets.id = album_asset_cross_refs.asset_id
        WHERE album_asset_cross_refs.album_id = :albumId
        """,
    )
    fun getByAlbumIdFlow(albumId: String): Flow<List<CachedAssetWithAlbum>>

    @Query(
        """
        SELECT cached_assets.*, album_asset_cross_refs.album_id AS membership_album_id
        FROM cached_assets
        INNER JOIN album_asset_cross_refs
            ON cached_assets.id = album_asset_cross_refs.asset_id
        """,
    )
    suspend fun getAllWithMemberships(): List<CachedAssetWithAlbum>

    @Query("SELECT * FROM cached_assets")
    suspend fun getAllAssets(): List<CachedAssetEntity>

    @Query("SELECT * FROM cached_assets WHERE id IN (:assetIds)")
    suspend fun getByIds(assetIds: List<String>): List<CachedAssetEntity>

    @Query("DELETE FROM cached_assets WHERE id IN (:assetIds)")
    suspend fun deleteByIds(assetIds: List<String>)

    @Query("DELETE FROM album_asset_cross_refs WHERE album_id = :albumId AND asset_id IN (:assetIds)")
    suspend fun deleteMemberships(albumId: String, assetIds: List<String>)

    @Query("DELETE FROM album_asset_cross_refs WHERE album_id = :albumId")
    suspend fun deleteMembershipsByAlbum(albumId: String)

    @Query(
        """
        SELECT cached_assets.* FROM cached_assets
        LEFT JOIN album_asset_cross_refs
            ON cached_assets.id = album_asset_cross_refs.asset_id
        WHERE album_asset_cross_refs.asset_id IS NULL
        """,
    )
    suspend fun getOrphanedAssets(): List<CachedAssetEntity>

    @Query("DELETE FROM cached_assets")
    suspend fun deleteAll()

    @Query("SELECT * FROM cached_assets WHERE id = :assetId")
    suspend fun getById(assetId: String): CachedAssetEntity?
}

@Dao
interface AlbumSyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: AlbumSyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(states: List<AlbumSyncStateEntity>)

    @Query("SELECT * FROM album_sync_states WHERE album_id = :albumId")
    suspend fun getByAlbumId(albumId: String): AlbumSyncStateEntity?

    @Query("SELECT * FROM album_sync_states WHERE album_id = :albumId")
    fun getByAlbumIdFlow(albumId: String): Flow<AlbumSyncStateEntity?>

    @Query("SELECT * FROM album_sync_states")
    suspend fun getAll(): List<AlbumSyncStateEntity>

    @Query("SELECT * FROM album_sync_states")
    fun getAllFlow(): Flow<List<AlbumSyncStateEntity>>

    @Query("DELETE FROM album_sync_states WHERE album_id = :albumId")
    suspend fun deleteByAlbumId(albumId: String)

    @Query("DELETE FROM album_sync_states")
    suspend fun deleteAll()

    @Update
    suspend fun update(state: AlbumSyncStateEntity)
}
