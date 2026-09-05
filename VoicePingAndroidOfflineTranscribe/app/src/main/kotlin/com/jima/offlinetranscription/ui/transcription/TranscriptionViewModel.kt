package com.voiceping.offlinetranscription.ui.transcription

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceping.offlinetranscription.model.AudioInputMode
import com.voiceping.offlinetranscription.model.ModelInfo
import com.voiceping.offlinetranscription.service.WhisperEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class TranscriptionViewModel(
    val engine: WhisperEngine
) : ViewModel() {

    val isRecording = engine.isRecording
    val confirmedText = engine.confirmedText
    val hypothesisText = engine.hypothesisText
    val bufferEnergy = engine.bufferEnergy
    val bufferSeconds = engine.bufferSeconds
    val tokensPerSecond = engine.tokensPerSecond
    val lastError = engine.lastError
    val selectedModel = engine.selectedModel
    val modelState = engine.modelState
    val useVAD = engine.useVAD
    val enableTimestamps = engine.enableTimestamps
    val audioInputMode = engine.audioInputMode
    val systemAudioCaptureReady = engine.systemAudioCaptureReady
    val isSystemAudioCaptureSupported: Boolean
        get() = engine.isSystemAudioCaptureSupported
    val cpuPercent = engine.cpuPercent
    val memoryMB = engine.memoryMB
    val e2eResult = engine.e2eResult

    // Translation state
    val translationEnabled = engine.translationEnabled
    val translationSourceLanguage = engine.translationSourceLanguageCode
    val translationTargetLanguage = engine.translationTargetLanguageCode
    val translatedConfirmedText = engine.translatedConfirmedText
    val translatedHypothesisText = engine.translatedHypothesisText
    val translationWarning = engine.translationWarning
    val translationModelReady = engine.translationModelReady
    val translationDownloadStatus = engine.translationDownloadStatus

    val fullText: String
        get() = engine.fullTranscriptionText

    private inline fun launchEngineAction(crossinline block: suspend () -> Unit): Job {
        return viewModelScope.launch {
            block()
        }
    }

    private fun copyAssetIfMissing(context: Context, assetName: String): File {
        val cached = File(context.cacheDir, assetName)
        if (cached.exists()) return cached
        context.assets.open(assetName).use { input ->
            cached.outputStream().use { output -> input.copyTo(output) }
        }
        return cached
    }

    fun toggleRecording() {
        if (engine.isRecording.value) {
            engine.stopRecording()
        } else {
            startRecordingWithPreparation()
        }
    }

    fun startRecordingWithPreparation() {
        launchEngineAction {
            engine.prewarmRealtimePath()
            engine.startRecording()
        }
    }

    fun prewarmOnScreenOpen() {
        launchEngineAction {
            engine.prewarmRealtimePath()
        }
    }

    fun clearTranscription() {
        engine.clearTranscription()
    }

    fun setAudioInputMode(mode: AudioInputMode) {
        engine.setAudioInputMode(mode)
    }

    fun setSystemAudioCapturePermission(resultCode: Int, data: Intent?) {
        engine.setSystemAudioCapturePermission(resultCode, data)
    }

    /** Dismiss error without clearing transcription text. */
    fun dismissError() {
        engine.clearError()
    }

    fun transcribeTestFile(filePath: String) {
        engine.transcribeFile(filePath)
    }

    fun transcribeTestAsset(context: Context) {
        val cached = copyAssetIfMissing(context, "test_speech.wav")
        engine.transcribeFile(cached.absolutePath)
    }

    /**
     * Handles a user-picked audio file (from the system file/document picker).
     * Content URIs cannot be read directly by the native engines, so the file
     * is first copied into the app cache dir, then transcribed the same way
     * as the bundled test asset.
     */
    fun transcribeFromUri(context: Context, uri: Uri) {
        launchEngineAction {
            try {
                val extension = guessExtension(context, uri)
                val cached = File(context.cacheDir, "picked_audio_${System.currentTimeMillis()}$extension")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cached.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    Log.e("TranscriptionViewModel", "transcribeFromUri: could not open input stream for $uri")
                    return@launchEngineAction
                }
                engine.transcribeFile(cached.absolutePath)
            } catch (e: Exception) {
                Log.e("TranscriptionViewModel", "transcribeFromUri failed", e)
            }
        }
    }

    private fun guessExtension(context: Context, uri: Uri): String {
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

    /** Writes the current full transcription text to a user-chosen destination Uri. */
    fun saveTranscriptionToUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(fullText.toByteArray())
            }
        } catch (e: Exception) {
            Log.e("TranscriptionViewModel", "saveTranscriptionToUri failed", e)
        }
    }

    fun stopIfRecording() {
        if (engine.isRecording.value) {
            engine.stopRecording()
        }
    }

    fun switchModel(model: ModelInfo) {
        launchEngineAction {
            engine.switchModel(model)
        }
    }

    fun setUseVAD(enabled: Boolean) {
        launchEngineAction {
            engine.setUseVAD(enabled)
        }
    }

    fun setEnableTimestamps(enabled: Boolean) {
        launchEngineAction {
            engine.setEnableTimestamps(enabled)
        }
    }

    fun setTranslationEnabled(enabled: Boolean) {
        launchEngineAction {
            engine.setTranslationEnabled(enabled)
        }
    }

    fun setTranslationSourceLanguageCode(languageCode: String) {
        launchEngineAction {
            engine.setTranslationSourceLanguageCode(languageCode)
        }
    }

    fun setTranslationTargetLanguageCode(languageCode: String) {
        launchEngineAction {
            engine.setTranslationTargetLanguageCode(languageCode)
        }
    }
}
