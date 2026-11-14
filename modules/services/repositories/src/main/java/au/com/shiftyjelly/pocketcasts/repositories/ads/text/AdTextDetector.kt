package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads the bundled TF-IDF model once per process. The bundle is an app asset, so analysis
 * never depends on the network and a version mismatch is a build problem, not a runtime one.
 */
@Singleton
class AdTextDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var cached: AdTextModel? = null
    private var failed: String? = null

    suspend fun model(): AdTextModel? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        mutex.withLock {
            cached?.let { return@withLock it }
            failed?.let { return@withLock null }
            runCatching {
                val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                AdTextModel.fromJson(json).also {
                    cached = it
                    LogBuffer.i(TAG, "Loaded ad text model $ASSET")
                }
            }.onFailure {
                failed = it.message
                LogBuffer.e(TAG, it, "Failed to load ad text model")
            }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "AdTextDetector"
        const val ASSET = "ad_detector/model.json"
    }
}
