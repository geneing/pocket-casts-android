package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import androidx.room.Room
import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.di.ModelModule
import au.com.shiftyjelly.pocketcasts.models.di.addTypeConverters
import au.com.shiftyjelly.pocketcasts.models.to.DbAdSegment
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AdSegmentSelectionTest {
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
    fun `selection survives concurrent replacement`() = runBlocking {
        val dao = database.adSegmentDao()
        val segment = DbAdSegment(index = 0, episodeUuid = "episode", startTimeMs = 10_000, endTimeMs = 20_000)
        repeat(100) {
            dao.replaceAll("episode", listOf(segment))
            awaitAll(
                async(Dispatchers.IO) { dao.setDeselected("episode", 0, true) },
                async(Dispatchers.IO) { dao.replacePreservingSelections("episode", listOf(segment)) },
            )
            assertTrue(dao.findSegmentsForEpisode("episode").single().deselected)
        }
    }
}
