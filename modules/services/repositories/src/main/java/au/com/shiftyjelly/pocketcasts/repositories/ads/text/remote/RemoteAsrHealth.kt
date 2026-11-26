package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.content.Context
import android.content.SharedPreferences
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why one remote request failed, as the fallback and the banner treat it.
 *
 * The split is not cosmetic: a model that is gone and an account with no credit will not fix
 * themselves, so they suspend the remote engine on their own, while a network or API refusal has to
 * repeat before the app decides the engine is down rather than that one request was unlucky.
 */
enum class RemoteSttFailureKind {
    /** OpenRouter no longer serves the model: retired, renamed, or no provider for it left. */
    ModelUnavailable,

    /** The account behind the key has no credit left. */
    OutOfFunds,

    /** The request never reached the provider, timed out, or the provider was too busy. */
    Network,

    /** Any other refusal; [RemoteAsrAlert.status] carries the HTTP status to report the type. */
    Api,
}

/**
 * A remote-transcription problem the app tells the user about: what failed, in what way, and when.
 *
 * It is shown in the same bottom bar the player uses for its own problems, and it stays there until
 * it is dismissed — a pass runs in the background, so the failure may be hours old by the time the
 * app is open. [status] is the HTTP status where there was one, which is what makes an [Api] failure
 * report its type instead of only its message.
 */
data class RemoteAsrAlert(
    val kind: RemoteSttFailureKind,
    val status: Int?,
    val detail: String,
    val atMs: Long,
)

/**
 * Whether remote transcription is working, and what to tell the user when it is not.
 *
 * A failure that repeats — the same refusal on the next pass — suspends the remote engine, which is
 * what makes [SelectingEpisodeTranscriber] fall back to Parakeet and what raises [alert]. Both are
 * persisted, so a failure found by a background pass is still shown on the next launch, and a
 * suspended engine stays suspended across restarts instead of quietly retrying a service that is
 * known to be down. Only the user brings remote back: [resumeRemote] from the banner, which clears
 * the suspension and the alert together.
 *
 * Consecutive failures are counted in memory only: a restart is allowed one more attempt, which
 * costs a request and is the cheapest way for a fixed account or a recovered network to come back.
 */
@Singleton
class RemoteAsrHealth @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _alert = MutableStateFlow(readAlert())
    val alert: StateFlow<RemoteAsrAlert?> = _alert.asStateFlow()

    @Volatile
    private var suspended: Boolean = preferences.getBoolean(KEY_SUSPENDED, false)

    /** Requests that failed in a row; a success means the engine works and resets the count. */
    private var consecutiveFailures = 0

    fun isSuspended(): Boolean = suspended

    /** One request went through, so the engine is working whatever happened before it. */
    @Synchronized
    fun recordSuccess() {
        consecutiveFailures = 0
    }

    /**
     * Records one failed request and suspends the remote engine once the failure is consistent:
     * immediately for a kind that cannot fix itself, after [CONSISTENT_FAILURES] in a row otherwise.
     */
    @Synchronized
    fun recordFailure(kind: RemoteSttFailureKind, status: Int?, detail: String) {
        if (suspended) return
        consecutiveFailures++
        val threshold = when (kind) {
            RemoteSttFailureKind.ModelUnavailable, RemoteSttFailureKind.OutOfFunds -> 1
            RemoteSttFailureKind.Network, RemoteSttFailureKind.Api -> CONSISTENT_FAILURES
        }
        if (consecutiveFailures < threshold) {
            LogBuffer.w(TAG, "Remote transcription request failed ($kind, ${consecutiveFailures}x): $detail")
            return
        }
        suspended = true
        preferences.edit()
            .putBoolean(KEY_SUSPENDED, true)
            .putString(KEY_ALERT_KIND, kind.name)
            .putInt(KEY_ALERT_STATUS, status ?: NO_STATUS)
            .putString(KEY_ALERT_DETAIL, detail.take(DETAIL_CHARS))
            .putLong(KEY_ALERT_AT, System.currentTimeMillis())
            .apply()
        val alert = RemoteAsrAlert(
            kind = kind,
            status = status,
            detail = detail.take(DETAIL_CHARS),
            atMs = System.currentTimeMillis(),
        )
        _alert.value = alert
        LogBuffer.w(
            TAG,
            "Remote transcription suspended after $consecutiveFailures failure(s): $kind ${status ?: ""} $detail. " +
                "Recognising on device until it is resumed.",
        )
    }

    /** Hides the banner; the remote engine stays suspended until [resumeRemote]. */
    @Synchronized
    fun dismiss() {
        if (_alert.value == null) return
        _alert.value = null
        preferences.edit()
            .remove(KEY_ALERT_KIND)
            .remove(KEY_ALERT_STATUS)
            .remove(KEY_ALERT_DETAIL)
            .remove(KEY_ALERT_AT)
            .apply()
    }

    /** Brings remote transcription back, forgetting both the suspension and the alert. */
    @Synchronized
    fun resumeRemote() {
        consecutiveFailures = 0
        suspended = false
        _alert.value = null
        preferences.edit()
            .putBoolean(KEY_SUSPENDED, false)
            .remove(KEY_ALERT_KIND)
            .remove(KEY_ALERT_STATUS)
            .remove(KEY_ALERT_DETAIL)
            .remove(KEY_ALERT_AT)
            .apply()
        LogBuffer.i(TAG, "Remote transcription resumed by the user")
    }

    private fun readAlert(): RemoteAsrAlert? {
        val kind = preferences.getString(KEY_ALERT_KIND, null)
            ?.let { name -> RemoteSttFailureKind.entries.firstOrNull { it.name == name } }
            ?: return null
        val status = preferences.getInt(KEY_ALERT_STATUS, NO_STATUS).takeIf { it != NO_STATUS }
        return RemoteAsrAlert(
            kind = kind,
            status = status,
            detail = preferences.getString(KEY_ALERT_DETAIL, "").orEmpty(),
            atMs = preferences.getLong(KEY_ALERT_AT, 0L),
        )
    }

    private companion object {
        const val TAG = "RemoteAsr"
        const val PREFERENCES = "POCKETCASTS_REMOTE_ASR"
        const val KEY_SUSPENDED = "suspended"
        const val KEY_ALERT_KIND = "alert_kind"
        const val KEY_ALERT_STATUS = "alert_status"
        const val KEY_ALERT_DETAIL = "alert_detail"
        const val KEY_ALERT_AT = "alert_at"

        /** Two refusals in a row is a service that is down, not one request that was unlucky. */
        const val CONSISTENT_FAILURES = 2

        const val NO_STATUS = 0
        const val DETAIL_CHARS = 300
    }
}
