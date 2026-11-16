package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptType
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdClassifier
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModel
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModelStore
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevClassification
import au.com.shiftyjelly.pocketcasts.repositories.file.FileStorage
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.transcript.TranscriptManager
import java.io.File
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class TranscriptAdAnalyzerTest {
    /** What [FeatureFlagTranscriptPolicy] would answer; set per test. */
    private var ignoresPublished = false

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
    private val transcriptManager: TranscriptManager = mock()
    private val generatedTranscriptLoader: GeneratedTranscriptLoader = mock()
    private val jevAdModelStore: JevAdModelStore = mock()
    private val fileStorage: FileStorage = mock()
    private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

    private fun transcript(
        url: String = "https://shownotes.pocketcasts.com/generated_transcripts/podcast/episode.vtt",
        vararg entries: TranscriptEntry,
    ) = Transcript.Text(
        entries = entries.toList(),
        type = TranscriptType.Vtt,
        url = url,
        isGenerated = true,
        episodeUuid = "episode-uuid",
        podcastUuid = "podcast-uuid",
    )

    @Test
    fun `timed text entries become cues and speakers and unusable entries are dropped`() {
        val cues = transcript(
            "url",
            TranscriptEntry.Speaker("Host"),
            TranscriptEntry.Text("this episode is brought to you by acme", startTimeMs = 60_000, endTimeMs = 63_000),
            TranscriptEntry.Text("  ", startTimeMs = 64_000, endTimeMs = 65_000),
            TranscriptEntry.Text("no timing"),
            TranscriptEntry.Text("use code save", startTimeMs = 63_000, endTimeMs = 60_000),
        ).toAnalysisCues()

        assertEquals(
            listOf(
                TranscriptCue(60_000, 63_000, "this episode is brought to you by acme"),
                TranscriptCue(63_000, 63_000, "use code save"),
            ),
            cues,
        )
    }

    @Test
    fun `a fetched transcript is identified by its url`() {
        val version = transcript("https://example.com/episode.vtt").analysisVersion("moonshine")
        assertEquals("transcript:https://example.com/episode.vtt", version)
    }

    @Test
    fun `an on-device transcript is identified by the recognizer`() {
        val version = transcript("${LocalTranscriptStore.SCHEME}episode-uuid").analysisVersion("moonshine")
        assertEquals("moonshine", version)
    }

    @Test
    fun `a successful transcription is cached and then classified`() = runTest {
        val audio = audioFile()
        val cues = listOf(
            TranscriptCue(0, 30_000, "this episode is brought to you by acme."),
            TranscriptCue(30_000, 60_000, "and now back to the show."),
        )
        givenEpisode(audio)
        givenNoTranscript()
        whenever(transcriber.version).thenReturn(ENGINE)
        whenever(transcriber.isAvailable()).thenReturn(true)
        whenever(transcriber.availability("en")).thenReturn(TranscriptAvailability.Ready)
        whenever(transcriber.transcribe(any(), any())).thenReturn(TranscriptResult.Success(cues, "moonshine-tag"))
        whenever(classifier.classify(any(), any(), any())).thenReturn(
            JevClassification.Scored(pAd = listOf(0.95, 0.05), responseModel = null, usageCostUsd = 0.01),
        )
        whenever(adSegmentDao.findSegmentsForEpisode(EPISODE)).thenReturn(emptyList())

        val outcome = analyzer().run(EPISODE)

        assertEquals(TranscriptAnalysis.Analysed, outcome)
        verify(transcriptStore).save(EPISODE, cues)
        verify(classifier).classify(any(), any(), any())
        val state = argumentCaptor<AdAnalysisState>()
        verify(adAnalysisDao).upsert(state.capture())
        assertEquals(ENGINE, state.firstValue.sttVersion)
        assertEquals(AnalysisIdentity.audioSignature(audio), state.firstValue.audioSignature)
    }

    @Test
    fun `an unavailable engine leaves the episode alone and does not count as a failure`() = runTest {
        givenEpisode(audioFile())
        givenNoTranscript()
        whenever(transcriber.version).thenReturn(ENGINE)
        whenever(transcriber.isAvailable()).thenReturn(true)
        whenever(transcriber.availability("en")).thenReturn(TranscriptAvailability.Ready)
        whenever(transcriber.transcribe(any(), any())).thenReturn(TranscriptResult.Unavailable("hls stream"))

        assertEquals(TranscriptAnalysis.NotAnalysed, analyzer().run(EPISODE))
        // A failure would have been remembered and blocked the retry below.
        assertEquals(TranscriptAnalysis.NotAnalysed, analyzer().run(EPISODE))

        verify(transcriber, times(2)).transcribe(any(), any())
        verify(transcriptStore, never()).save(any(), any())
        verifyNoInteractions(classifier)
    }

    @Test
    fun `a failed transcription is remembered so the episode is not retried at once`() = runTest {
        givenEpisode(audioFile())
        givenNoTranscript()
        whenever(transcriber.version).thenReturn(ENGINE)
        whenever(transcriber.isAvailable()).thenReturn(true)
        whenever(transcriber.availability("en")).thenReturn(TranscriptAvailability.Ready)
        whenever(transcriber.transcribe(any(), any())).thenReturn(TranscriptResult.Failed("recognizer error"))

        val analyzer = analyzer()
        assertEquals(TranscriptAnalysis.NotAnalysed, analyzer.run(EPISODE))
        assertEquals(TranscriptAnalysis.NotAnalysed, analyzer.run(EPISODE))

        verify(transcriber, times(1)).transcribe(any(), any())
        verify(transcriptStore, never()).save(any(), any())
        verifyNoInteractions(classifier)
        verify(adAnalysisDao, never()).upsert(any())
    }

    @Test
    fun `a model that needs an unmetered network defers the pass instead of transcribing`() = runTest {
        givenEpisode(audioFile())
        givenNoTranscript()
        whenever(transcriber.version).thenReturn(ENGINE)
        whenever(transcriber.isAvailable()).thenReturn(true)
        whenever(transcriber.availability("en")).thenReturn(TranscriptAvailability.ModelNeedsUnmeteredNetwork)

        assertEquals(TranscriptAnalysis.NeedsUnmeteredNetwork, analyzer().run(EPISODE))
        verify(transcriber, never()).transcribe(any(), any())
    }

    @Test
    fun `a language with no model is left to the audio analyzer`() = runTest {
        givenEpisode(audioFile())
        givenNoTranscript()
        whenever(transcriber.version).thenReturn(ENGINE)
        whenever(transcriber.isAvailable()).thenReturn(true)
        whenever(transcriber.availability("en")).thenReturn(TranscriptAvailability.UnsupportedLanguage)

        assertEquals(TranscriptAnalysis.NotAnalysed, analyzer().run(EPISODE))
        verify(transcriber, never()).transcribe(any(), any())
    }

    @Test
    fun `the debug switch ignores the published transcript and replaces it`() = runTest {
        ignoresPublished = true
        val audio = audioFile()
        givenEpisode(audio)
        whenever(transcriber.version).thenReturn(ENGINE)
        whenever(transcriber.isAvailable()).thenReturn(true)
        whenever(transcriber.availability("en")).thenReturn(TranscriptAvailability.Ready)
        whenever(transcriber.transcribe(any(), any()))
            .thenReturn(TranscriptResult.Success(listOf(TranscriptCue(0, 30_000, "on device text.")), "tag"))
        whenever(classifier.classify(any(), any(), any())).thenReturn(
            JevClassification.Scored(pAd = listOf(0.9), responseModel = null, usageCostUsd = 0.01),
        )
        // A show transcript the episode does have: the switch must not read it.
        whenever(transcriptManager.observeIsTranscriptAvailable(EPISODE)).thenReturn(flowOf(true))
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("https://example.com/t.vtt"))
        whenever(generatedTranscriptLoader.urlFor(any())).thenReturn("https://example.com/generated.vtt")
        whenever(generatedTranscriptLoader.load(EPISODE)).thenReturn(transcript("https://example.com/generated.vtt"))

        assertEquals(TranscriptAnalysis.Analysed, analyzer().run(EPISODE))

        verify(transcriber).transcribe(any(), any())
        verify(transcriptStore).replaceRemoteAndSave(EPISODE, listOf(TranscriptCue(0, 30_000, "on device text.")))
        verify(transcriptStore, never()).save(any(), any())
    }

    @Test
    fun `a published transcript is scored instead of recognising the audio`() = runTest {
        ignoresPublished = false
        givenEpisode(audioFile())
        whenever(transcriber.version).thenReturn(ENGINE)
        whenever(transcriber.isAvailable()).thenReturn(true)
        val published = transcript(
            "https://example.com/shownotes.vtt",
            TranscriptEntry.Text("this episode is brought to you by acme", startTimeMs = 0, endTimeMs = 30_000),
            TranscriptEntry.Text("and now back to the show", startTimeMs = 30_000, endTimeMs = 60_000),
        )
        whenever(transcriptManager.observeIsTranscriptAvailable(EPISODE)).thenReturn(flowOf(true))
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(published)
        whenever(classifier.classify(any(), any(), any())).thenReturn(
            JevClassification.Scored(pAd = listOf(0.95, 0.05), responseModel = null, usageCostUsd = 0.01),
        )

        assertEquals(TranscriptAnalysis.Analysed, analyzer().run(EPISODE))

        // The published transcript is the analysis input: no pass, and no transcript replaced on disk.
        verify(transcriber, never()).transcribe(any(), any())
        verify(transcriptStore, never()).save(any(), any())
        verify(transcriptStore, never()).replaceRemoteAndSave(any(), any())
        val cues = argumentCaptor<List<TranscriptCue>>()
        verify(classifier).classify(cues.capture(), any(), any())
        assertEquals(
            listOf(
                TranscriptCue(0, 30_000, "this episode is brought to you by acme"),
                TranscriptCue(30_000, 60_000, "and now back to the show"),
            ),
            cues.firstValue,
        )
        // The analysis stays tied to the published URL, so a different one is a different analysis.
        val state = argumentCaptor<AdAnalysisState>()
        verify(adAnalysisDao).upsert(state.capture())
        assertEquals(transcriptAnalysisVersion(published.url), state.firstValue.sttVersion)
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
        whenever(adAnalysisDao.findState(EPISODE)).thenReturn(null)
        whenever(adCueScoreDao.countForEpisode(EPISODE)).thenReturn(0)
        whenever(adSegmentDao.findSegmentsForEpisode(EPISODE)).thenReturn(emptyList())
        whenever(fileStorage.getOrCreatePodcastEpisodeFile(any())).thenReturn(null)
    }

    private suspend fun givenNoTranscript() {
        whenever(transcriptManager.observeIsTranscriptAvailable(EPISODE)).thenReturn(flowOf(false))
        whenever(generatedTranscriptLoader.urlFor(any())).thenReturn(null)
        whenever(generatedTranscriptLoader.load(EPISODE)).thenReturn(null)
    }

    private fun analyzer() = TranscriptAdAnalyzer(
        episodeManager = episodeManager,
        podcastManager = podcastManager,
        adSegmentDao = adSegmentDao,
        adAnalysisDao = adAnalysisDao,
        adCueScoreDao = adCueScoreDao,
        classifier = classifier,
        transcriber = transcriber,
        sourcePolicy = { ignoresPublished },
        transcriptStore = transcriptStore,
        transcriptManager = transcriptManager,
        generatedTranscriptLoader = generatedTranscriptLoader,
        jevAdModelStore = jevAdModelStore,
        fileStorage = fileStorage,
        scope = scope,
    )

    private companion object {
        const val EPISODE = "episode-uuid"
        const val ENGINE = "moonshine-0.1.6/tiny-streaming-en-wt,es"
    }
}
