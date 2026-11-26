package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RemoteAsrHealthTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        preferences().edit().clear().commit()
    }

    @Test
    fun `one network failure does not suspend the engine`() {
        val health = RemoteAsrHealth(context)

        health.recordFailure(RemoteSttFailureKind.Network, null, "could not reach OpenRouter")

        assertFalse(health.isSuspended())
        assertNull(health.alert.value)
    }

    @Test
    fun `two network failures in a row suspend it and raise the banner`() {
        val health = RemoteAsrHealth(context)

        health.recordFailure(RemoteSttFailureKind.Network, null, "could not reach OpenRouter")
        health.recordFailure(RemoteSttFailureKind.Network, null, "could not reach OpenRouter")

        assertTrue(health.isSuspended())
        assertEquals(RemoteSttFailureKind.Network, health.alert.value?.kind)
    }

    @Test
    fun `a success between failures keeps remote transcription working`() {
        val health = RemoteAsrHealth(context)

        health.recordFailure(RemoteSttFailureKind.Network, null, "timeout")
        health.recordSuccess()
        health.recordFailure(RemoteSttFailureKind.Network, null, "timeout")

        assertFalse(health.isSuspended())
    }

    @Test
    fun `a retired model suspends the engine on the first failure`() {
        val health = RemoteAsrHealth(context)

        health.recordFailure(RemoteSttFailureKind.ModelUnavailable, 404, "No endpoints found for model")

        assertTrue(health.isSuspended())
        assertEquals(RemoteSttFailureKind.ModelUnavailable, health.alert.value?.kind)
        assertEquals(404, health.alert.value?.status)
    }

    @Test
    fun `an empty account suspends the engine on the first failure`() {
        val health = RemoteAsrHealth(context)

        health.recordFailure(RemoteSttFailureKind.OutOfFunds, 402, "Insufficient credits")

        assertTrue(health.isSuspended())
        assertEquals(RemoteSttFailureKind.OutOfFunds, health.alert.value?.kind)
    }

    @Test
    fun `the suspension and the banner survive a restart`() {
        RemoteAsrHealth(context).recordFailure(RemoteSttFailureKind.OutOfFunds, 402, "Insufficient credits")

        val restarted = RemoteAsrHealth(context)

        assertTrue(restarted.isSuspended())
        assertEquals(RemoteSttFailureKind.OutOfFunds, restarted.alert.value?.kind)
        assertEquals(402, restarted.alert.value?.status)
        assertEquals("Insufficient credits", restarted.alert.value?.detail)
    }

    @Test
    fun `dismissing the banner keeps the engine suspended`() {
        val health = RemoteAsrHealth(context)
        health.recordFailure(RemoteSttFailureKind.OutOfFunds, 402, "Insufficient credits")

        health.dismiss()

        assertNull(health.alert.value)
        assertNull(RemoteAsrHealth(context).alert.value)
        assertTrue(health.isSuspended())
    }

    @Test
    fun `retrying remote clears the suspension and the banner`() {
        val health = RemoteAsrHealth(context)
        health.recordFailure(RemoteSttFailureKind.OutOfFunds, 402, "Insufficient credits")

        health.resumeRemote()

        assertFalse(health.isSuspended())
        assertNull(health.alert.value)
        assertFalse(RemoteAsrHealth(context).isSuspended())
        assertNull(RemoteAsrHealth(context).alert.value)
    }

    private fun preferences() = context.getSharedPreferences("POCKETCASTS_REMOTE_ASR", Context.MODE_PRIVATE)
}
