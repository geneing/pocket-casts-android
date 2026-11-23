package au.com.shiftyjelly.pocketcasts.models.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.shiftyjelly.pocketcasts.models.db.dao.AsrQueueDao
import au.com.shiftyjelly.pocketcasts.models.di.ModelModule
import au.com.shiftyjelly.pocketcasts.models.di.addTypeConverters
import au.com.shiftyjelly.pocketcasts.models.to.AsrQueueItem
import com.squareup.moshi.Moshi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The row that parks recognition until its episode's audio exists is released by the download, so
 * the release has to be exact: waking a row that is backing off from a real failure would retry a
 * broken pass every time the user opens the episode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AsrQueueDaoTest {
    private lateinit var asrQueueDao: AsrQueueDao
    private lateinit var testDb: AppDatabase

    @Before
    fun setupDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addTypeConverters(ModelModule.provideRoomConverters(Moshi.Builder().build()))
            .build()
        asrQueueDao = testDb.asrQueueDao()
    }

    @After
    fun closeDb() {
        testDb.close()
    }

    @Test
    fun `a row waiting for its audio becomes due when the audio arrives`() = runTest {
        asrQueueDao.upsert(item(EPISODE, nextAttemptAt = FUTURE, lastError = WAITING))

        asrQueueDao.wakeWaitingForAudio(EPISODE, now = NOW, reason = WAITING)

        assertEquals(NOW, asrQueueDao.find(EPISODE)?.nextAttemptAt)
    }

    @Test
    fun `a row backing off from a failure keeps its retry time`() = runTest {
        asrQueueDao.upsert(item(EPISODE, nextAttemptAt = FUTURE, lastError = "recognizer error"))

        asrQueueDao.wakeWaitingForAudio(EPISODE, now = NOW, reason = WAITING)

        assertEquals(FUTURE, asrQueueDao.find(EPISODE)?.nextAttemptAt)
    }

    @Test
    fun `a finished row is not made due again`() = runTest {
        asrQueueDao.upsert(item(EPISODE, nextAttemptAt = FUTURE, isComplete = true))

        asrQueueDao.wakeWaitingForAudio(EPISODE, now = NOW, reason = WAITING)

        assertTrue(asrQueueDao.find(EPISODE)?.isComplete == true)
        assertEquals(FUTURE, asrQueueDao.find(EPISODE)?.nextAttemptAt)
    }

    @Test
    fun `a row already due is left due and stops waiting`() = runTest {
        asrQueueDao.upsert(item(EPISODE, nextAttemptAt = 0, lastError = WAITING))

        asrQueueDao.wakeWaitingForAudio(EPISODE, now = NOW, reason = WAITING)

        assertEquals(NOW, asrQueueDao.find(EPISODE)?.nextAttemptAt)
        assertNull("the row is no longer waiting for audio", asrQueueDao.find(EPISODE)?.lastError)
    }

    private fun item(
        episodeUuid: String,
        nextAttemptAt: Long,
        isComplete: Boolean = false,
        lastError: String? = null,
    ) = AsrQueueItem(
        episodeUuid = episodeUuid,
        enqueuedAt = NOW - 1_000,
        nextAttemptAt = nextAttemptAt,
        isComplete = isComplete,
        lastError = lastError,
    )

    private companion object {
        const val EPISODE = "asr-queue-test-episode"
        const val WAITING = "waiting for the episode's audio"
        const val NOW = 1_700_000_000_000L
        const val FUTURE = NOW + 30 * 60_000L
    }
}
