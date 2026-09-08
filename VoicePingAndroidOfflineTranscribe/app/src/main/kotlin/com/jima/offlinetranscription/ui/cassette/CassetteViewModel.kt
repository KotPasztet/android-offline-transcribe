package com.voiceping.offlinetranscription.ui.cassette

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceping.offlinetranscription.data.cassette.CassetteEntity
import com.voiceping.offlinetranscription.data.cassette.CassetteRepository
import com.voiceping.offlinetranscription.data.cassette.MemoEntity
import com.voiceping.offlinetranscription.service.SessionState
import com.voiceping.offlinetranscription.service.WhisperEngine
import com.voiceping.offlinetranscription.util.AudioDecodeUtils
import com.voiceping.offlinetranscription.util.CassetteAudioStorage
import com.voiceping.offlinetranscription.util.PendingShareHolder
import com.voiceping.offlinetranscription.util.TranscriptionCheckpointStore
import com.voiceping.offlinetranscription.util.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CassetteViewModel(
    private val context: Context,
    private val cassetteId: Long,
    private val engine: WhisperEngine,
    private val repository: CassetteRepository
) : ViewModel() {

    // Live engine state, reused directly (same as TranscriptionViewModel)
    // so recording / model switching / live tokens all "just work" here too.
    val isRecording = engine.isRecording
    val sessionState = engine.sessionState
    val confirmedText = engine.confirmedText
    val hypothesisText = engine.hypothesisText
    val bufferEnergy = engine.bufferEnergy
    val selectedModel = engine.selectedModel
    val fileTranscriptionProgress = engine.fileTranscriptionProgress
    val lastError = engine.lastError

    // True only while decoding/copying the picked file — before transcription
    // (and its own progress bar) even starts. Shown so the UI doesn't look
    // frozen during this phase now that it actually runs in the background.
    private val _isImportDecoding = MutableStateFlow(false)
    val isImportDecoding: StateFlow<Boolean> = _isImportDecoding.asStateFlow()

    val cassette: StateFlow<CassetteEntity?> = repository.observeCassette(cassetteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val memos: StateFlow<List<MemoEntity>> = repository.observeMemos(cassetteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // If this cassette screen was opened because the user picked it in
        // the "share a file into a cassette" dialog, pick up and consume
        // that pending file now.
        PendingShareHolder.pendingAudioUri?.let { uri ->
            PendingShareHolder.pendingAudioUri = null
            importFileAsMemo(uri)
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var recordingFlushJob: Job? = null

    fun startRecording() {
        engine.startRecording()
        recordingFlushJob?.cancel()
        // Periodically snapshot the in-progress recording to disk, so that if the
        // app process crashes mid-recording, the next launch can recover whatever
        // was captured so far as a finished memo instead of losing it outright.
        // (A literal "resume the live mic stream" isn't possible after a process
        // death — the AudioRecord session dies with it — so this is a
        // recover-into-memo safety net rather than a true resume.)
        recordingFlushJob = viewModelScope.launch {
            while (true) {
                delay(RECORDING_CHECKPOINT_INTERVAL_MS)
                if (engine.sessionState.value == SessionState.Idle) break
                if (engine.sessionState.value == SessionState.Recording) {
                    flushRecordingCheckpoint()
                }
            }
        }
    }

    /** Pauses the current recording — mic stops, but the memo-in-progress isn't finalized. */
    fun pauseRecording() {
        viewModelScope.launch {
            engine.pauseRecording()
            flushRecordingCheckpoint()
        }
    }

    /** Resumes a paused recording, continuing the same memo-in-progress. */
    fun resumeRecording() {
        engine.resumeRecording()
    }

    private fun flushRecordingCheckpoint() {
        val samples = engine.getRecordedSamplesSnapshot()
        if (samples.isEmpty()) return
        // Runs off the main thread — for a long recording this rewrites a
        // growing WAV file every 5s, which would otherwise stutter/freeze
        // the UI on every flush the longer the recording gets.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = recordingCheckpointFile()
                WavWriter.write(samples, com.voiceping.offlinetranscription.service.AudioConstants.SAMPLE_RATE, tempFile)
                engine.checkpointStore.saveRecordingCheckpoint(
                    TranscriptionCheckpointStore.RecordingCheckpoint(
                        cassetteId = cassetteId,
                        tempWavPath = tempFile.absolutePath,
                        lastFlushedText = engine.fullTranscriptionText,
                        flushedAtMs = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e("CassetteViewModel", "flushRecordingCheckpoint failed", e)
            }
        }
    }

    private fun recordingCheckpointFile(): File =
        File(context.cacheDir, "recording_checkpoint_$cassetteId.wav")

    /**
     * Stops the current live recording and persists it as a new memo on
     * this cassette, once the engine's async finalization has settled.
     *
     * Note: WhisperEngine finalizes the last bit of transcript slightly
     * asynchronously after stopRecording() returns (see its comments around
     * "realtimeLoop finally block"). We give it a short grace period rather
     * than exposing a completion callback from the engine, to keep this
     * change minimal. If you see the very last word occasionally missing
     * from a saved memo, increase FINALIZE_GRACE_MS below.
     */
    fun stopRecordingAndSaveMemo() {
        val durationSeconds = engine.recordingDurationSeconds
        engine.stopRecording()
        recordingFlushJob?.cancel()
        recordingFlushJob = null
        viewModelScope.launch {
            delay(FINALIZE_GRACE_MS)
            val samples = engine.getRecordedSamplesSnapshot()
            if (samples.isEmpty()) {
                engine.checkpointStore.clearRecordingCheckpoint()
                return@launch
            }

            // File write + copy off the main thread — for a long recording
            // this is potentially hundreds of MB, and doing it on Main would
            // freeze the UI right as the person taps "stop".
            val persisted = withContext(Dispatchers.IO) {
                val tempFile = File(context.cacheDir, "memo_recording_${System.currentTimeMillis()}.wav")
                WavWriter.write(samples, com.voiceping.offlinetranscription.service.AudioConstants.SAMPLE_RATE, tempFile)
                CassetteAudioStorage.persist(context, cassetteId, tempFile)
            }

            repository.addMemo(
                cassetteId = cassetteId,
                audioFilePath = persisted.absolutePath,
                transcriptText = engine.fullTranscriptionText,
                durationMs = (durationSeconds * 1000).toLong(),
                modelDisplayName = engine.selectedModel.value.displayName
            )
            // Memo saved successfully — the crash-recovery checkpoint for this
            // recording is no longer needed.
            engine.checkpointStore.clearRecordingCheckpoint()
            recordingCheckpointFile().delete()
        }
    }

    /**
     * Imports an audio file (any format m4a/mp3/wav/ogg/... via
     * AudioDecodeUtils) as a new memo, showing live progressive text and
     * progress via [fileTranscriptionProgress] / [confirmedText] while it runs.
     */
    fun importFileAsMemo(uri: Uri) {
        viewModelScope.launch {
            try {
                _isImportDecoding.value = true
                val timestamp = System.currentTimeMillis()
                val extension = guessExtension(uri)

                // Heavy lifting (file copy + CPU-bound MediaCodec decode) off the
                // main thread. This used to run directly inside viewModelScope.launch
                // (= Dispatchers.Main), so for a long file (e.g. 40 minutes of m4a)
                // the decode loop had no suspend points and froze the entire UI —
                // black screen, no navigation, nothing — until it finished. Running
                // it on Dispatchers.IO keeps the UI thread free the whole time.
                val wavFile = withContext(Dispatchers.IO) {
                    if (extension == ".wav") {
                        val cached = File(context.cacheDir, "import_$timestamp.wav")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            cached.outputStream().use { output -> input.copyTo(output) }
                        } ?: return@withContext null
                        cached
                    } else {
                        val decoded = File(context.cacheDir, "import_decoded_$timestamp.wav")
                        AudioDecodeUtils.decodeToWavFile(context, uri, decoded)
                        decoded
                    }
                }
                if (wavFile == null) {
                    _isImportDecoding.value = false
                    return@launch
                }

                val durationMs = withContext(Dispatchers.IO) { wavDurationMs(wavFile) }
                _isImportDecoding.value = false
                engine.transcribeFile(wavFile.absolutePath, cassetteId = cassetteId)

                // Poll progress until the (fire-and-forget) file transcription
                // finishes, then persist the memo. See WhisperEngine.fileTranscriptionProgress.
                // This loop only ever suspends on delay() — it never blocks the UI thread.
                var started = false
                while (true) {
                    val p = engine.fileTranscriptionProgress.value
                    if (p > 0f) started = true
                    if (started && p >= 1f) break
                    delay(150)
                }

                val persisted = withContext(Dispatchers.IO) {
                    CassetteAudioStorage.persist(context, cassetteId, wavFile)
                }
                repository.addMemo(
                    cassetteId = cassetteId,
                    audioFilePath = persisted.absolutePath,
                    transcriptText = engine.fullTranscriptionText,
                    durationMs = durationMs,
                    modelDisplayName = engine.selectedModel.value.displayName
                )
            } catch (e: Exception) {
                Log.e("CassetteViewModel", "importFileAsMemo failed", e)
            } finally {
                _isImportDecoding.value = false
            }
        }
    }

    fun deleteMemo(memo: MemoEntity) {
        viewModelScope.launch {
            repository.deleteMemo(memo)
            CassetteAudioStorage.deleteMemoFile(memo.audioFilePath)
        }
    }

    /** Clears the current error so the dialog showing it can be dismissed. */
    fun dismissError() {
        engine.clearError()
    }

    /**
     * Bundles the whole cassette (all memo audio + a combined transcript)
     * into a .zip and opens the system Share sheet for it.
     */
    fun exportAndShareCassette() {
        viewModelScope.launch {
            try {
                val currentCassette = cassette.value ?: repository.getCassette(cassetteId) ?: return@launch
                val currentMemos = memos.value
                val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.voiceping.offlinetranscription.util.CassetteExporter.exportCassette(
                        context, currentCassette, currentMemos
                    )
                }
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = android.content.Intent.createChooser(shareIntent, "Udostępnij kasetę")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Log.e("CassetteViewModel", "exportAndShareCassette failed", e)
            }
        }
    }

    fun playMemo(memo: MemoEntity, onCompletion: () -> Unit = {}) {
        stopPlayback()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(memo.audioFilePath)
                setOnCompletionListener { onCompletion() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("CassetteViewModel", "playMemo failed for ${memo.audioFilePath}", e)
        }
    }

    fun stopPlayback() {
        mediaPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        recordingFlushJob?.cancel()
        stopPlayback()
    }

    private fun guessExtension(uri: Uri): String {
        val type = context.contentResolver.getType(uri) ?: ""
        return when {
            type.contains("wav") -> ".wav"
            type.contains("mp3") || type.contains("mpeg") -> ".mp3"
            type.contains("m4a") || type.contains("mp4") -> ".m4a"
            type.contains("ogg") -> ".ogg"
            type.contains("flac") -> ".flac"
            else -> {
                val name = uri.lastPathSegment ?: ""
                val dot = name.lastIndexOf('.')
                if (dot >= 0) name.substring(dot) else ".wav"
            }
        }
    }

    private fun wavDurationMs(wavFile: File): Long {
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(wavFile.absolutePath)
            mp.prepare()
            val duration = mp.duration.toLong()
            mp.release()
            duration
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private const val FINALIZE_GRACE_MS = 400L
        private const val RECORDING_CHECKPOINT_INTERVAL_MS = 5000L
    }
}
