package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Semi-Markov decoder from `dev/adaware/next/semantic/detector.py`, ported without changes:
 * non-overlapping pods of 8-300 s, a duration prior, a boundary penalty, hard boundaries at
 * transcript gaps over 12 s, and a final merge of near-adjacent pods.
 */
internal object AdPodDecoder {
    fun decode(
        segments: List<AdTranscriptSegment>,
        probabilities: DoubleArray,
        decoder: AdDetectorBundle.Decoder,
    ): List<DetectedAdPod> {
        if (segments.isEmpty() || probabilities.isEmpty()) return emptyList()
        val n = segments.size
        val logitThreshold = logit(decoder.threshold)
        val emissions = DoubleArray(n)
        for (i in 0 until n) {
            val duration = max(0.0, segments[i].endSeconds - segments[i].startSeconds)
            val confidence = logit(probabilities[i]) - logitThreshold
            emissions[i] = confidence * (duration / 3.5).coerceIn(0.5, 1.5)
        }
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + emissions[i]
        val starts = DoubleArray(n) { segments[it].startSeconds }
        val ends = DoubleArray(n) { segments[it].endSeconds }

        val hardBreaks = IntArray(n)
        if (n > 1) {
            var lastBreak = 0
            for (i in 1 until n) {
                if (starts[i] - ends[i - 1] > decoder.hardGapSeconds) lastBreak = i
                hardBreaks[i] = lastBreak
            }
        }

        val best = DoubleArray(n + 1)
        val backStart = IntArray(n + 1) { -1 }
        for (endIndex in 1..n) {
            best[endIndex] = best[endIndex - 1]
            val endTime = ends[endIndex - 1]
            var lo = lowerBound(starts, endTime - decoder.maxPodSeconds, endIndex)
            val hi = upperBound(starts, endTime - decoder.minPodSeconds, endIndex)
            lo = max(lo, hardBreaks[endIndex - 1])
            if (hi <= lo) continue
            var bestScore = best[endIndex - 1]
            var bestRun = -1
            for (runStart in lo until hi) {
                val podDuration = endTime - starts[runStart]
                if (podDuration < decoder.minPodSeconds || podDuration > decoder.maxPodSeconds) continue
                val z = ln(max(1.0, podDuration) / 45.0)
                val score = best[runStart] +
                    prefix[endIndex] - prefix[runStart] -
                    decoder.boundaryPenalty -
                    decoder.durationWeight * 0.5 * z * z
                if (score > bestScore && score > 0.0) {
                    bestScore = score
                    bestRun = runStart
                }
            }
            if (bestRun >= 0) {
                best[endIndex] = bestScore
                backStart[endIndex] = bestRun
            }
        }

        val chosen = ArrayList<Pair<Int, Int>>()
        var cursor = n
        while (cursor > 0) {
            val start = backStart[cursor]
            if (start < 0) {
                cursor--
            } else {
                chosen.add(start to cursor)
                cursor = start
            }
        }
        val intervals = chosen.asReversed().map { (a, b) ->
            val start = starts[a]
            val end = ends[b - 1]
            val mean = (a until b).sumOf { probabilities[it] } / (b - a).coerceAtLeast(1)
            DetectedAdPod(
                startMs = (start * 1000.0).toLong(),
                endMs = (end * 1000.0).toLong(),
                meanProbability = mean,
            )
        }
        return merge(intervals, decoder.mergeGap)
    }

    private fun merge(pods: List<DetectedAdPod>, gapSeconds: Double): List<DetectedAdPod> {
        if (pods.isEmpty()) return pods
        val out = ArrayList<DetectedAdPod>()
        var current = pods.first()
        for (pod in pods.drop(1)) {
            if (pod.startMs - current.endMs <= (gapSeconds * 1000).toLong()) {
                val total = (current.meanProbability + pod.meanProbability) / 2
                current = current.copy(endMs = max(current.endMs, pod.endMs), meanProbability = total)
            } else {
                out.add(current)
                current = pod
            }
        }
        out.add(current)
        return out
    }

    private fun lowerBound(values: DoubleArray, target: Double, limit: Int): Int {
        var lo = 0
        var hi = limit
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBound(values: DoubleArray, target: Double, limit: Int): Int {
        var lo = 0
        var hi = limit
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] <= target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun logit(p: Double): Double {
        val clamped = p.coerceIn(1e-5, 1.0 - 1e-5)
        return ln(clamped / (1.0 - clamped))
    }
}
