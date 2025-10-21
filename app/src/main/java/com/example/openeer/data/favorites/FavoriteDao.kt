package com.example.openeer.data.favorites

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: FavoriteEntity): Long

    @Update
    suspend fun update(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun getById(id: Long): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): FavoriteEntity?

    @Query("SELECT * FROM favorites ORDER BY createdAt ASC")
    suspend fun getAll(): List<FavoriteEntity>
}
