package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Offline transcription through the platform's on-device recognizer.
 *
 * The episode is decoded once to raw 16-bit mono PCM on disk (memory stays at one chunk), then
 * fed to the recognizer in 15 s chunks through [RecognizerIntent.EXTRA_AUDIO_SOURCE]. Chunk
 * timestamps are shifted into the episode timeline.
 *
 * Availability is device dependent: Pixel-class devices expose an on-device recognizer. A chunk
 * with no speech is normal; a fatal recognizer error fails the whole pass so the analyzer does
 * not persist a partial transcript or mark the episode complete.
 */
class SpeechRecognizerEpisodeTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) : EpisodeTranscriber {
    override val version: String = TranscriptionChunkLoop.ENGINE

    override fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    override suspend fun transcribe(episodeUuid: String, file: File, durationMs: Long): TranscriptResult {
        if (!isAvailable()) return TranscriptResult.Unavailable("no on-device recognizer")
        if (!file.exists()) return TranscriptResult.Failed("missing audio file")
        return withContext(Dispatchers.IO) {
            val pcmFile = File.createTempFile("ad-stt-", ".pcm", context.cacheDir)
            try {
                val decoded = decodeToPcm(file, pcmFile)
                if (decoded == null) return@withContext TranscriptResult.Failed("could not decode audio")
                val (sampleRate, frames) = decoded
                val chunkFrames = sampleRate * CHUNK_SECONDS
                val chunkCount = (frames + chunkFrames - 1) / chunkFrames
                val input = RandomAccessFile(pcmFile, "r")
                val loop = TranscriptionChunkLoop(
                    recognize = { chunk -> recognizeChunk(chunk, sampleRate, episodeUuid) },
                    close = { runCatching { input.close() } },
                )
                loop.run(chunkCount) { index ->
                    val startFrame = index.toLong() * chunkFrames
                    val count = minOf(chunkFrames.toLong(), frames - startFrame).toInt()
                    val bytes = ByteArray(count * 2)
                    input.seek(startFrame * 2)
                    input.readFully(bytes)
                    val samples = ShortArray(count)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                    PcmChunk(samples, startFrame * 1000L / sampleRate)
                }
            } catch (e: Exception) {
                LogBuffer.e(LOG_TAG, e, "on-device transcription failed")
                TranscriptResult.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                pcmFile.delete()
            }
        }
    }

    private suspend fun recognizeChunk(
        chunk: PcmChunk,
        sampleRate: Int,
        episodeUuid: String,
    ): ChunkResult = withContext(Dispatchers.Main) {
        val pcmFile = File.createTempFile("ad-stt-", ".pcm", context.cacheDir)
        var descriptor: ParcelFileDescriptor? = null
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            writePcm(pcmFile, chunk.samples)
            descriptor = ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val chunkMs = chunk.samples.size * 1000L / sampleRate
            val result = suspendCancellableCoroutine { cont ->
                val cues = ArrayList<ChunkCue>()
                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onError(error: Int) {
                        val outcome = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            -> ChunkResult.NoSpeech

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ChunkResult.Unavailable

                            else -> ChunkResult.Fatal("recognizer error $error")
                        }
                        if (cont.isActive) cont.resume(outcome)
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.joinToString(" ")
                            ?.trim()
                            .orEmpty()
                        if (text.isNotEmpty()) cues.add(ChunkCue(0L, chunkMs, text))
                        if (cont.isActive) cont.resume(if (cues.isEmpty()) ChunkResult.NoSpeech else ChunkResult.Speech(cues.toList()))
                    }

                    override fun onSegmentResults(segmentResults: Bundle) {
                        val texts = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                        if (texts.isEmpty()) return
                        // The platform bundle carries no per-segment clock on all builds, so spread
                        // the chunk evenly across the returned segments.
                        val step = chunkMs / texts.size
                        texts.forEachIndexed { index, text ->
                            val start = index * step
                            val end = if (index == texts.lastIndex) chunkMs else start + step
                            cues.add(ChunkCue(start, end, text))
                        }
                    }

                    override fun onEndOfSegmentedSession() {
                        if (cont.isActive) cont.resume(if (cues.isEmpty()) ChunkResult.NoSpeech else ChunkResult.Speech(cues.toList()))
                    }
                }
                recognizer.setRecognitionListener(listener)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
                }
                runCatching { recognizer.startListening(intent) }
                    .onFailure { if (cont.isActive) cont.resume(ChunkResult.Unavailable) }
                cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
            }
            result
        } catch (e: Exception) {
            LogBuffer.e(LOG_TAG, e, "chunk recognition failed for $episodeUuid")
            ChunkResult.Fatal(e.message ?: "chunk failed")
        } finally {
            runCatching { recognizer.destroy() }
            runCatching { descriptor?.close() }
            pcmFile.delete()
        }
    }

    /** Decode the first audio track to mono PCM16 on disk; returns (sampleRate, frames). */
    private fun decodeToPcm(source: File, target: File): Pair<Int, Int>? {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var frames = 0
        RandomAccessFile(target, "rw").use { out ->
            out.setLength(0)
            try {
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val samples = ShortArray(shorts.remaining()).also { shorts.get(it) }
                            val mono = downmix(samples, channels)
                            val bytes = ByteBuffer.allocate(mono.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                            mono.forEach { bytes.putShort(it) }
                            out.write(bytes.array())
                            frames += mono.size
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                runCatching { extractor.release() }
            }
        }
        return sampleRate to frames
    }

    private fun downmix(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        val frames = samples.size / channels
        return ShortArray(frames) { frame ->
            var sum = 0
            for (c in 0 until channels) sum += samples[frame * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Raw little-endian PCM; the recognizer learns the format from the intent extras. */
    private fun writePcm(file: File, samples: ShortArray) {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        file.writeBytes(bytes.array())
    }

    companion object {
        private const val LOG_TAG = "AdTextTranscriber"
        private const val CHUNK_SECONDS = 15
    }
}
