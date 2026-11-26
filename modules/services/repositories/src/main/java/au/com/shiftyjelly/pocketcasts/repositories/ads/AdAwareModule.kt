package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.EpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.SelectingEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.di.OpenRouterApiKey
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Ad-detection bindings, kept out of [au.com.shiftyjelly.pocketcasts.repositories.di.RepositoryModule] for cleaner upstream merges. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AdAwareModule {

    @Binds
    abstract fun provideAdSegmentManager(adSegmentManagerImpl: AdSegmentManagerImpl): AdSegmentManager

    /**
     * The recogniser: remote Nemotron over OpenRouter when the remote flag is on and the build has
     * a key, Parakeet TDT 0.6B v3 on LiteRT otherwise. [SelectingEpisodeTranscriber] makes that
     * choice per pass, so the dev toggle takes effect without a restart, and its version follows
     * the engine it picked so transcripts from the other one are recognised again.
     */
    @Binds
    @Singleton
    abstract fun provideEpisodeTranscriber(impl: SelectingEpisodeTranscriber): EpisodeTranscriber

    /**
     * The OpenRouter key is optional so every app variant's graph stays valid: the app binds it
     * from `BuildConfig`, and a variant without one simply has no remote ad classifier.
     */
    @BindsOptionalOf
    @OpenRouterApiKey
    abstract fun optionalOpenRouterApiKey(): String
}
