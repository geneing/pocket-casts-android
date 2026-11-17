package au.com.shiftyjelly.pocketcasts.repositories.ads

import android.media.MediaExtractor
import androidx.core.net.toUri
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdFingerprintDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.models.to.AdFingerprint
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentKind
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentOrigin
import au.com.shiftyjelly.pocketcasts.models.to.DbAdSegment
import au.com.shiftyjelly.pocketcasts.repositories.fingerprint.StreamingMediaDataSource
import au.com.shiftyjelly.pocketcasts.repositories.playback.ExoPlayerDataSourceFactory
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Runs the on-device ad analysis for an episode.
 *
 * Downloaded episodes are decoded straight from the file. Streams follow the player's own cache:
 * ExoPlayer keeps minutes of audio buffered, so the pass runs ahead of the playhead and the result
 * is usually ready before playback reaches the segment. Analysis progress is persisted, so a
 * cancelled or partial pass resumes instead of starting over.
 */
@Singleton
class AudioAdAnalyzer @Inject constructor(
    private val episodeManager: EpisodeManager,
    private val adSegmentDao: AdSegmentDao,
    private val adAnalysisDao: AdAnalysisDao,
    private val adFingerprintDao: AdFingerprintDao,
    private val dataSourceFactory: Lazy<ExoPlayerDataSourceFactory>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var analysisJob: Job? = null

    fun analyze(episodeUuid: String) {
        val previous = analysisJob
        Timber.tag(TAG).d(
            "Analysis requested for %s (previous pass running=%s)",
            episodeUuid,
            previous?.isActive == true,
        )
        previous?.cancel()
        analysisJob = scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            // Let the cancelled pass release the mutex before the new one starts.
            runCatching { previous?.join() }
            mutex.withLock { runAnalysis(episodeUuid) }
        }
    }

    private suspend fun runAnalysis(episodeUuid: String) {
        val startedAt = System.currentTimeMillis()
        val job = currentCoroutineContext()[Job]
        val episode = episodeManager.findByUuid(episodeUuid) ?: run {
            Timber.tag(TAG).d("Episode %s is gone, nothing to analyse", episodeUuid)
            return
        }
        val state = adAnalysisDao.findState(episodeUuid)
        if (state?.isComplete == true) {
            Timber.tag(TAG).d("Episode %s was already analysed completely, skipping", episodeUuid)
            return
        }

        val localFile = episode.downloadedFilePath?.let(::File)?.takeIf { it.exists() }
        val isStream = localFile == null
        Timber.tag(TAG).d(
            "Episode %s: %s, duration=%dms, state(analyzedUntil=%dms, complete=%s)",
            episodeUuid,
            localFile?.let { "downloaded ${it.length() / 1024 / 1024}MB at ${it.absolutePath}" }
                ?: "stream ${episode.streamUrl?.take(80)} hls=${episode.isStreamUrlHls}",
            episode.durationMs,
            state?.analyzedUntilMs ?: 0L,
            state?.isComplete ?: false,
        )
        if (isStream && !waitForStreamBuffer(episodeUuid, job)) {
            Timber.tag(TAG).d("No buffered audio yet for $episodeUuid, skipping analysis pass")
            return
        }

        val startMs = ((state?.analyzedUntilMs ?: 0L) - CONTEXT_MS).coerceAtLeast(0L)
        val budgetMs = if (isStream) STREAM_BUDGET_MS else FILE_BUDGET_MS
        val deadline = System.currentTimeMillis() + budgetMs
        val isActive = { job?.isActive == true && System.currentTimeMillis() < deadline }
        Timber.tag(TAG).d("Pass window starts at %dms with a %ds budget", startMs, budgetMs / 1000)

        val opened = openExtractor(episode, localFile, isActive) ?: return
        val extractor = opened.extractor
        val source = opened.source

        val existing = adSegmentDao.findSegmentsForEpisode(episodeUuid).toMutableList()
        val fingerprints = adFingerprintDao.findForPodcast(episode.podcastUuid).toMutableList()
        val frames = mutableListOf<AudioFrame>()
        var featureExtractor: AudioFeatureExtractor? = null
        var formatChannels = -1
        var formatRate = -1
        var lastFrameMs = startMs
        var decodeCompleted = false
        var decodeStopReason = "not_started"

        LogBuffer.i(
            LogBuffer.TAG_PLAYBACK,
            "Ad analysis started for $episodeUuid at ${startMs}ms (stream=$isStream, existing=${existing.size})",
        )

        try {
            val result = AudioAdDecoder.decode(extractor, startMs, isActive) { samples, count, channels, sampleRate, positionMs ->
                if (featureExtractor == null || channels != formatChannels || sampleRate != formatRate) {
                    featureExtractor = AudioFeatureExtractor(sampleRate = sampleRate, channelCount = channels)
                        .also { it.reset(positionMs) }
                    formatChannels = channels
                    formatRate = sampleRate
                    Timber.tag(TAG).d("Feature extractor configured for %dHz x%d at %dms", sampleRate, channels, positionMs)
                }
                featureExtractor.accept(samples, count, positionMs) { frame ->
                    frames += frame
                    lastFrameMs = frame.positionMs
                    if (frames.size >= FLUSH_FRAMES) {
                        runBlocking { flush(frames, existing, fingerprints, episode, isFinal = false) }
                    }
                }
            }
            decodeCompleted = result.completed
            decodeStopReason = result.stopReason
            lastFrameMs = max(lastFrameMs, result.lastPositionMs)
            Timber.tag(TAG).d(
                "Decode pass %s: completed=%s stop=%s decoded=%dms in %d buffers, %dms wall",
                episodeUuid,
                result.completed,
                result.stopReason,
                result.decodedMs,
                result.buffers,
                result.wallMs,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Ad analysis failed for $episodeUuid")
        } finally {
            runCatching { extractor.release() }
            runCatching { source?.close() }
            Timber.tag(TAG).d(
                "Closed decoder for %s at %dms, %d frames buffered",
                episodeUuid,
                lastFrameMs,
                frames.size,
            )
        }

        withContext(NonCancellable) {
            flush(frames, existing, fingerprints, episode, isFinal = true)
            val knownEnded = episode.durationMs > 0 && lastFrameMs >= episode.durationMs - END_TOLERANCE_MS
            val isComplete = decodeCompleted && (!isStream || knownEnded)
            val analyzedUntil = max(lastFrameMs, existing.maxOfOrNull { it.endTimeMs } ?: 0L)
            adAnalysisDao.upsert(
                AdAnalysisState(
                    episodeUuid = episodeUuid,
                    analyzedUntilMs = analyzedUntil,
                    isComplete = isComplete,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            if (existing.isNotEmpty()) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Ad analysis found ${existing.size} segments for $episodeUuid")
            }
            LogBuffer.i(
                LogBuffer.TAG_PLAYBACK,
                "Ad analysis finished for $episodeUuid: analyzedUntil=${analyzedUntil}ms, " +
                    "segments=${existing.size}, complete=$isComplete, " +
                    "took=${System.currentTimeMillis() - startedAt}ms",
            )
            Timber.tag(TAG).d(
                "Summary for %s: decoded to %dms (%s), %d wall ms, segments=%d [%s], complete=%s",
                episodeUuid,
                lastFrameMs,
                decodeStopReason,
                System.currentTimeMillis() - startedAt,
                existing.size,
                existing.joinToString { segment ->
                    "${segment.startTimeMs / 1000}-${segment.endTimeMs / 1000}s:${segment.origin}:${segment.confidence}"
                },
                isComplete,
            )
        }
    }

    private suspend fun waitForStreamBuffer(episodeUuid: String, job: Job?): Boolean {
        val factory = dataSourceFactory.get()
        if (!factory.isCacheAvailable) {
            Timber.tag(TAG).d("Player cache is unavailable, cannot follow the stream")
            return false
        }
        var waited = 0L
        while (job?.isActive != false && waited < STREAM_WAIT_MS) {
            val cached = factory.cachedLengthAt(episodeUuid, 0, 1)
            if (cached > 0) {
                Timber.tag(TAG).d("Cache has %dKB for %s after %dms", cached / 1024, episodeUuid, waited)
                return true
            }
            delay(STREAM_WAIT_POLL_MS)
            waited += STREAM_WAIT_POLL_MS
        }
        val cached = factory.cachedLengthAt(episodeUuid, 0, 1)
        Timber.tag(TAG).d(
            "Cache has %dKB for %s after waiting %dms (gave up=%s)",
            cached / 1024,
            episodeUuid,
            waited,
            cached <= 0,
        )
        return cached > 0
    }

    private fun openExtractor(
        episode: PodcastEpisode,
        localFile: File?,
        isActive: () -> Boolean,
    ): OpenedAudio? {
        val extractor = MediaExtractor()
        var source: StreamingMediaDataSource? = null
        try {
            if (localFile != null) {
                extractor.setDataSource(localFile.absolutePath)
                Timber.tag(TAG).d("Opened local file for %s (%d tracks)", episode.uuid, extractor.trackCount)
            } else {
                val url = episode.streamUrl ?: run {
                    Timber.tag(TAG).d("No stream url for %s", episode.uuid)
                    return null
                }
                if (episode.isStreamUrlHls) {
                    Timber.tag(TAG).d("Skipping HLS stream for %s, not analysable this way", episode.uuid)
                    return null
                }
                val factory = dataSourceFactory.get()
                source = StreamingMediaDataSource(
                    dataSourceFactory = factory.blockingCacheFactory,
                    uri = url.toUri(),
                    cacheKey = episode.uuid,
                    isActive = isActive,
                    cachedLengthAt = { position, length -> factory.cachedLengthAt(episode.uuid, position, length) },
                )
                extractor.setDataSource(source)
                Timber.tag(TAG).d(
                    "Opened stream for %s (%d tracks, cached %dKB)",
                    episode.uuid,
                    extractor.trackCount,
                    factory.cachedLengthAt(episode.uuid, 0, 1) / 1024,
                )
            }
            return OpenedAudio(extractor, source)
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Couldn't open audio for ad analysis of ${episode.uuid}")
            runCatching { extractor.release() }
            runCatching { source?.close() }
            return null
        }
    }

    private suspend fun flush(
        frames: MutableList<AudioFrame>,
        existing: MutableList<DbAdSegment>,
        fingerprints: MutableList<AdFingerprint>,
        episode: PodcastEpisode,
        isFinal: Boolean,
    ) {
        if (frames.size < MIN_FRAMES) {
            Timber.tag(TAG).d("Flush skipped, only %d frames buffered (need %d)", frames.size, MIN_FRAMES)
            return
        }
        val cutoffMs = if (isFinal) frames.last().positionMs else frames.last().positionMs - TAIL_MS
        val stable = frames.filter { it.positionMs < cutoffMs }
        if (stable.size < MIN_FRAMES) {
            Timber.tag(TAG).d("Flush skipped, %d stable frames after the %dms tail", stable.size, TAIL_MS)
            return
        }

        val analysisStartedAt = System.currentTimeMillis()
        val analysis = AdSegmentAnalyzer.analyzeDetailed(stable)
        Timber.tag(TAG).d(
            "Analysis %d-%ds (%d frames, final=%s): %d boundaries, %d candidates, %d near misses, " +
                "rejected short=%d long=%d noCue=%d, took %dms",
            analysis.fromTimeMs / 1000,
            analysis.toTimeMs / 1000,
            stable.size,
            isFinal,
            analysis.boundaries.size,
            analysis.candidates.size,
            analysis.nearMisses.size,
            analysis.rejectedShort,
            analysis.rejectedLong,
            analysis.rejectedNoCue,
            System.currentTimeMillis() - analysisStartedAt,
        )
        analysis.boundaries.take(MAX_LOGGED_BOUNDARIES).forEach { boundary ->
            Timber.tag(TAG).d(
                "  boundary at %dms cues=%s score=%s",
                boundary.positionMs,
                boundary.cues,
                boundary.score,
            )
        }
        analysis.nearMisses.take(MAX_LOGGED_CANDIDATES).forEach { miss ->
            Timber.tag(TAG).d(
                "  near miss %d-%dms conf=%s cues=%s reasons=%s (threshold %s)",
                miss.startTimeMs,
                miss.endTimeMs,
                miss.confidence,
                miss.cues,
                miss.reasons,
                AdSegmentAnalyzer.MARKER_THRESHOLD,
            )
        }

        var nextIndex = (existing.maxOfOrNull { it.index } ?: -1) + 1
        val newSegments = mutableListOf<DbAdSegment>()
        val now = System.currentTimeMillis()

        for (candidate in analysis.candidates) {
            val overlapping = existing.firstOrNull { it.overlaps(candidate) }
            if (overlapping != null) {
                Timber.tag(TAG).d(
                    "  candidate %d-%dms skipped, already covered by segment %d (%d-%dms)",
                    candidate.startTimeMs,
                    candidate.endTimeMs,
                    overlapping.index,
                    overlapping.startTimeMs,
                    overlapping.endTimeMs,
                )
                continue
            }
            val signature = AdSignature.build(stable, candidate.startTimeMs)
            if (signature == null) {
                Timber.tag(TAG).d("  candidate %d-%dms has no signature, frames missing", candidate.startTimeMs, candidate.endTimeMs)
            }
            val repeat = signature?.let { findRepeat(it, candidate.durationMs, fingerprints) }
            val confidence = if (repeat != null) max(candidate.confidence, REPEAT_CONFIDENCE) else candidate.confidence
            val segment = DbAdSegment(
                index = nextIndex++,
                episodeUuid = episode.uuid,
                startTimeMs = candidate.startTimeMs,
                endTimeMs = candidate.endTimeMs,
                kind = AdSegmentKind.Ad,
                origin = if (repeat != null) AdSegmentOrigin.RepeatMatch else AdSegmentOrigin.AudioAnalysis,
                confidence = confidence,
                createdAt = now,
            )
            newSegments += segment
            existing += segment
            Timber.tag(TAG).d(
                "  keeping %d-%dms conf=%s cues=%s repeat=%s reasons=%s",
                candidate.startTimeMs,
                candidate.endTimeMs,
                confidence,
                candidate.cues,
                repeat != null,
                candidate.reasons,
            )

            if (repeat == null && signature != null && confidence >= SIGNATURE_CONFIDENCE) {
                val fingerprint = AdFingerprint(
                    fingerprint = UUID.randomUUID().toString(),
                    podcastUuid = episode.podcastUuid,
                    durationMs = candidate.durationMs,
                    signature = signature,
                    createdAt = now,
                )
                fingerprints += fingerprint
                adFingerprintDao.insert(fingerprint)
                Timber.tag(TAG).d(
                    "  stored signature for %d-%dms of ${episode.uuid}, ${fingerprints.size} known for podcast",
                    candidate.startTimeMs,
                    candidate.endTimeMs,
                )
            } else if (repeat != null) {
                Timber.tag(TAG).d("  matched a signature stored for podcast ${episode.podcastUuid}")
            }
        }
        if (newSegments.isNotEmpty()) {
            adSegmentDao.upsertSegments(newSegments)
            LogBuffer.i(
                LogBuffer.TAG_PLAYBACK,
                "Ad analysis saved ${newSegments.size} segments for ${episode.uuid} " +
                    "(confidence ${newSegments.minOf { it.confidence }}..${newSegments.maxOf { it.confidence }})",
            )
        }

        if (!isFinal) {
            val keepFrom = cutoffMs - RETENTION_MS
            val before = frames.size
            frames.removeAll { it.positionMs < keepFrom }
            Timber.tag(TAG).d(
                "Retained %d frames from %dms onwards (dropped %d)",
                frames.size,
                keepFrom,
                before - frames.size,
            )
        }
    }

    private fun findRepeat(signature: ByteArray, durationMs: Long, fingerprints: List<AdFingerprint>): AdFingerprint? {
        if (fingerprints.isEmpty()) return null
        var best: AdFingerprint? = null
        var bestScore = 0f
        var compared = 0
        for (fingerprint in fingerprints) {
            if (abs(fingerprint.durationMs - durationMs) > REPEAT_DURATION_TOLERANCE_MS) continue
            compared++
            val score = AdSignature.similarity(fingerprint.signature, signature)
            if (score > bestScore) {
                bestScore = score
                best = fingerprint
            }
        }
        Timber.tag(TAG).d(
            "Repeat match for %dms: compared %d of %d signatures, best=%s (threshold %s)",
            durationMs,
            compared,
            fingerprints.size,
            bestScore,
            REPEAT_SIMILARITY,
        )
        return best?.takeIf { bestScore >= REPEAT_SIMILARITY }
    }

    private fun DbAdSegment.overlaps(candidate: AdSegmentAnalyzer.Candidate): Boolean {
        val overlap = min(endTimeMs, candidate.endTimeMs) - max(startTimeMs, candidate.startTimeMs)
        if (overlap <= 0) return false
        val shortest = min(endTimeMs - startTimeMs, candidate.durationMs)
        return shortest > 0 && overlap.toFloat() / shortest >= OVERLAP_RATIO
    }

    private class OpenedAudio(
        val extractor: MediaExtractor,
        val source: StreamingMediaDataSource?,
    )

    private companion object {
        const val TAG = "AdAnalysis"
        const val CONTEXT_MS = 5_000L
        const val TAIL_MS = 2_500L
        const val RETENTION_MS = 8_000L
        const val MAX_LOGGED_BOUNDARIES = 12
        const val MAX_LOGGED_CANDIDATES = 8
        const val END_TOLERANCE_MS = 3_000L
        const val FILE_BUDGET_MS = 240_000L
        const val STREAM_BUDGET_MS = 45_000L
        const val STREAM_WAIT_MS = 20_000L
        const val STREAM_WAIT_POLL_MS = 500L
        const val MIN_FRAMES = 200
        const val FLUSH_FRAMES = 5_000
        const val OVERLAP_RATIO = 0.4f
        const val REPEAT_SIMILARITY = 0.985f
        const val REPEAT_DURATION_TOLERANCE_MS = 2_500L
        const val REPEAT_CONFIDENCE = 0.95f
        const val SIGNATURE_CONFIDENCE = 0.65f
    }
}
