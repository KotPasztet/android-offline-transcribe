package com.voiceping.offlinetranscription.ui.cassette

import android.content.Context
import android.util.Log
import com.voiceping.offlinetranscription.data.cassette.CassetteRepository
import com.voiceping.offlinetranscription.service.WhisperEngine
import com.voiceping.offlinetranscription.util.CassetteAudioStorage
import kotlinx.coroutines.delay
import java.io.File

/**
 * Runs once, right after the model finishes loading at app startup, to
 * recover from a process crash that happened mid-transcription:
 *
 * - **File import crash**: if [WhisperEngine.checkpointStore] has an
 *   unfinished file-transcription checkpoint tied to a cassette, and it
 *   hasn't exhausted its retry budget (see [TranscriptionCheckpointStore]),
 *   we transparently resume transcribing from the last completed chunk and
 *   save the result as a memo once done. If the retry budget IS exhausted
 *   (e.g. it crashed again after producing almost no new text), we abandon
 *   it instead of relaunching the same crash forever.
 *
 * - **Live recording crash**: raw mic audio can't be "resumed" after a
 *   process death, but [CassetteViewModel] periodically flushes the
 *   in-progress recording to disk. If such a flush exists, we save
 *   whatever was captured as a completed memo so it isn't lost.
 */
object CrashRecoveryCoordinator {

    private const val TAG = "CrashRecoveryCoordinator"

    suspend fun recoverIfNeeded(context: Context, engine: WhisperEngine, repository: CassetteRepository) {
        recoverInterruptedRecording(context, engine, repository)
        recoverInterruptedFileImport(context, engine, repository)
    }

    private suspend fun recoverInterruptedRecording(
        context: Context,
        engine: WhisperEngine,
        repository: CassetteRepository
    ) {
        val checkpoint = engine.checkpointStore.loadRecordingCheckpoint() ?: return
        val tempFile = File(checkpoint.tempWavPath)
        if (!tempFile.exists() || tempFile.length() == 0L) {
            Log.i(TAG, "recoverInterruptedRecording: no audio file to recover, clearing checkpoint")
            engine.checkpointStore.clearRecordingCheckpoint()
            return
        }

        Log.i(TAG, "recoverInterruptedRecording: recovering crashed recording for cassette ${checkpoint.cassetteId}")
        try {
            val durationMs = mediaDurationMs(checkpoint.tempWavPath)
            val persisted = CassetteAudioStorage.persist(context, checkpoint.cassetteId, tempFile)
            repository.addMemo(
                cassetteId = checkpoint.cassetteId,
                audioFilePath = persisted.absolutePath,
                transcriptText = checkpoint.lastFlushedText +
                    "\n\n[Odzyskano po awarii aplikacji — nagranie mogło zostać ucięte]",
                durationMs = durationMs,
                modelDisplayName = engine.selectedModel.value.displayName
            )
        } catch (e: Exception) {
            Log.e(TAG, "recoverInterruptedRecording failed", e)
        } finally {
            engine.checkpointStore.clearRecordingCheckpoint()
        }
    }

    private suspend fun recoverInterruptedFileImport(
        context: Context,
        engine: WhisperEngine,
        repository: CassetteRepository
    ) {
        val checkpoint = engine.checkpointStore.loadFileCheckpoint() ?: return
        val cassetteId = checkpoint.cassetteId
        if (cassetteId == null) {
            // Not tied to a cassette (e.g. legacy/debug transcription) — nothing for
            // us to auto-save into; leave it for the classic screen to resume manually.
            return
        }
        if (!File(checkpoint.wavFilePath).exists()) {
            Log.w(TAG, "recoverInterruptedFileImport: source file missing, abandoning checkpoint")
            engine.checkpointStore.clearFileCheckpoint()
            return
        }
        if (!checkpoint.shouldAutoResume()) {
            Log.w(
                TAG,
                "recoverInterruptedFileImport: retry budget exhausted for ${checkpoint.wavFilePath}, " +
                    "abandoning instead of retrying again on startup"
            )
            engine.checkpointStore.clearFileCheckpoint()
            return
        }

        Log.i(TAG, "recoverInterruptedFileImport: resuming file transcription for cassette $cassetteId")
        engine.transcribeFile(checkpoint.wavFilePath, checkpoint.languageHint, cassetteId = cassetteId)

        // Wait for the (fire-and-forget) resumed transcription to finish, same
        // polling pattern used by CassetteViewModel.importFileAsMemo.
        var started = false
        var waitedMs = 0L
        while (waitedMs < MAX_WAIT_MS) {
            val p = engine.fileTranscriptionProgress.value
            if (p > 0f) started = true
            if (started && p >= 1f) break
            delay(150)
            waitedMs += 150
        }

        if (started && engine.fileTranscriptionProgress.value >= 1f) {
            val durationMs = mediaDurationMs(checkpoint.wavFilePath)
            val persisted = CassetteAudioStorage.persist(context, cassetteId, File(checkpoint.wavFilePath))
            repository.addMemo(
                cassetteId = cassetteId,
                audioFilePath = persisted.absolutePath,
                transcriptText = engine.fullTranscriptionText,
                durationMs = durationMs,
                modelDisplayName = engine.selectedModel.value.displayName
            )
        } else {
            Log.w(TAG, "recoverInterruptedFileImport: resumed transcription did not finish in time, will retry next launch")
        }
    }

    private fun mediaDurationMs(path: String): Long {
        return try {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            val d = mp.duration.toLong()
            mp.release()
            d
        } catch (e: Exception) {
            0L
        }
    }

    private const val MAX_WAIT_MS = 5 * 60_000L // don't block startup forever on a huge file
}
