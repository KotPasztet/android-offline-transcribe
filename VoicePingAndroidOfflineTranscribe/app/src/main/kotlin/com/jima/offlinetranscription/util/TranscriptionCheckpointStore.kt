package com.voiceping.offlinetranscription.util

import android.content.Context
import com.voiceping.offlinetranscription.service.TranscriptionSegment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists a lightweight "how far did we get" checkpoint for file
 * transcription and live recording, so that if the app process crashes
 * mid-transcription, the NEXT launch can pick up where it left off
 * instead of starting over from zero.
 *
 * Guards against crash-loops: if the same checkpoint keeps failing to
 * make real progress across retries, [FileCheckpoint.shouldAutoResume]
 * returns false and the caller should abandon + clear it instead of
 * resuming forever (e.g. a file that reliably crashes the native engine
 * a few tokens/seconds in would otherwise retry on every app start).
 *
 * Backed by plain SharedPreferences + JSON — no new dependency needed.
 */
class TranscriptionCheckpointStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("transcription_checkpoints", Context.MODE_PRIVATE)

    // ---- File transcription checkpoint --------------------------------

    data class FileCheckpoint(
        val wavFilePath: String,
        val languageHint: String,
        val cassetteId: Long?,        // null when not tied to a cassette memo
        val processedChunks: Int,     // fully completed 20s chunks
        val accumulatedText: String,
        val segmentsJson: String,     // serialized TranscriptionSegment list
        val attemptStartChunk: Int,   // processedChunks value when the CURRENT attempt began
        val attemptCount: Int         // how many times we've tried resuming from attemptStartChunk
    ) {
        /**
         * False once we've retried the same stuck point too many times, or
         * a retry crashed again while barely producing any output — that's
         * the "crashed after ~5 tokens" case the caller should NOT keep
         * relaunching into forever.
         */
        fun shouldAutoResume(): Boolean {
            if (attemptCount >= MAX_RETRIES_AT_SAME_CHUNK) return false
            val wordCount = accumulatedText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            if (attemptCount >= 1 && wordCount < MIN_WORDS_TO_TRUST_PROGRESS) return false
            return true
        }
    }

    fun saveFileCheckpoint(checkpoint: FileCheckpoint) {
        val json = JSONObject().apply {
            put("wavFilePath", checkpoint.wavFilePath)
            put("languageHint", checkpoint.languageHint)
            put("cassetteId", checkpoint.cassetteId ?: -1L)
            put("processedChunks", checkpoint.processedChunks)
            put("accumulatedText", checkpoint.accumulatedText)
            put("segmentsJson", checkpoint.segmentsJson)
            put("attemptStartChunk", checkpoint.attemptStartChunk)
            put("attemptCount", checkpoint.attemptCount)
        }
        // commit() (synchronous) rather than apply(): this checkpoint must survive
        // a process crash that could happen moments later, so it needs to actually
        // hit disk before we return, not just queue an async write.
        prefs.edit().putString(KEY_FILE_CHECKPOINT, json.toString()).commit()
    }

    fun loadFileCheckpoint(): FileCheckpoint? {
        val raw = prefs.getString(KEY_FILE_CHECKPOINT, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val cassetteIdRaw = json.optLong("cassetteId", -1L)
            FileCheckpoint(
                wavFilePath = json.getString("wavFilePath"),
                languageHint = json.optString("languageHint", "auto"),
                cassetteId = if (cassetteIdRaw < 0) null else cassetteIdRaw,
                processedChunks = json.optInt("processedChunks", 0),
                accumulatedText = json.optString("accumulatedText", ""),
                segmentsJson = json.optString("segmentsJson", "[]"),
                attemptStartChunk = json.optInt("attemptStartChunk", 0),
                attemptCount = json.optInt("attemptCount", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearFileCheckpoint() {
        prefs.edit().remove(KEY_FILE_CHECKPOINT).apply()
    }

    /**
     * Called right before a (re)attempt starts. Bumps attemptCount if we're
     * retrying from the same stuck chunk as last time, or resets it if
     * we've actually moved past that point since the last attempt began.
     */
    fun beginAttempt(checkpoint: FileCheckpoint): FileCheckpoint {
        val stillStuckAtSamePoint = checkpoint.processedChunks <= checkpoint.attemptStartChunk
        val updated = if (stillStuckAtSamePoint) {
            checkpoint.copy(attemptCount = checkpoint.attemptCount + 1)
        } else {
            checkpoint.copy(attemptStartChunk = checkpoint.processedChunks, attemptCount = 0)
        }
        saveFileCheckpoint(updated)
        return updated
    }

    // ---- Live recording checkpoint (best-effort recovery-into-memo) ---

    data class RecordingCheckpoint(
        val cassetteId: Long,
        val tempWavPath: String,
        val lastFlushedText: String,
        val flushedAtMs: Long
    )

    fun saveRecordingCheckpoint(checkpoint: RecordingCheckpoint) {
        val json = JSONObject().apply {
            put("cassetteId", checkpoint.cassetteId)
            put("tempWavPath", checkpoint.tempWavPath)
            put("lastFlushedText", checkpoint.lastFlushedText)
            put("flushedAtMs", checkpoint.flushedAtMs)
        }
        prefs.edit().putString(KEY_RECORDING_CHECKPOINT, json.toString()).apply()
    }

    fun loadRecordingCheckpoint(): RecordingCheckpoint? {
        val raw = prefs.getString(KEY_RECORDING_CHECKPOINT, null) ?: return null
        return try {
            val json = JSONObject(raw)
            RecordingCheckpoint(
                cassetteId = json.getLong("cassetteId"),
                tempWavPath = json.getString("tempWavPath"),
                lastFlushedText = json.optString("lastFlushedText", ""),
                flushedAtMs = json.optLong("flushedAtMs", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearRecordingCheckpoint() {
        prefs.edit().remove(KEY_RECORDING_CHECKPOINT).apply()
    }

    // ---- Segment (de)serialization helpers -----------------------------

    companion object {
        private const val KEY_FILE_CHECKPOINT = "file_checkpoint"
        private const val KEY_RECORDING_CHECKPOINT = "recording_checkpoint"

        /** Retry the same stuck chunk at most this many times before giving up. */
        const val MAX_RETRIES_AT_SAME_CHUNK = 2

        /** If a retry crashes again with fewer words than this since it began, stop retrying. */
        const val MIN_WORDS_TO_TRUST_PROGRESS = 5

        fun serializeSegments(segments: List<TranscriptionSegment>): String {
            val array = JSONArray()
            for (segment in segments) {
                val obj = JSONObject().apply {
                    put("text", segment.text)
                    put("startMs", segment.startMs)
                    put("endMs", segment.endMs)
                    put("detectedLanguage", segment.detectedLanguage ?: JSONObject.NULL)
                }
                array.put(obj)
            }
            return array.toString()
        }

        fun deserializeSegments(json: String): List<TranscriptionSegment> {
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    TranscriptionSegment(
                        text = obj.getString("text"),
                        startMs = obj.getLong("startMs"),
                        endMs = obj.getLong("endMs"),
                        detectedLanguage = obj.optString("detectedLanguage", null)
                            .takeIf { it.isNotEmpty() && it != "null" }
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
