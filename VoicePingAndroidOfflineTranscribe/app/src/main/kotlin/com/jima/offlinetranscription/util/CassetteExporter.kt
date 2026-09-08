package com.voiceping.offlinetranscription.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.voiceping.offlinetranscription.data.cassette.CassetteEntity
import com.voiceping.offlinetranscription.data.cassette.MemoEntity
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles a whole cassette (all memo audio files + a combined transcript)
 * into a single .zip so it can be shared via Android's Share sheet
 * (e.g. to send a full cassette to someone, or back it up manually).
 */
object CassetteExporter {

    fun exportCassette(context: Context, cassette: CassetteEntity, memos: List<MemoEntity>): Uri {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = cassette.name
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "_")
            .trim()
            .ifBlank { "kaseta" }
        val zipFile = File(exportsDir, "${safeName}_${System.currentTimeMillis()}.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // Combined transcript, in memo order.
            val transcript = buildString {
                append("Kaseta: ${cassette.name}\n")
                append("Liczba nagrań: ${memos.size}\n\n")
                memos.forEachIndexed { index, memo ->
                    append("--- Nagranie ${index + 1} ---\n")
                    append("Czas trwania: ${FormatUtils.formatDuration(memo.durationMs / 1000.0)}\n")
                    append("Model: ${memo.modelDisplayName}\n\n")
                    append(memo.transcriptText.ifBlank { "(brak transkrypcji)" })
                    append("\n\n")
                }
            }
            zos.putNextEntry(ZipEntry("transkrypcja.txt"))
            zos.write(transcript.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Audio files, numbered in the same order as the transcript.
            memos.forEachIndexed { index, memo ->
                val audioFile = File(memo.audioFilePath)
                if (audioFile.exists()) {
                    zos.putNextEntry(ZipEntry("audio/%02d_nagranie.wav".format(index + 1)))
                    audioFile.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
    }
}
