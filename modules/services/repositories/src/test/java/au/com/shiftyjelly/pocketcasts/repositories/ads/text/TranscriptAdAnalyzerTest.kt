package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import androidx.room.Room
import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.di.ModelModule
import au.com.shiftyjelly.pocketcasts.models.di.addTypeConverters
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.models.to.AdDetectionQueueItem
import au.com.shiftyjelly.pocketcasts.models.to.AsrQueueItem
import au.com.shiftyjelly.pocketcasts.models.to.DbAdCueScore
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus
import au.com.shiftyjelly.pocketcasts.repositories.ads.AdAnalysisCleanup
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdClassifier
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModel
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModelStore
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevClassification
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import com.squareup.moshi.Moshi
import java.io.File
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TranscriptAdAnalyzerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val episodeManager: EpisodeManager = mock()
    private val podcastManager: PodcastManager = mock()
    private val adSegmentDao: AdSegmentDao = mock()
    private val adAnalysisDao: AdAnalysisDao = mock()
    private val adCueScoreDao: AdCueScoreDao = mock()
    private val classifier: JevAdClassifier = mock()
    private val transcriber: EpisodeTranscriber = mock()
    private val transcriptStore: LocalTranscriptStore = mock()
    private val jevAdModelStore: JevAdModelStore = mock()
    private val queue: AdAnalysisQueue = mock()
    private val writeGuard = AnalysisWriteGuard()
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addTypeConverters(ModelModule.provideRoomConverters(Moshi.Builder().build()))
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a result arriving after reset is discarded`() = runTest {
        givenEpisode(audioFile())
        givenQueued(detection = detectionItem(), asr = asrItem(complete = true))
        whenever(transcriptStore.readCues(EPISODE)).thenReturn(listOf(TranscriptCue(0, 60_000, "old cue")))
        whenever(adCueScoreDao.findScoresForEpisode(EPISODE)).thenReturn(emptyList())
        val reply = CompletableDeferred<JevClassification>()
        whenever(classifier.classify(any(), any(), any())).doSuspendableAnswer { reply.await() }

        val result = async { analyzer().run(EPISODE) }
        runCurrent()
        AdAnalysisCleanup(
            transcriptStore = transcriptStore,
            adSegmentDao = adSegmentDao,
            adCueScoreDao = adCueScoreDao,
            adAnalysisDao = adAnalysisDao,
            asrQueueDao = mock(),
            detectionQueueDao = mock(),
            writeGuard = writeGuard,
        ).clearAnalysisForEpisodes(listOf(EPISODE))
        reply.complete(JevClassification.Scored(listOf(0.9), listOf(0.1), null, null))

        assertEquals(DetectionOutcome.NotQueued, result.await())
        verify(adSegmentDao, never()).replacePreservingSelections(any(), any())
        verify(adCueScoreDao).replaceAll(EPISODE, emptyList())
        verify(adAnalysisDao, never()).upsert(any())
        verify(queue, never()).recordDetectionProgress(any(), any(), any())
    }

    @Test
    fun `recognition cannot republish cues after reset`() = runTest {
        givenEpisode(audioFile())
        givenQueued(detection = detectionItem(), asr = asrItem(complete = false))
        whenever(transcriber.availability("en")).thenReturn(TranscriptAvailability.Ready)
        whenever(transcriptStore.readCues(EPISODE)).thenReturn(emptyList())
        whenever(transcriptStore.recognisedAudio(EPISODE)).thenReturn(null)
        whenever(transcriptStore.hasTranscript(EPISODE)).thenReturn(false)
        val ready = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val cues = listOf(TranscriptCue(0, 10_000, "old cue"))
        whenever(transcriber.transcribe(any(), any(), any())).doSuspendableAnswer { invocation ->
            ready.complete(Unit)
            finish.await()
            invocation.getArgument<suspend (List<TranscriptCue>) -> Unit>(2)(cues)
            TranscriptResult.Success(cues, ENGINE)
        }

        val result = async { generator().run(EPISODE) }
        runCurrent()
        if (!ready.isCompleted && result.isCompleted) error("Recognition ended before starting: ${result.await()}")
        assertTrue("Recognition did not start", ready.isCompleted)
        writeGuard.invalidate(listOf(EPISODE)) { }
        finish.complete(Unit)

        assertEquals(AsrOutcome.NotQueued, result.await())
        verify(transcriptStore, never()).saveProgress(any(), any())
        verify(transcriptStore, never()).markComplete(any(), any(), any())
        verify(queue, never()).enqueueDetectionNow(any())
    }

    @Test
    fun `only the on-device transcript cues are scored`() = runTest {
        val audio = audioFile()
        val cues = listOf(
            TranscriptCue(0, 30_000, "this episode is brought to you by acme."),
            TranscriptCue(30_000, 60_000, "and now back to the show."),
        )
        givenEpisode(audio)
        givenQueued(detection = detectionItem(), asr = asrItem(complete = true))
        whenever(transcriptStore.readCues(EPISODE)).thenReturn(cues)
        whenever(adCueScoreDao.findScoresForEpisode(EPISODE)).thenReturn(emptyList())
        whenever(classifier.classify(any(), any(), any())).thenReturn(
            JevClassification.Scored(
                pAd = listOf(0.95, 0.05),
                pContent = listOf(0.05, 0.95),
                responseModel = null,
                usageCostUsd = 0.01,
            ),
        )
        whenever(adSegmentDao.findSegmentsForEpisode(EPISODE)).thenReturn(emptyList())

        val outcome = analyzer().run(EPISODE)

        assertEquals(DetectionOutcome.Completed, outcome)
        val scored = argumentCaptor<List<TranscriptCue>>()
        verify(classifier).classify(scored.capture(), any(), any())
        assertEquals(cues, scored.firstValue)
        val state = argumentCaptor<AdAnalysisState>()
        verify(adAnalysisDao).upsert(state.capture())
        assertEquals(ENGINE, state.firstValue.sttVersion)
        assertTrue(state.firstValue.isComplete)
        verify(queue).recordDetectionProgress(EPISODE, 60_000L, isComplete = true)
        verify(transcriber, never()).transcribe(any(), any(), any())
    }

    @Test
    fun `recognition that is still running waits for a useful batch`() = runTest {
        givenEpisode(audioFile())
        givenQueued(detection = detectionItem(), asr = asrItem(complete = false))
        whenever(transcriptStore.readCues(EPISODE)).thenReturn(
            listOf(
                TranscriptCue(0, 10_000, "one."),
                TranscriptCue(10_000, 20_000, "two."),
                TranscriptCue(20_000, 30_000, "three."),
            ),
        )
        whenever(adCueScoreDao.findScoresForEpisode(EPISODE)).thenReturn(emptyList())

        assertEquals(DetectionOutcome.Waiting, analyzer().run(EPISODE))

        verify(queue).deferDetection(EPISODE, 5 * 60_000L)
        verifyNoInteractions(classifier)
    }

    @Test
    fun `an analysis built from a published transcript is discarded and restarted`() = runTest {
        givenEpisode(audioFile())
        givenQueued(detection = detectionItem(), asr = asrItem(complete = true))
        whenever(queue.analysisState(EPISODE)).thenReturn(
            AdAnalysisState(
                episodeUuid = EPISODE,
                isComplete = true,
                sttVersion = "transcript:https://example.com/published.vtt",
            ),
        )
        whenever(transcriptStore.readCues(EPISODE)).thenReturn(
            listOf(TranscriptCue(0, 60_000, "recognised on device.")),
        )
        whenever(adCueScoreDao.findScoresForEpisode(EPISODE)).thenReturn(emptyList())
        whenever(classifier.classify(any(), any(), any())).thenReturn(
            JevClassification.Scored(
                pAd = listOf(0.9),
                pContent = listOf(0.1),
                responseModel = null,
                usageCostUsd = null,
            ),
        )

        assertEquals(DetectionOutcome.Completed, analyzer().run(EPISODE))

        verify(queue).resetDetection(EPISODE)
        verify(transcriber, never()).transcribe(any(), any(), any())
    }

    @Test
    fun `an analysis scored under an older prompt is discarded and restarted`() = runTest {
        givenEpisode(audioFile())
        givenQueued(detection = detectionItem(), asr = asrItem(complete = true))
        whenever(queue.analysisState(EPISODE)).thenReturn(
            AdAnalysisState(
                episodeUuid = EPISODE,
                isComplete = true,
                sttVersion = ENGINE,
                modelVersion = "respan/span-02-20261015#prompt2",
            ),
        )
        whenever(transcriptStore.readCues(EPISODE)).thenReturn(
            listOf(TranscriptCue(0, 60_000, "recognised on device.")),
        )
        whenever(adCueScoreDao.findScoresForEpisode(EPISODE)).thenReturn(emptyList())
        whenever(classifier.classify(any(), any(), any())).thenReturn(
            JevClassification.Scored(
                pAd = listOf(0.9),
                pContent = listOf(0.1),
                responseModel = "respan/span-02-20261015",
                usageCostUsd = null,
            ),
        )

        assertEquals(DetectionOutcome.Completed, analyzer().run(EPISODE))

        verify(queue).resetDetection(EPISODE)
    }

    @Test
    fun `a failed window keeps its progress and asks for a retry`() = runTest {
        givenEpisode(audioFile())
        givenQueued(detection = detectionItem(), asr = asrItem(complete = true))
        whenever(transcriptStore.readCues(EPISODE)).thenReturn(
            listOf(TranscriptCue(0, 60_000, "one long cue.")),
        )
        whenever(adCueScoreDao.findScoresForEpisode(EPISODE)).thenReturn(emptyList())
        whenever(classifier.classify(any(), any(), any())).thenReturn(
            JevClassification.Failed("connection reset"),
        )

        assertEquals(DetectionOutcome.Retry, analyzer().run(EPISODE))

        verify(queue).failDetection(EPISODE, "connection reset", 10 * 60_000L)
        verify(adSegmentDao, never()).replaceAll(any(), any())
    }

    @Test
    fun `a resumed window starts at the first unscored cue with a little context`() = runTest {
        givenEpisode(audioFile())
        givenQueued(detection = detectionItem(), asr = asrItem(complete = true))
        val cues = (0 until 25).map { index ->
            TranscriptCue(index * 10_000L, (index + 1) * 10_000L, "cue $index.")
        }
        whenever(transcriptStore.readCues(EPISODE)).thenReturn(cues)
        whenever(adCueScoreDao.findScoresForEpisode(EPISODE)).thenReturn(
            (0 until 20).map { index ->
                DbAdCueScore(EPISODE, index, cues[index].startMs, cues[index].endMs, 0.1f)
            },
        )
        whenever(classifier.classify(any(), any(), any())).thenReturn(
            JevClassification.Scored(
                pAd = List(9) { 0.1 },
                pContent = List(9) { 0.9 },
                responseModel = null,
                usageCostUsd = null,
            ),
        )

        assertEquals(DetectionOutcome.Completed, analyzer().run(EPISODE))

        val scored = argumentCaptor<List<TranscriptCue>>()
        verify(classifier).classify(scored.capture(), any(), any())
        assertEquals(9, scored.firstValue.size)
        assertEquals(cues[16].startMs, scored.firstValue.first().startMs)
    }

    @Test
    fun `merging keeps prepared cues and appends new ones without duplication`() {
        val generator = generator()
        val existing = listOf(TranscriptCue(0, 2_000, "first"), TranscriptCue(2_000, 4_000, "second"))

        val merged = generator.mergeCues(
            existing = existing,
            incoming = listOf(
                // The resumed pass starts on a keyframe before its resume point.
                TranscriptCue(2_000, 4_000, "second"),
                TranscriptCue(4_000, 6_000, "third"),
            ),
        )

        assertEquals(
            listOf(
                TranscriptCue(0, 2_000, "first"),
                TranscriptCue(2_000, 4_000, "second"),
                TranscriptCue(4_000, 6_000, "third"),
            ),
            merged,
        )
    }

    private fun audioFile(): File = folder.newFile("episode.mp3").apply { writeBytes(ByteArray(1_024)) }

    private suspend fun givenEpisode(audio: File) {
        val episode = PodcastEpisode(
            uuid = EPISODE,
            publishedDate = Date(),
            podcastUuid = "podcast-uuid",
            duration = 3_600.0,
            downloadStatus = EpisodeDownloadStatus.Downloaded,
            downloadedFilePath = audio.absolutePath,
        )
        whenever(episodeManager.findByUuid(EPISODE)).thenReturn(episode)
        whenever(podcastManager.findPodcastByUuid("podcast-uuid"))
            .thenReturn(Podcast(uuid = "podcast-uuid", title = "Show", podcastLanguage = "en"))
        whenever(jevAdModelStore.selectedJevAdModel()).thenReturn(JevAdModel.SPAN_02)
        whenever(transcriber.version).thenReturn(ENGINE)
        whenever(adAnalysisDao.findState(EPISODE)).thenReturn(null)
        whenever(adSegmentDao.findSegmentsForEpisode(EPISODE)).thenReturn(emptyList())
    }

    private suspend fun givenQueued(detection: AdDetectionQueueItem, asr: AsrQueueItem) {
        whenever(queue.detectionItem(EPISODE)).thenReturn(detection)
        whenever(queue.asrItem(EPISODE)).thenReturn(asr)
    }

    private fun detectionItem() = AdDetectionQueueItem(episodeUuid = EPISODE, enqueuedAt = 0L)

    private fun asrItem(complete: Boolean) = AsrQueueItem(
        episodeUuid = EPISODE,
        enqueuedAt = 0L,
        processedUntilMs = if (complete) 3_600_000L else 0L,
        isComplete = complete,
    )

    private fun analyzer() = TranscriptAdAnalyzer(
        episodeManager = episodeManager,
        podcastManager = podcastManager,
        adSegmentDao = adSegmentDao,
        adAnalysisDao = adAnalysisDao,
        adCueScoreDao = adCueScoreDao,
        classifier = classifier,
        transcriber = transcriber,
        transcriptStore = transcriptStore,
        jevAdModelStore = jevAdModelStore,
        queue = queue,
        writeGuard = writeGuard,
        database = database,
    )

    private fun generator() = OnDeviceTranscriptGenerator(
        episodeManager = episodeManager,
        podcastManager = podcastManager,
        transcriber = transcriber,
        transcriptStore = transcriptStore,
        fileStorage = mock(),
        queue = queue,
        writeGuard = writeGuard,
    )

    private companion object {
        const val EPISODE = "episode-uuid"
        const val ENGINE = "parakeet-tdt-0.6b-v3/i8-5s-stateful/litertlm-1.2.3/cues1"
    }
}
