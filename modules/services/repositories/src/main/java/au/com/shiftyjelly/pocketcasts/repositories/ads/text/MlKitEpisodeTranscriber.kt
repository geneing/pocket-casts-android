package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import au.com.shiftyjelly.pocketcasts.repositories.ads.AudioAdDecoder
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * On-device transcription through ML Kit's GenAI **Advanced** speech recognizer (Gemini Nano).
 *
 * The episode file is handed over whole with [AudioSource.fromFile], which is a ONE_SHOT source:
 * the SDK decodes the container itself and transcribes faster than real time. One-shot sources
 * cannot report word timings, so the recognizer returns its segments as ordered text with no
 * clock.
 *
 * Timings are therefore reconstructed from the audio: the file is decoded once with
 * [AudioAdDecoder] into 20 ms energy frames, a speech threshold is taken from the clip's own level
 * distribution, and the above-threshold frames are merged into [SpeechRegion]s. The ordered
 * segments are then mapped onto the ordered regions by [alignSegmentsToRegions].
 *
 * These cues are **speech-region accurate, not word accurate**: a segment's start is where speech
 * resumed around it, not where its first word was spoken. A pause inside a long segment shifts
 * that segment's boundaries towards its neighbours, and episodes whose audio has no usable dynamic
 * range collapse to one region covering the whole clip.
 */
class MlKitEpisodeTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) : EpisodeTranscriber {
    override val version: String = ENGINE

    /**
     * Last feature status seen by [transcribe]. [SpeechRecognizer.checkStatus] is a suspending call
     * into AI Core, so this cache keeps [isAvailable] cheap and non blocking. Before the first pass
     * the device is assumed usable so that the first analysis probes the model, which is also what
     * starts its download.
     */
    @Volatile
    private var featureStatus: Int? = null

    /**
     * The recognizer refuses to run without the record-audio permission, even when the audio comes
     * from a file, so an ungranted permission makes the feature unavailable rather than failing
     * every episode.
     */
    override fun isAvailable(): Boolean {
        if (featureStatus == FeatureStatus.UNAVAILABLE) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    override suspend fun transcribe(episodeUuid: String, source: TranscriptSource, durationMs: Long): TranscriptResult {
        if (source is TranscriptSource.Downloaded && !source.file.exists()) {
            return TranscriptResult.Failed("missing audio file")
        }
        return withContext(Dispatchers.IO) {
            try {
                val recognizer = SpeechRecognition.getClient(
                    speechRecognizerOptions {
                        locale = LOCALE
                        preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
                    },
                )
                try {
                    val status = recognizer.checkStatus()
                    featureStatus = status
                    LogBuffer.i(TAG, "ML Kit advanced speech status=$status for $episodeUuid")
                    val modelProblem = when (status) {
                        FeatureStatus.AVAILABLE -> null
                        FeatureStatus.DOWNLOADABLE -> downloadModel(recognizer)
                        FeatureStatus.DOWNLOADING -> awaitDownload(recognizer)
                        else -> TranscriptResult.Unavailable("advanced speech model unavailable (status=$status)")
                    }
                    if (modelProblem != null) return@withContext modelProblem
                    transcribeWithModel(episodeUuid, source, durationMs, recognizer)
                } finally {
                    runCatching { recognizer.close() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBuffer.e(TAG, e, "ML Kit transcription failed for $episodeUuid")
                TranscriptResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun transcribeWithModel(
        episodeUuid: String,
        source: TranscriptSource,
        durationMs: Long,
        recognizer: SpeechRecognizer,
    ): TranscriptResult {
        val startedAt = System.currentTimeMillis()
        val segments = recognize(source, recognizer)
        val recognizeMs = System.currentTimeMillis() - startedAt
        if (segments.isEmpty()) return TranscriptResult.Failed("recognizer returned no speech")

        val context = currentCoroutineContext()
        // Only a local file can be profiled; a stream has no audio here to measure, so its cues
        // fall back to being spread over the episode duration.
        val profile = (source as? TranscriptSource.Downloaded)
            ?.let { downloaded -> runCatching { profileSpeech(downloaded.file) { context.isActive } }.getOrNull() }
        context.ensureActive()
        if (profile == null) {
            LogBuffer.w(TAG, "No audio profile for $episodeUuid; falling back to proportional cue times")
        }
        val regions = profile?.let { detectSpeechRegions(it.levelsDb, it.frameDurationMs) }.orEmpty()
        val timelineMs = when {
            durationMs > 0L -> durationMs
            profile != null && profile.audioMs > 0L -> profile.audioMs
            else -> regions.lastOrNull()?.endMs ?: 0L
        }
        val cues = alignSegmentsToRegions(segments, regions, timelineMs)
        logAlignment(episodeUuid, segments, regions, cues, profile, timelineMs, recognizeMs)
        if (cues.isEmpty()) return TranscriptResult.Failed("could not place the transcript on the timeline")
        return TranscriptResult.Success(cues, version)
    }

    /** Runs one one-shot recognition over the source and returns its segments in speaking order. */
    private suspend fun recognize(source: TranscriptSource, recognizer: SpeechRecognizer): List<RecognizedSegment> {
        val input = when (source) {
            is TranscriptSource.Downloaded -> AudioSource.fromFile(source.file)
            is TranscriptSource.Stream -> AudioSource.fromUri(context, source.url.toUri())
        }
        val segments = ArrayList<RecognizedSegment>()
        try {
            // Word timing is not supported for one-shot sources, so recognitionOptions stay default.
            val request = speechRecognizerRequest { audioSource = input }
            recognizer.startRecognition(request).collect { response ->
                when (response) {
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        val text = response.text.trim()
                        if (text.isNotEmpty()) segments.add(RecognizedSegment(text))
                    }

                    is SpeechRecognizerResponse.ErrorResponse -> throw RecognitionFailure(
                        "recognizer error ${response.e.errorCode}: ${response.e.message ?: "unknown"}",
                    )

                    // End of the one-shot source: everything before this is the transcript.
                    is SpeechRecognizerResponse.CompletedResponse -> Unit

                    // No timings to use from partials or levels in a one-shot pass.
                    is SpeechRecognizerResponse.PartialTextResponse,
                    is SpeechRecognizerResponse.AudioLevelResponse,
                    -> Unit
                }
            }
        } finally {
            runCatching { input.close() }
        }
        return segments
    }

    /** Collects the model download so the first pass does not fail while the weights arrive. */
    private suspend fun downloadModel(recognizer: SpeechRecognizer): TranscriptResult? {
        LogBuffer.i(TAG, "Downloading the ML Kit advanced speech model")
        var lastLoggedMb = -1L
        try {
            recognizer.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted ->
                        LogBuffer.i(TAG, "Speech model download started, ${status.bytesToDownload} bytes")

                    is DownloadStatus.DownloadProgress -> {
                        val megabytes = status.totalBytesDownloaded / MEGABYTE
                        if (megabytes != lastLoggedMb) {
                            lastLoggedMb = megabytes
                            LogBuffer.i(TAG, "Speech model download: $megabytes MB")
                        }
                    }

                    is DownloadStatus.DownloadCompleted -> LogBuffer.i(TAG, "Speech model download completed")

                    is DownloadStatus.DownloadFailed -> throw RecognitionFailure(
                        "speech model download failed: ${status.e.message ?: "error ${status.e.errorCode}"}",
                    )
                }
            }
        } catch (e: RecognitionFailure) {
            return TranscriptResult.Failed(e.message ?: "speech model download failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return TranscriptResult.Failed("speech model download failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val status = recognizer.checkStatus()
        featureStatus = status
        return if (status == FeatureStatus.AVAILABLE) {
            null
        } else {
            TranscriptResult.Failed("speech model download finished with status=$status")
        }
    }

    /** Another pass, or AI Core itself, may be downloading the model already. */
    private suspend fun awaitDownload(recognizer: SpeechRecognizer): TranscriptResult? {
        val deadline = System.currentTimeMillis() + DOWNLOAD_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(DOWNLOAD_POLL_MS)
            val status = recognizer.checkStatus()
            featureStatus = status
            when (status) {
                FeatureStatus.AVAILABLE -> return null
                FeatureStatus.DOWNLOADABLE -> return downloadModel(recognizer)
                FeatureStatus.DOWNLOADING -> Unit
                else -> return TranscriptResult.Unavailable("advanced speech model unavailable (status=$status)")
            }
        }
        return TranscriptResult.Unavailable(
            "advanced speech model download still running after ${DOWNLOAD_WAIT_MS / 1000}s",
        )
    }

    /** Decodes the episode once and measures its per-frame level. */
    private fun profileSpeech(file: File, isActive: () -> Boolean): SpeechProfile? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val levels = ArrayList<Float>()
            var samplesPerFrame = 0
            var frameDurationMs = FRAME_MS.toLong()
            var frameSampleRate = 0
            var frameSamples = 0
            var sumSquares = 0.0
            val result = AudioAdDecoder.decode(
                extractor = extractor,
                startPositionMs = 0L,
                isActive = isActive,
            ) { samples, _, channelCount, sampleRate, _ ->
                // Frame width follows the decoder's output rate; a rate change starts a new frame.
                if (samplesPerFrame == 0 || sampleRate != frameSampleRate) {
                    frameSampleRate = sampleRate
                    samplesPerFrame = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
                    frameDurationMs = samplesPerFrame * 1000L / sampleRate
                    frameSamples = 0
                    sumSquares = 0.0
                }
                val frames = if (channelCount > 1) samples.size / channelCount else samples.size
                for (frame in 0 until frames) {
                    val sample: Int = if (channelCount > 1) {
                        var sum = 0
                        for (channel in 0 until channelCount) sum += samples[frame * channelCount + channel]
                        sum / channelCount
                    } else {
                        samples[frame].toInt()
                    }
                    val value = sample / PCM_FULL_SCALE
                    sumSquares += value * value
                    frameSamples++
                    if (frameSamples == samplesPerFrame) {
                        levels.add(levelDb(sumSquares / frameSamples))
                        sumSquares = 0.0
                        frameSamples = 0
                    }
                }
            }
            if (levels.isEmpty()) return null
            Timber.tag(TAG).d(
                "Profiled %s: %d frames of %dms from %dms of audio (%s)",
                file.name,
                levels.size,
                frameDurationMs,
                result.decodedMs,
                result.stopReason,
            )
            // A decode that stopped before the end covers only a prefix of the episode, so its
            // regions would pull the whole transcript into that prefix. No profile is better: the
            // alignment then falls back to spreading the text across the full duration.
            if (!result.completed) return null
            return SpeechProfile(levels.toFloatArray(), frameDurationMs, result.decodedMs)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun logAlignment(
        episodeUuid: String,
        segments: List<RecognizedSegment>,
        regions: List<SpeechRegion>,
        cues: List<TranscriptCue>,
        profile: SpeechProfile?,
        timelineMs: Long,
        recognizeMs: Long,
    ) {
        val speechMs = regions.sumOf { it.lengthMs }
        val coverage = if (profile != null && profile.audioMs > 0L) {
            speechMs.toDouble() / profile.audioMs
        } else {
            0.0
        }
        val medianRegionMs = if (regions.isEmpty()) 0L else regions.map { it.lengthMs }.sorted()[regions.size / 2]
        val mode = when {
            regions.isEmpty() -> "proportional-no-regions"
            segments.size == regions.size -> "one-to-one"
            else -> "proportional"
        }
        LogBuffer.i(
            TAG,
            "ML Kit transcribed $episodeUuid: ${segments.size} segments, ${regions.size} speech regions, " +
                "${cues.size} cues, recognition=${recognizeMs}ms, timeline=${timelineMs}ms, " +
                "coverage=${"%.0f".format(Locale.US, coverage * 100)}%, medianRegion=${medianRegionMs}ms, " +
                "mode=$mode",
        )
    }

    companion object {
        /** Feeds [au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState.sttVersion]. */
        const val ENGINE = "mlkit-advanced-1.0.0-alpha2"
        private const val TAG = "AdTextTranscriber"

        /**
         * Advanced mode supports en-US among others. The app has no per-episode language yet, so
         * every episode is recognised as US English.
         */
        private val LOCALE = Locale.US

        private const val DOWNLOAD_POLL_MS = 2_000L
        private const val DOWNLOAD_WAIT_MS = 5 * 60_000L
        private const val MEGABYTE = 1024L * 1024L
        private const val PCM_FULL_SCALE = 32768.0
    }
}

/** Marks a recognizer or model failure that should end the pass without a stack trace. */
private class RecognitionFailure(message: String) : Exception(message)

/** One final ASR segment: ordered text with no clock of its own. */
internal data class RecognizedSegment(val text: String)

/** A span of the episode the profiler believes is speech. */
internal data class SpeechRegion(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/** Frame energy levels and the width of one frame, in the order they were decoded. */
internal data class SpeechProfile(
    val levelsDb: FloatArray,
    val frameDurationMs: Long,
    val audioMs: Long,
)

/**
 * Places ordered ASR segments on the episode timeline using [regions].
 *
 * One region per segment is used when the counts agree, because that is the only case where the
 * profiler and the recognizer agree on where each utterance is. Otherwise the segments are spread
 * over the concatenated speech time in proportion to their text length, each one starting where
 * the previous ended. [speechToMedia] maps that back onto the episode, so cues stay inside the
 * speech regions, never overlap, and never pass [durationMs].
 *
 * With no regions at all the cues are spread across `[0, durationMs]` by text length instead.
 * Returns an empty list when there is neither a region nor a positive duration to place them on.
 */
internal fun alignSegmentsToRegions(
    segments: List<RecognizedSegment>,
    regions: List<SpeechRegion>,
    durationMs: Long,
): List<TranscriptCue> {
    val texts = segments.map { it.text.trim() }.filter { it.isNotEmpty() }
    if (texts.isEmpty()) return emptyList()
    val limitMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE
    val usable = mergeOverlapping(regions.filter { it.endMs > it.startMs }.sortedBy { it.startMs })

    if (usable.isEmpty()) {
        if (durationMs <= 0L) return emptyList()
        return spreadByText(texts, 0L, durationMs)
    }
    if (texts.size == usable.size) {
        return texts.mapIndexed { index, text ->
            val region = usable[index]
            val startMs = region.startMs.coerceIn(0L, limitMs)
            val endMs = region.endMs.coerceIn(startMs, limitMs)
            TranscriptCue(startMs, endMs, text)
        }
    }

    val totalSpeechMs = usable.sumOf { it.lengthMs }
    if (totalSpeechMs <= 0L) return emptyList()
    val totalWeight = texts.sumOf { it.length }.coerceAtLeast(1)
    var consumedSpeechMs = 0.0
    return texts.map { text ->
        val weight = text.length.toDouble() / totalWeight
        val startMs = speechToMedia(consumedSpeechMs, usable, preferNextRegion = true).coerceIn(0L, limitMs)
        consumedSpeechMs += weight * totalSpeechMs
        val endMs = speechToMedia(consumedSpeechMs, usable, preferNextRegion = false).coerceIn(startMs, limitMs)
        TranscriptCue(startMs, endMs, text)
    }
}

/** Spreads [texts] evenly by character count across `[startMs, endMs]`. */
private fun spreadByText(texts: List<String>, startMs: Long, endMs: Long): List<TranscriptCue> {
    val spanMs = (endMs - startMs).coerceAtLeast(0L)
    if (spanMs <= 0L) return emptyList()
    val totalWeight = texts.sumOf { it.length }.coerceAtLeast(1)
    var consumed = 0L
    return texts.map { text ->
        val weight = text.length.toDouble() / totalWeight
        val cueStart = startMs + consumed
        consumed += (weight * spanMs).toLong()
        val cueEnd = (startMs + consumed).coerceIn(cueStart, endMs)
        TranscriptCue(cueStart.coerceIn(startMs, endMs), cueEnd, text)
    }
}

/**
 * Maps a position in concatenated speech time back onto the episode timeline. A start that lands
 * exactly on a region boundary belongs to the next region, an end belongs to the current one.
 */
private fun speechToMedia(speechMs: Double, regions: List<SpeechRegion>, preferNextRegion: Boolean): Long {
    var remaining = speechMs
    for (region in regions) {
        val length = region.lengthMs.toDouble()
        val inside = if (preferNextRegion) remaining < length else remaining <= length
        if (inside) return (region.startMs + remaining).roundToLong()
        remaining -= length
    }
    return regions.last().endMs
}

private fun mergeOverlapping(regions: List<SpeechRegion>): List<SpeechRegion> {
    val merged = ArrayList<SpeechRegion>(regions.size)
    for (region in regions) {
        val last = merged.lastOrNull()
        if (last != null && region.startMs <= last.endMs) {
            merged[merged.lastIndex] = SpeechRegion(last.startMs, maxOf(last.endMs, region.endMs))
        } else {
            merged.add(region)
        }
    }
    return merged
}

/**
 * Groups frame levels into speech regions.
 *
 * A frame is speech when its level is above [speechThresholdDb], a threshold taken from the clip
 * itself so that quiet recordings are not treated as silence. Runs separated by less than
 * [MERGE_GAP_MS] are merged (a breath inside a sentence) and runs shorter than [MIN_REGION_MS] are
 * dropped (a click).
 */
internal fun detectSpeechRegions(levelsDb: FloatArray, frameDurationMs: Long): List<SpeechRegion> {
    if (levelsDb.isEmpty() || frameDurationMs <= 0L) return emptyList()
    val thresholdDb = speechThresholdDb(levelsDb)
    val runs = ArrayList<SpeechRegion>()
    var runStart = -1
    for (index in levelsDb.indices) {
        val isSpeech = levelsDb[index] > thresholdDb
        if (isSpeech && runStart < 0) runStart = index
        if (!isSpeech && runStart >= 0) {
            runs.add(SpeechRegion(runStart * frameDurationMs, index * frameDurationMs))
            runStart = -1
        }
    }
    if (runStart >= 0) runs.add(SpeechRegion(runStart * frameDurationMs, levelsDb.size * frameDurationMs))

    val merged = ArrayList<SpeechRegion>(runs.size)
    for (run in runs) {
        val last = merged.lastOrNull()
        if (last != null && run.startMs - last.endMs < MERGE_GAP_MS) {
            merged[merged.lastIndex] = SpeechRegion(last.startMs, run.endMs)
        } else {
            merged.add(run)
        }
    }
    return merged.filter { it.lengthMs >= MIN_REGION_MS }
}

/**
 * Speech threshold in dBFS from the clip's own distribution: 15th and 90th percentiles bound the
 * usable dynamic range, and speech starts [THRESHOLD_FRACTION] of the way up it. A clip flatter
 * than [MIN_SPREAD_DB] has no silence to find, so the threshold sits just under its top level and
 * the whole clip counts as speech.
 */
internal fun speechThresholdDb(levelsDb: FloatArray): Float {
    if (levelsDb.isEmpty()) return Float.NEGATIVE_INFINITY
    val sorted = levelsDb.sortedArray()
    val low = percentile(sorted, LOW_PERCENTILE)
    val high = percentile(sorted, HIGH_PERCENTILE)
    val spread = high - low
    return if (spread < MIN_SPREAD_DB) high - UNIFORM_MARGIN_DB else low + THRESHOLD_FRACTION * spread
}

private fun percentile(sorted: FloatArray, fraction: Double): Float {
    if (sorted.isEmpty()) return 0f
    val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.lastIndex)
    return sorted[index]
}

private fun levelDb(meanSquare: Double): Float = (10.0 * log10(meanSquare.coerceAtLeast(MIN_MEAN_SQUARE))).toFloat()

/** Energy window for [detectSpeechRegions]; short enough to place a sentence start. */
private const val FRAME_MS = 20
private const val MERGE_GAP_MS = 400L
private const val MIN_REGION_MS = 200L
private const val LOW_PERCENTILE = 0.15
private const val HIGH_PERCENTILE = 0.90
private const val THRESHOLD_FRACTION = 0.35f
private const val MIN_SPREAD_DB = 6f
private const val UNIFORM_MARGIN_DB = 6f

/** Floors dBFS at -100 so silence still has a comparable level. */
private const val MIN_MEAN_SQUARE = 1e-10
