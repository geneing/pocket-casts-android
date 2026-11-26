package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.content.Context
import com.squareup.moshi.Moshi
import java.util.Optional
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the endpoint's failures look like to the app, and that each one reaches the health state
 * with the kind the fallback and the banner act on.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteSttClientTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var health: RemoteAsrHealth
    private lateinit var client: RemoteSttClient

    @Before
    fun setUp() {
        context.getSharedPreferences("POCKETCASTS_REMOTE_ASR", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().apply { start() }
        health = RemoteAsrHealth(context)
        client = RemoteSttClient(
            okHttpClient = OkHttpClient(),
            apiKey = Optional.of(KEY),
            moshi = Moshi.Builder().build(),
            health = health,
        ).apply { endpoint = server.url("/audio/transcriptions").toString() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a transcript is returned with the price the provider reported`() {
        server.enqueue(
            MockResponse().setBody(
                """{"text":"hello there","duration":2.0,""" +
                    """"words":[{"word":"hello","start":0.0,"end":1.0},{"word":"there","start":1.0,"end":2.0}],""" +
                    """"usage":{"seconds":2.0,"cost":0.0000123}}""",
            ),
        )

        val result = transcribe()

        val success = result as RemoteSttResult.Success
        assertEquals(2, success.transcript.words.size)
        assertEquals(0.0000123, success.transcript.costUsd!!, 0.0)
        assertFalse(health.isSuspended())
    }

    @Test
    fun `an empty account is reported as out of funds`() {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"message":"Insufficient credits","code":402}}"""))

        val failure = transcribe() as RemoteSttResult.Failed

        assertEquals(RemoteSttFailureKind.OutOfFunds, failure.kind)
        assertEquals(402, failure.status)
        assertTrue(health.isSuspended())
        assertEquals(RemoteSttFailureKind.OutOfFunds, health.alert.value?.kind)
    }

    @Test
    fun `a model with no provider left is reported as unavailable`() {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":{"message":"No endpoints found for model","code":404}}"""),
        )

        val failure = transcribe() as RemoteSttResult.Failed

        assertEquals(RemoteSttFailureKind.ModelUnavailable, failure.kind)
        assertTrue(health.isSuspended())
    }

    @Test
    fun `a server error is a network failure`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream unavailable"))

        val failure = transcribe() as RemoteSttResult.Failed

        assertEquals(RemoteSttFailureKind.Network, failure.kind)
        // One network failure is not enough: the engine has to be seen to be consistently down.
        assertFalse(health.isSuspended())
    }

    @Test
    fun `any other refusal is an api failure carrying its status`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid request"}}"""))

        val failure = transcribe() as RemoteSttResult.Failed

        assertEquals(RemoteSttFailureKind.Api, failure.kind)
        assertEquals(400, failure.status)
    }

    @Test
    fun `a provider error inside a 200 is classified from its message`() {
        server.enqueue(
            MockResponse().setBody("""{"error":{"message":"You are out of credits","code":402}}"""),
        )

        val failure = transcribe() as RemoteSttResult.Failed

        assertEquals(RemoteSttFailureKind.OutOfFunds, failure.kind)
    }

    @Test
    fun `a malformed body is an api failure`() {
        server.enqueue(MockResponse().setBody("not json"))

        val failure = transcribe() as RemoteSttResult.Failed

        assertEquals(RemoteSttFailureKind.Api, failure.kind)
        assertNull(failure.status)
    }

    @Test
    fun `the reasoning only matches a model that is gone`() {
        assertEquals(
            RemoteSttFailureKind.ModelUnavailable,
            client.kindOf(400, "The model nvidia/nemotron is not available"),
        )
        // A model that exists but refuses this request is not "gone".
        assertEquals(
            RemoteSttFailureKind.Api,
            client.kindOf(400, "The selected model does not support audio from a URL"),
        )
        assertEquals(RemoteSttFailureKind.OutOfFunds, client.kindOf(200, "Insufficient credits"))
        assertEquals(RemoteSttFailureKind.Network, client.kindOf(429, "rate limited"))
    }

    private fun transcribe(): RemoteSttResult = runBlocking {
        client.transcribe(byteArrayOf(1, 2, 3, 4), "mp3", "en")
    }

    private companion object {
        const val KEY = "test-key"
    }
}
