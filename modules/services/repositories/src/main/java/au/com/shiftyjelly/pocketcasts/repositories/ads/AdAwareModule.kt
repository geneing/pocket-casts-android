package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.EpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetEpisodeTranscriber
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
     * The only speech recognizer: Parakeet TDT 0.6B v3 on LiteRT, on the Tensor G6's NPU when the
     * phone has one and on the CPU otherwise, with the graph and tokenizer installed on device or
     * downloaded on Wi-Fi. One multilingual graph covers every allowed language, so there is nothing
     * per-language to bind.
     */
    @Binds
    @Singleton
    abstract fun provideEpisodeTranscriber(impl: ParakeetEpisodeTranscriber): EpisodeTranscriber

    /**
     * The OpenRouter key is optional so every app variant's graph stays valid: the app binds it
     * from `BuildConfig`, and a variant without one simply has no remote ad classifier.
     */
    @BindsOptionalOf
    @OpenRouterApiKey
    abstract fun optionalOpenRouterApiKey(): String
}
