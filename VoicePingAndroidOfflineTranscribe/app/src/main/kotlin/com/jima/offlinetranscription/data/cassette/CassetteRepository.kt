package com.voiceping.offlinetranscription.data.cassette

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper over the DAOs so ViewModels don't touch Room directly.
 */
class CassetteRepository(context: Context) {
    private val db = CassetteDatabase.getInstance(context)
    private val cassetteDao = db.cassetteDao()
    private val memoDao = db.memoDao()

    fun observeCassettes(): Flow<List<CassetteEntity>> = cassetteDao.observeAll()

    fun observeCassette(id: Long): Flow<CassetteEntity?> = cassetteDao.observeById(id)

    fun observeMemos(cassetteId: Long): Flow<List<MemoEntity>> = memoDao.observeForCassette(cassetteId)

    suspend fun createCassette(name: String, colorArgb: Int): Long =
        cassetteDao.insert(CassetteEntity(name = name, colorArgb = colorArgb))

    suspend fun renameCassette(cassette: CassetteEntity, newName: String) {
        cassetteDao.update(cassette.copy(name = newName, updatedAtMs = System.currentTimeMillis()))
    }

    suspend fun deleteCassette(cassette: CassetteEntity) {
        cassetteDao.delete(cassette)
    }

    suspend fun addMemo(
        cassetteId: Long,
        audioFilePath: String,
        transcriptText: String,
        durationMs: Long,
        modelDisplayName: String,
        isTranscribing: Boolean = false
    ): Long {
        val id = memoDao.appendToCassette(
            MemoEntity(
                cassetteId = cassetteId,
                audioFilePath = audioFilePath,
                transcriptText = transcriptText,
                durationMs = durationMs,
                modelDisplayName = modelDisplayName,
                position = 0, // overwritten by appendToCassette
                isTranscribing = isTranscribing
            )
        )
        cassetteDao.touch(cassetteId)
        return id
    }

    suspend fun updateMemoTranscript(memo: MemoEntity, transcriptText: String, isTranscribing: Boolean) {
        memoDao.update(memo.copy(transcriptText = transcriptText, isTranscribing = isTranscribing))
    }

    suspend fun deleteMemo(memo: MemoEntity) {
        memoDao.delete(memo)
    }

    suspend fun getMemo(id: Long): MemoEntity? = memoDao.getById(id)

    suspend fun getCassette(id: Long): CassetteEntity? = cassetteDao.getById(id)
}
