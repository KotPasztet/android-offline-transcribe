package com.voiceping.offlinetranscription.data.cassette

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CassetteDao {
    @Query("SELECT * FROM cassettes ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<CassetteEntity>>

    @Query("SELECT * FROM cassettes WHERE id = :id")
    fun observeById(id: Long): Flow<CassetteEntity?>

    @Query("SELECT * FROM cassettes WHERE id = :id")
    suspend fun getById(id: Long): CassetteEntity?

    @Insert
    suspend fun insert(cassette: CassetteEntity): Long

    @Update
    suspend fun update(cassette: CassetteEntity)

    @Delete
    suspend fun delete(cassette: CassetteEntity)

    @Query("UPDATE cassettes SET updatedAtMs = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long = System.currentTimeMillis())
}

@Dao
interface MemoDao {
    @Query("SELECT * FROM memos WHERE cassetteId = :cassetteId ORDER BY position ASC")
    fun observeForCassette(cassetteId: Long): Flow<List<MemoEntity>>

    @Query("SELECT * FROM memos WHERE id = :id")
    suspend fun getById(id: Long): MemoEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM memos WHERE cassetteId = :cassetteId")
    suspend fun maxPosition(cassetteId: Long): Int

    @Insert
    suspend fun insert(memo: MemoEntity): Long

    @Update
    suspend fun update(memo: MemoEntity)

    @Delete
    suspend fun delete(memo: MemoEntity)

    @Transaction
    suspend fun appendToCassette(memo: MemoEntity): Long {
        val nextPosition = maxPosition(memo.cassetteId) + 1
        return insert(memo.copy(position = nextPosition))
    }
}
