package com.example.openeer.domain.favorites

import com.example.openeer.data.favorites.FavoriteDao
import com.example.openeer.data.favorites.FavoriteEntity

class FavoritesRepository(
    private val favoriteDao: FavoriteDao
) {
    suspend fun insert(favorite: FavoriteEntity): Long = favoriteDao.insert(favorite)

    suspend fun update(favorite: FavoriteEntity) = favoriteDao.update(favorite)

    suspend fun delete(id: Long) = favoriteDao.deleteById(id)

    suspend fun getById(id: Long): FavoriteEntity? = favoriteDao.getById(id)

    suspend fun getByKey(key: String): FavoriteEntity? = favoriteDao.getByKey(key)

    suspend fun getAll(): List<FavoriteEntity> = favoriteDao.getAll()
}
