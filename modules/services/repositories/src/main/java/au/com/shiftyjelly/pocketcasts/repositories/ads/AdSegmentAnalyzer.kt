package au.com.shiftyjelly.pocketcasts.repositories.ads

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Segments a decoded episode into candidate advertising blocks from acoustic evidence only.
 *
 * The detector looks for the joins that separate inserted material from the show: short silences
 * with loud audio on both sides, step changes in level and spectral shape, and stereo/mono flips.
 * Each candidate is scored, and only candidates with an actual boundary cue can reach the marker
 * threshold, which keeps long stretches of ordinary talk from being labelled.
 */
internal object AdSegmentAnalyzer {
    const val MARKER_THRESHOLD = 0.45f
    const val AUTO_SKIP_THRESHOLD = 0.7f

    const val MIN_SEGMENT_MS = 12_000L
    const val MAX_SEGMENT_MS = 180_000L

    private const val WINDOW_MS = 1_000L
    private const val MERGE_DISTANCE_MS = 400L
    private const val SILENCE_DB = -42f
    private const val LOUD_DB = -30f
    private const val SILENCE_MIN_MS = 40L
    private const val SILENCE_MAX_MS = 600L
    private const val LEVEL_STEP_DB = 3.0f
    private const val LEVEL_STABLE_STD_DB = 4.0f
    private const val MEL_DISTANCE = 0.25f
    private const val CORRELATION_STEP = 0.5f

    enum class CueKind {
        Silence,
        Level,
        Spectral,
        Channel,
    }

    data class Candidate(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val confidence: Float,
        val cues: Set<CueKind>,
        val reasons: List<String>,
    ) {
        val durationMs: Long get() = endTimeMs - startTimeMs
    }

    /** A join the detector believes separates two pieces of audio, with the evidence behind it. */
    data class BoundaryInfo(
        val positionMs: Long,
        val score: Float,
        val cues: Set<CueKind>,
    )

    /**
     * Everything one analysis pass saw, so the caller can log why it produced the segments it did.
     * [nearMisses] are windows that scored below [MARKER_THRESHOLD] but close enough to matter when
     * tuning the evidence weights.
     */
    data class Analysis(
        val candidates: List<Candidate>,
        val nearMisses: List<Candidate>,
        val boundaries: List<BoundaryInfo>,
        val fromTimeMs: Long,
        val toTimeMs: Long,
        val rejectedShort: Int,
        val rejectedLong: Int,
        val rejectedNoCue: Int,
    ) {
        val analyzedMs: Long get() = toTimeMs - fromTimeMs
    }

    private const val NEAR_MISS_THRESHOLD = 0.25f

    private data class Boundary(
        val positionMs: Long,
        val score: Float,
        val cues: MutableSet<CueKind>,
    )

    private class Window(
        val meanDb: Float,
        val stdDb: Float,
        val mel: FloatArray,
        val correlation: Float,
    )

    fun analyze(frames: List<AudioFrame>): List<Candidate> = analyzeDetailed(frames).candidates

    fun analyzeDetailed(frames: List<AudioFrame>): Analysis {
        val fromTimeMs = frames.firstOrNull()?.positionMs ?: 0L
        val toTimeMs = frames.lastOrNull()?.positionMs ?: 0L
        if (frames.size < 60) {
            return Analysis(emptyList(), emptyList(), emptyList(), fromTimeMs, toTimeMs, 0, 0, 0)
        }

        val boundaries = mutableListOf<Boundary>()
        boundaries += silenceBridges(frames)
        boundaries += stepBoundaries(frames)
        val merged = mergeBoundaries(boundaries)
        val boundaryInfos = merged.sortedBy { it.positionMs }.map { boundary ->
            BoundaryInfo(boundary.positionMs, boundary.score, boundary.cues.toSet())
        }
        if (merged.isEmpty()) {
            return Analysis(emptyList(), emptyList(), emptyList(), fromTimeMs, toTimeMs, 0, 0, 0)
        }

        val sorted = merged.sortedBy { it.positionMs }
        val candidates = mutableListOf<Candidate>()
        val nearMisses = mutableListOf<Candidate>()
        var rejectedShort = 0
        var rejectedLong = 0
        var rejectedNoCue = 0
        val edges: List<Boundary?> = listOf<Boundary?>(null) + sorted + listOf<Boundary?>(null)

        for (index in 0 until edges.size - 1) {
            val startBoundary = edges[index]
            val endBoundary = edges[index + 1]
            val startMs = startBoundary?.positionMs ?: frames.first().positionMs
            val endMs = endBoundary?.positionMs ?: frames.last().positionMs
            val durationMs = endMs - startMs
            if (durationMs < MIN_SEGMENT_MS) {
                rejectedShort++
                continue
            }
            if (durationMs > MAX_SEGMENT_MS) {
                rejectedLong++
                continue
            }

            val segmentFrames = frames.filter { it.positionMs >= startMs && it.positionMs < endMs }
            if (segmentFrames.size < 20) {
                rejectedShort++
                continue
            }

            val startCues = startBoundary?.cues.orEmpty()
            val endCues = endBoundary?.cues.orEmpty()
            val hasBoundaryCue = startCues.isNotEmpty() || endCues.isNotEmpty()
            if (!hasBoundaryCue) {
                rejectedNoCue++
                continue
            }

            var score = 0.05f
            val reasons = mutableListOf<String>()
            if (CueKind.Silence in startCues) {
                score += 0.22f
                reasons += "silence_at_start"
            }
            if (CueKind.Silence in endCues) {
                score += 0.15f
                reasons += "silence_at_end"
            }
            if (CueKind.Level in startCues) {
                score += 0.15f
                reasons += "level_step_at_start"
            }
            if (CueKind.Spectral in startCues) {
                score += 0.08f
                reasons += "spectral_step_at_start"
            }
            if (CueKind.Channel in startCues || CueKind.Channel in endCues) {
                score += 0.12f
                reasons += "stereo_to_mono"
            }
            if (startBoundary != null) {
                score += 0.05f * startBoundary.score
            }
            if (endBoundary != null) {
                score += 0.03f * endBoundary.score
            }

            val durationPrior = durationPrior(durationMs)
            if (durationPrior > 0f) {
                score += 0.08f
                reasons += "creative_length"
            }
            if (melStability(segmentFrames) > 0.85f) {
                score += 0.05f
                reasons += "steady_spectrum"
            }
            if (durationMs > 120_000 && CueKind.Silence !in startCues && CueKind.Level !in startCues) {
                score -= 0.15f
            }

            val confidence = score.coerceIn(0f, 1f)
            val candidate = Candidate(
                startTimeMs = startMs,
                endTimeMs = endMs,
                confidence = confidence,
                cues = startCues + endCues,
                reasons = reasons,
            )
            when {
                confidence >= MARKER_THRESHOLD -> candidates += candidate
                confidence >= NEAR_MISS_THRESHOLD -> nearMisses += candidate
            }
        }
        return Analysis(
            candidates = candidates,
            nearMisses = nearMisses,
            boundaries = boundaryInfos,
            fromTimeMs = fromTimeMs,
            toTimeMs = toTimeMs,
            rejectedShort = rejectedShort,
            rejectedLong = rejectedLong,
            rejectedNoCue = rejectedNoCue,
        )
    }

    private fun durationPrior(durationMs: Long): Float {
        val typical = longArrayOf(15_000L, 30_000L, 45_000L, 60_000L)
        return if (typical.any { abs(it - durationMs) <= 2_000L }) 1f else 0f
    }

    private fun silenceBridges(frames: List<AudioFrame>): List<Boundary> {
        val boundaries = mutableListOf<Boundary>()
        var index = 0
        while (index < frames.size) {
            if (frames[index].rmsDb > SILENCE_DB) {
                index++
                continue
            }
            var end = index
            while (end < frames.size && frames[end].rmsDb <= SILENCE_DB) end++
            val duration = frames[end - 1].positionMs - frames[index].positionMs
            if (duration in SILENCE_MIN_MS..SILENCE_MAX_MS && index > 0 && end < frames.size) {
                val before = meanDb(frames, (index - 5).coerceAtLeast(0), index)
                val after = meanDb(frames, end, (end + 5).coerceAtMost(frames.size))
                if (before > LOUD_DB && after > LOUD_DB) {
                    boundaries += Boundary(
                        positionMs = frames[end - 1].positionMs,
                        score = 0.8f,
                        cues = mutableSetOf(CueKind.Silence),
                    )
                }
            }
            index = end
        }
        return boundaries
    }

    private fun stepBoundaries(frames: List<AudioFrame>): List<Boundary> {
        val windowFrames = (WINDOW_MS / hopMs(frames)).coerceAtLeast(8L).toInt()
        val prefix = PrefixSums(frames)
        val boundaries = mutableListOf<Boundary>()

        for (index in windowFrames until frames.size - windowFrames) {
            val before = prefix.window(index - windowFrames, index)
            val after = prefix.window(index, index + windowFrames)
            if (before == null || after == null) continue
            val positionMs = frames[index].positionMs
            val cues = mutableSetOf<CueKind>()
            val scores = mutableListOf<Float>()

            val levelDelta = abs(after.meanDb - before.meanDb)
            if (levelDelta >= LEVEL_STEP_DB && before.stdDb <= LEVEL_STABLE_STD_DB && after.stdDb <= LEVEL_STABLE_STD_DB) {
                cues += CueKind.Level
                scores += (levelDelta / 6f).coerceIn(0.2f, 1f)
            }

            val melDistance = 1f - cosine(before.mel, after.mel)
            if (melDistance >= MEL_DISTANCE) {
                cues += CueKind.Spectral
                scores += (melDistance / 0.5f).coerceIn(0.2f, 1f)
            }

            val correlationDelta = abs(after.correlation - before.correlation)
            if (correlationDelta >= CORRELATION_STEP && min(before.correlation, after.correlation) < 0.35f) {
                cues += CueKind.Channel
                scores += correlationDelta.coerceIn(0.2f, 1f)
            }

            if (cues.isNotEmpty()) {
                boundaries += Boundary(positionMs, scores.max(), cues)
            }
        }
        return boundaries
    }

    private fun mergeBoundaries(boundaries: List<Boundary>): List<Boundary> {
        if (boundaries.isEmpty()) return emptyList()
        val sorted = boundaries.sortedBy { it.positionMs }
        val merged = mutableListOf<Boundary>()
        for (boundary in sorted) {
            val previous = merged.lastOrNull()
            if (previous != null && boundary.positionMs - previous.positionMs <= MERGE_DISTANCE_MS) {
                previous.cues += boundary.cues
                merged[merged.lastIndex] = Boundary(
                    positionMs = (previous.positionMs + boundary.positionMs) / 2,
                    score = maxOf(previous.score, boundary.score),
                    cues = previous.cues,
                )
            } else {
                merged += Boundary(boundary.positionMs, boundary.score, boundary.cues.toMutableSet())
            }
        }
        return merged
    }

    private fun melStability(frames: List<AudioFrame>): Float {
        if (frames.size < 2) return 1f
        var similaritySum = 0f
        for (index in 1 until frames.size) {
            similaritySum += cosine(frames[index - 1].melBands, frames[index].melBands)
        }
        return similaritySum / (frames.size - 1)
    }

    private fun meanDb(frames: List<AudioFrame>, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var sum = 0f
        for (index in from until to) sum += frames[index].rmsDb
        return sum / (to - from)
    }

    private fun hopMs(frames: List<AudioFrame>): Long {
        if (frames.size < 2) return 20L
        val delta = frames[1].positionMs - frames[0].positionMs
        return if (delta in 1..200) delta else 20L
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        val size = min(a.size, b.size)
        for (index in 0 until size) {
            dot += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }

    private class PrefixSums(frames: List<AudioFrame>) {
        private val count = frames.size
        private val db = FloatArray(count + 1)
        private val dbSquares = FloatArray(count + 1)
        private val correlation = FloatArray(count + 1)
        private val mel = Array(count + 1) { FloatArray(AudioFeatureExtractor.MEL_BAND_COUNT) }

        init {
            for (index in frames.indices) {
                val frame = frames[index]
                db[index + 1] = db[index] + frame.rmsDb
                dbSquares[index + 1] = dbSquares[index] + frame.rmsDb * frame.rmsDb
                correlation[index + 1] = correlation[index] + frame.channelCorrelation
                for (band in 0 until AudioFeatureExtractor.MEL_BAND_COUNT) {
                    mel[index + 1][band] = mel[index][band] + frame.melBands[band]
                }
            }
        }

        fun window(from: Int, to: Int): Window? {
            if (from < 0 || to > count || to - from < 4) return null
            val size = (to - from).toFloat()
            val meanDb = (db[to] - db[from]) / size
            val variance = (dbSquares[to] - dbSquares[from]) / size - meanDb * meanDb
            val meanCorrelation = (correlation[to] - correlation[from]) / size
            val melMean = FloatArray(AudioFeatureExtractor.MEL_BAND_COUNT) { band ->
                (mel[to][band] - mel[from][band]) / size
            }
            return Window(
                meanDb = meanDb,
                stdDb = sqrt(variance.coerceAtLeast(0f)),
                mel = melMean,
                correlation = meanCorrelation,
            )
        }
    }
}
