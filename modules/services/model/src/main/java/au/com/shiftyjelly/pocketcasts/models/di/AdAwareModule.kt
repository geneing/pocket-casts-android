package au.com.shiftyjelly.pocketcasts.models.di

import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdDetectionQueueDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdFingerprintDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AsrQueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Ad-detection DAO providers, kept out of [ModelModule] for cleaner upstream merges. */
@Module
@InstallIn(SingletonComponent::class)
object AdAwareModule {

    @Provides
    fun provideAdSegmentDao(database: AppDatabase): AdSegmentDao = database.adSegmentDao()

    @Provides
    fun provideAdAnalysisDao(database: AppDatabase): AdAnalysisDao = database.adAnalysisDao()

    @Provides
    fun provideAdCueScoreDao(database: AppDatabase): AdCueScoreDao = database.adCueScoreDao()

    @Provides
    fun provideAdFingerprintDao(database: AppDatabase): AdFingerprintDao = database.adFingerprintDao()

    @Provides
    fun provideAsrQueueDao(database: AppDatabase): AsrQueueDao = database.asrQueueDao()

    @Provides
    fun provideAdDetectionQueueDao(database: AppDatabase): AdDetectionQueueDao = database.adDetectionQueueDao()
}
