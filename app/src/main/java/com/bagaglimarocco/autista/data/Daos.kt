package com.bagaglimarocco.autista.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trip WHERE id = 1")
    fun observe(): Flow<TripEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trip: TripEntity)
}

@Dao
interface StopDao {
    @Query("SELECT * FROM stops ORDER BY position")
    fun observeAll(): Flow<List<StopEntity>>

    @Query("SELECT * FROM stops WHERE id = :id")
    suspend fun get(id: Long): StopEntity?

    @Query("SELECT COUNT(*) FROM stops")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stops: List<StopEntity>)

    @Update
    suspend fun update(stop: StopEntity)

    @Query("SELECT * FROM stops WHERE synced = 0")
    suspend fun unsynced(): List<StopEntity>

    @Query("UPDATE stops SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)
}

@Dao
interface BagDao {
    @Query("SELECT * FROM bags ORDER BY code")
    fun observeAll(): Flow<List<BagEntity>>

    @Query("SELECT * FROM bags WHERE code = :code")
    suspend fun get(code: String): BagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bags: List<BagEntity>)

    @Update
    suspend fun update(bag: BagEntity)

    @Query("SELECT * FROM bags WHERE synced = 0")
    suspend fun unsynced(): List<BagEntity>

    @Query("UPDATE bags SET synced = 1 WHERE code IN (:codes)")
    suspend fun markSynced(codes: List<String>)
}
