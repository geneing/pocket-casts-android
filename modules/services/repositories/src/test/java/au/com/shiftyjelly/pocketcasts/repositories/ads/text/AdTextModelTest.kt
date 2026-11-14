package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity test against fixtures produced by `dev/adaware/next/m0/train.py`: the Kotlin vectorizer,
 * classifier and decoder must reproduce the Python probabilities and interval list.
 */
class AdTextModelTest {
    private fun model(): AdTextModel = AdTextModel.fromJson(resource("ad_detector/model.json"))

    private fun fixtures(): Map<String, Any?> {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return requireNotNull(Moshi.Builder().build().adapter<Map<String, Any?>>(type).fromJson(resource(FIXTURES)))
    }

    private fun resource(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
        return stream.bufferedReader().use { it.readText() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

    private fun Any?.asDouble(): Double = (this as? Number)?.toDouble() ?: 0.0

    @Test
    fun `probabilities match the python fixture`() {
        val model = model()
        val fixtures = fixtures()
        val documents = fixtures.list("documents").map { it.toString() }
        val expected = fixtures.list("probabilities").map { it.asDouble() }
        assertEquals(documents.size, expected.size)
        documents.forEachIndexed { i, document ->
            assertEquals("document $i", expected[i], model.scoreDocument(document), 2e-4)
        }
    }

    @Test
    fun `episode fixture reproduces decoded intervals`() {
        val model = model()
        val episode = fixtures()["episode"].asMap()
        val segments = episode.list("segments").map { row ->
            val map = row.asMap()
            AdTranscriptSegment(map["start"].asDouble(), map["end"].asDouble(), map["text"].toString())
        }
        val expected = episode.list("expected_intervals").map { row ->
            val map = row.asMap()
            map["start"].asDouble() to map["end"].asDouble()
        }
        val expectedProbabilities = episode.list("expected_probabilities").map { it.asDouble() }
        val actualProbabilities = model.probabilities(segments)
        assertEquals(expectedProbabilities.size, actualProbabilities.size)
        expectedProbabilities.forEachIndexed { i, p ->
            assertEquals("segment $i", p, actualProbabilities[i], 2e-4)
        }
        val actual = model.detect(segments)
        assertEquals("interval count", expected.size, actual.size)
        expected.forEachIndexed { i, (start, end) ->
            assertEquals("start $i", start * 1000, actual[i].startMs.toDouble(), 2.0)
            assertEquals("end $i", end * 1000, actual[i].endMs.toDouble(), 2.0)
        }
    }

    @Test
    fun `decoder does not emit pods below the minimum duration`() {
        val model = model()
        val segments = listOf(
            AdTranscriptSegment(0.0, 2.0, "this episode is sponsored by acme"),
            AdTranscriptSegment(2.0, 4.0, "use code save ten at acme dot com"),
        )
        assertTrue(model.detect(segments).isEmpty())
    }

    companion object {
        private const val FIXTURES = "ad_detector_fixtures.json"
    }
}
