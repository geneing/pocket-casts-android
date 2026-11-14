package au.com.shiftyjelly.pocketcasts.repositories.di

import javax.inject.Qualifier

/**
 * The OpenRouter API key, shared by everything in the app that calls OpenRouter: the jev ad
 * classifier and the AI tab's chat.
 *
 * The app module binds it from its `BuildConfig`; a variant that does not configure a key (wear,
 * automotive, tests) leaves it unbound, and the features report themselves unavailable instead of
 * failing.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class OpenRouterApiKey
