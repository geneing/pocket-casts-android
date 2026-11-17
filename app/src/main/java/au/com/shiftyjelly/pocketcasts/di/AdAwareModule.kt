package au.com.shiftyjelly.pocketcasts.di

import au.com.shiftyjelly.pocketcasts.BuildConfig
import au.com.shiftyjelly.pocketcasts.repositories.di.OpenRouterApiKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Ad-aware additions to the app's dependency graph, kept out of [AppModule] for cleaner upstream merges. */
@Module
@InstallIn(SingletonComponent::class)
object AdAwareModule {

    /** The jev classifier's OpenRouter key, resolved at build time from local.properties or the env. */
    @Provides
    @OpenRouterApiKey
    fun openRouterApiKey(): String = BuildConfig.OPENROUTER_API_KEY
}
