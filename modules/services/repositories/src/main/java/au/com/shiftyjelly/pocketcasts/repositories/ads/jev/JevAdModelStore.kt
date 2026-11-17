package au.com.shiftyjelly.pocketcasts.repositories.ads.jev

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The jev model chosen in the developer page.
 *
 * It keeps its own preferences file so the ad feature owns the whole preference, instead of adding
 * a string setting to upstream's [au.com.shiftyjelly.pocketcasts.preferences.Settings]. Unknown or
 * empty slugs fall back through [JevAdModel.fromSlug], so a stale value never breaks analysis.
 */
@Singleton
class JevAdModelStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectedJevAdModel(): JevAdModel = JevAdModel.fromSlug(prefs.getString(KEY_MODEL, JevAdModel.default.slug))

    fun select(model: JevAdModel) {
        prefs.edit { putString(KEY_MODEL, model.slug) }
    }

    private companion object {
        const val PREFS_NAME = "jev_ad_model"
        const val KEY_MODEL = "jevAdModel"
    }
}
