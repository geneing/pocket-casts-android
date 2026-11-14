package au.com.shiftyjelly.pocketcasts.repositories.di

import javax.inject.Qualifier

/**
 * The OpenRouter API key used by the jev ad classifier.
 *
 * The app module binds it from its `BuildConfig`; app variants that do not configure a key (wear,
 * automotive, tests) leave it unbound, which makes the classifier report
 * [au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevResponse.Unavailable] instead of failing
 * analysis.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class JevApiKey
