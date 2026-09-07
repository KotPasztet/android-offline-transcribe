package com.voiceping.offlinetranscription.util

import android.content.Context
import java.io.File

/** Stable, permanent storage for cassette memo audio files (survives cache clears). */
object CassetteAudioStorage {

    private fun cassetteDir(context: Context, cassetteId: Long): File {
        val dir = File(File(context.filesDir, "cassettes"), cassetteId.toString())
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** A fresh, unique .wav path for a new memo on the given cassette. */
    fun newMemoFile(context: Context, cassetteId: Long): File {
        val dir = cassetteDir(context, cassetteId)
        return File(dir, "memo_${System.currentTimeMillis()}.wav")
    }

    /** Copies (or moves) a temp file into permanent per-cassette storage. */
    fun persist(context: Context, cassetteId: Long, tempFile: File): File {
        val dest = newMemoFile(context, cassetteId)
        tempFile.copyTo(dest, overwrite = true)
        tempFile.delete()
        return dest
    }

    fun deleteMemoFile(path: String) {
        runCatching { File(path).delete() }
    }

    fun deleteCassetteFiles(context: Context, cassetteId: Long) {
        runCatching { cassetteDir(context, cassetteId).deleteRecursively() }
    }
}
