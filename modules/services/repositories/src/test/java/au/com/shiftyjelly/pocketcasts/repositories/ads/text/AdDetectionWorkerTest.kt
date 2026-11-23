package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import au.com.shiftyjelly.pocketcasts.models.to.AdDetectionQueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdDetectionWorkerTest {
    private val queue = mock<AdAnalysisQueue>()
    private val analyzer = mock<TranscriptAdAnalyzer>()
    private val guard = AnalysisWriteGuard()
    private val context = mock<Context> { on { resources } doReturn mock() }

    @Test
    fun `a crashed item is deferred and the next item is processed`() = runTest {
        val first = AdDetectionQueueItem(episodeUuid = "first", enqueuedAt = 1)
        val second = AdDetectionQueueItem(episodeUuid = "second", enqueuedAt = 2)
        whenever(queue.nextDetectionDue(any())).thenReturn(first, second, null)
        whenever(analyzer.run("first")).doSuspendableAnswer { throw IllegalStateException("failure") }
        whenever(analyzer.run("second")).thenReturn(DetectionOutcome.Completed)

        worker().drainQueue()

        verify(queue).failDetection("first", "crashed: IllegalStateException", 10 * 60_000L)
        verify(analyzer).run("second")
    }

    @Test
    fun `cancellation is propagated without recording a failure`() = runTest {
        whenever(queue.nextDetectionDue(any())).thenReturn(AdDetectionQueueItem(episodeUuid = "first", enqueuedAt = 1))
        whenever(analyzer.run("first")).doSuspendableAnswer { throw CancellationException("cancelled") }

        try {
            worker().drainQueue()
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
        verify(queue, never()).failDetection(any(), any(), any())
    }

    private fun worker(): AdDetectionWorker {
        whenever(context.applicationContext) doReturn context
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = AdDetectionWorker(appContext, workerParameters, queue, analyzer, guard)
        }
        return TestListenableWorkerBuilder.from(context, AdDetectionWorker::class.java)
            .setWorkerFactory(factory)
            .build()
    }
}
