package au.com.shiftyjelly.pocketcasts.repositories.transcript

import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Parsing the same transcript from two screens at once must give the same answer twice.
 *
 * The transcript tab, the player header and the generated-chapters loader all read the same file
 * through one [TranscriptParser] instance, and they do it concurrently whenever a screen opens while
 * another is finishing. A parser that keeps its read position in a field makes those parsers corrupt
 * each other, and the loser reports "no transcript" for a file that exists — which is exactly how a
 * finished on-device transcript disappears from the tab.
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptParserConcurrencyTest {
    private val cues = 40

    private val vtt = buildString {
        appendLine("WEBVTT")
        appendLine()
        for (second in 1..cues) {
            appendLine("00:00:${"%02d".format(second)}.000 --> 00:00:${"%02d".format(second)}.500")
            appendLine("Sentence number $second is here.")
            appendLine()
        }
    }

    @Test
    fun `one parser can be used from several threads at once`() {
        val parser = WebVttParser()
        val start = CyclicBarrier(THREADS)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val parsed = Collections.synchronizedList(mutableListOf<Int>())
        val threads = (1..THREADS).map {
            thread {
                start.await()
                repeat(ROUNDS) {
                    parser.parse(Buffer().writeUtf8(vtt))
                        .onSuccess { entries -> parsed += entries.count { it is TranscriptEntry.Text } }
                        .onFailure { error -> failures += error }
                }
            }
        }
        threads.forEach { it.join() }

        assertTrue("a concurrent parse failed: $failures", failures.isEmpty())
        assertEquals(THREADS * ROUNDS, parsed.size)
        assertTrue("a parse returned a partial transcript: ${parsed.toSet()}", parsed.all { it == cues })
    }

    private companion object {
        const val THREADS = 8
        const val ROUNDS = 25
    }
}
