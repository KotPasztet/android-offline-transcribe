package com.voiceping.offlinetranscription.data.cassette

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A "cassette" groups related voice memos under one topic, like a labeled
 * tape. Memos on a cassette play back-to-back as one continuous tape.
 */
@Entity(tableName = "cassettes")
data class CassetteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** ARGB color int used for the cassette label / shelf tile. */
    val colorArgb: Int,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * A single recording (live mic capture or imported file) belonging to a
 * cassette. [position] determines its order on the tape.
 */
@Entity(
    tableName = "memos",
    foreignKeys = [
        ForeignKey(
            entity = CassetteEntity::class,
            parentColumns = ["id"],
            childColumns = ["cassetteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("cassetteId")]
)
data class MemoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cassetteId: Long,
    val audioFilePath: String,
    val transcriptText: String,
    val durationMs: Long,
    val modelDisplayName: String,
    val position: Int,
    val createdAtMs: Long = System.currentTimeMillis(),
    /** True while an import/transcription is still running for this memo. */
    val isTranscribing: Boolean = false
)
