package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fences in-flight detection results against resets and deletion in this process. */
@Singleton
class AnalysisWriteGuard @Inject constructor() {
    private val mutex = Mutex()
    private val generations = mutableMapOf<String, Long>()

    suspend fun generation(episodeUuid: String): Long = mutex.withLock { generations[episodeUuid] ?: 0L }

    suspend fun invalidate(episodeUuids: Collection<String>, action: suspend () -> Unit) = mutex.withLock {
        episodeUuids.forEach { generations[it] = (generations[it] ?: 0L) + 1L }
        action()
    }

    suspend fun invalidateIfCurrent(episodeUuid: String, generation: Long, action: suspend () -> Unit): Long? = mutex.withLock {
        if ((generations[episodeUuid] ?: 0L) != generation) return@withLock null
        val next = generation + 1L
        generations[episodeUuid] = next
        action()
        next
    }

    suspend fun invalidateIf(
        episodeUuid: String,
        condition: suspend () -> Boolean,
        action: suspend () -> Boolean,
    ): Boolean = mutex.withLock {
        if (!condition()) return@withLock false
        generations[episodeUuid] = (generations[episodeUuid] ?: 0L) + 1L
        action()
    }

    suspend fun <T> ifCurrent(episodeUuid: String, generation: Long, action: suspend () -> T): T? = mutex.withLock {
        if ((generations[episodeUuid] ?: 0L) == generation) action() else null
    }
}
