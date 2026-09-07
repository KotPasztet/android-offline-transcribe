package com.voiceping.offlinetranscription.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.voiceping.offlinetranscription.service.AudioConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes any container Android's MediaExtractor/MediaCodec understands
 * (m4a/aac, mp3, ogg/opus, 3gp, etc.) into mono 16kHz Float32 PCM samples,
 * so it can be fed into the same pipeline as WAV files
 * (see [WhisperEngine.readWavFile] / [WavWriter]).
 *
 * WAV files should NOT be routed through this (MediaExtractor support for
 * raw PCM WAV containers is inconsistent across OEMs) — the app already has
 * a dedicated, reliable WAV reader for those.
 */
object AudioDecodeUtils {

    private const val TAG = "AudioDecodeUtils"
    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes the audio at [uri] and writes the result as a 16kHz mono
     * 16-bit PCM WAV file at [outputFile], ready for [WhisperEngine.transcribeFile].
     */
    fun decodeToWavFile(context: Context, uri: Uri, outputFile: File) {
        val samples = decodeToMonoFloatSamples(context, uri)
        WavWriter.write(samples, AudioConstants.SAMPLE_RATE, outputFile)
    }

    private fun decodeToMonoFloatSamples(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IllegalStateException("Could not open file descriptor for $uri")

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw IllegalStateException("No audio track found in $uri")
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val inputSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                AudioConstants.SAMPLE_RATE
            }
            val inputChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            Log.i(TAG, "decodeToMonoFloatSamples: mime=$mime rate=$inputSampleRate ch=$inputChannels")

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val rawMono = mutableListOf<Short>() // interleaved-free, already downmixed mono, at inputSampleRate
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            try {
                while (!sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)
                                ?: throw IllegalStateException("Null input buffer")
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIndex >= 0 -> {
                            val outputBuffer = codec.getOutputBuffer(outIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                val shortBuf = outputBuffer.asShortBuffer()
                                val frameCount = shortBuf.remaining() / inputChannels
                                for (i in 0 until frameCount) {
                                    if (inputChannels == 1) {
                                        rawMono.add(shortBuf.get())
                                    } else {
                                        // Downmix by averaging all channels of this frame.
                                        var sum = 0
                                        for (c in 0 until inputChannels) {
                                            sum += shortBuf.get()
                                        }
                                        rawMono.add((sum / inputChannels).toShort())
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEOS = true
                            }
                        }
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (sawInputEOS) {
                                // Give the decoder a little more time to flush before bailing.
                            }
                        }
                        else -> {
                            // INFO_OUTPUT_FORMAT_CHANGED or INFO_OUTPUT_BUFFERS_CHANGED: ignore,
                            // we only care about raw PCM16 samples.
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            val monoAtInputRate = ShortArray(rawMono.size) { rawMono[it] }
            val resampled = resampleTo16k(monoAtInputRate, inputSampleRate)
            return FloatArray(resampled.size) { resampled[it] / 32768f }
        } finally {
            extractor.release()
        }
    }

    /** Simple linear-interpolation resampler — adequate for speech transcription input. */
    private fun resampleTo16k(input: ShortArray, inputSampleRate: Int): ShortArray {
        val targetRate = AudioConstants.SAMPLE_RATE
        if (inputSampleRate == targetRate || input.isEmpty()) return input

        val ratio = inputSampleRate.toDouble() / targetRate.toDouble()
        val outputLength = (input.size / ratio).toInt().coerceAtLeast(1)
        val output = ShortArray(outputLength)
        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            val s0 = input[srcIndex.coerceIn(0, input.size - 1)]
            val s1 = input[(srcIndex + 1).coerceIn(0, input.size - 1)]
            output[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }
        return output
    }
}
