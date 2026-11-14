package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscriptionChunkLoopTest {
    private fun chunk(offsetMs: Long) = PcmChunk(ShortArray(8), offsetMs)

    @Test
    fun `a fatal chunk fails the whole pass`() = runTest {
        var closed = false
        val loop = TranscriptionChunkLoop(
            recognize = { current ->
                if (current.offsetMs == 0L) {
                    ChunkResult.Speech(listOf(ChunkCue(0, 1_000, "brought to you by acme")))
                } else {
                    ChunkResult.Fatal("recognizer error 11")
                }
            },
            close = { closed = true },
        )

        val result = loop.run(2) { index -> chunk(if (index == 0) 0L else 15_000L) }

        assertTrue("partial success must not be returned as a transcript", result is TranscriptResult.Failed)
        assertTrue("resources must be released", closed)
    }

    @Test
    fun `chunks without speech do not fail the pass`() = runTest {
        val loop = TranscriptionChunkLoop(
            recognize = { current ->
                when (current.offsetMs) {
                    0L -> ChunkResult.Speech(listOf(ChunkCue(0, 1_000, "this episode is sponsored by acme")))
                    15_000L -> ChunkResult.NoSpeech
                    else -> ChunkResult.Speech(listOf(ChunkCue(0, 2_000, "use code save ten")))
                }
            },
            close = {},
        )

        val result = loop.run(3) { index -> chunk(index * 15_000L) }

        assertTrue(result is TranscriptResult.Success)
        val cues = (result as TranscriptResult.Success).cues
        assertEquals(2, cues.size)
        assertEquals("cue times are shifted into the episode timeline", 30_000L, cues[1].startMs)
    }

    @Test
    fun `all chunks without speech is a failure, not an empty transcript`() = runTest {
        val loop = TranscriptionChunkLoop(recognize = { ChunkResult.NoSpeech }, close = {})
        val result = loop.run(2) { index -> chunk(index * 15_000L) }
        assertTrue(result is TranscriptResult.Failed)
    }

    @Test
    fun `cancellation propagates and still releases resources`() = runTest {
        var closed = false
        val loop = TranscriptionChunkLoop(
            recognize = { throw CancellationException("stopped") },
            close = { closed = true },
        )
        try {
            loop.run(1) { chunk(0) }
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            assertTrue(closed)
        }
    }
}
