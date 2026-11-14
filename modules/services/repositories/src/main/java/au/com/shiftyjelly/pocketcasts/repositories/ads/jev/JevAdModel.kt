package au.com.shiftyjelly.pocketcasts.repositories.ads.jev

import au.com.shiftyjelly.pocketcasts.preferences.Settings

/**
 * The jev choice-classifier models served by OpenRouter's System One endpoint.
 *
 * [slug] is the id sent in the request. Responses carry a dated suffix
 * (`respan/span-02-20261015`), so the slug is matched as a prefix and never as an exact identity.
 * There are deliberately only two models: both are verified against the endpoint and costed at
 * roughly $0.00012 per request of 20 sentences.
 */
enum class JevAdModel(val slug: String, val displayName: String) {
    /** Default: the cheaper and more lenient model, ~1.2 s for 20 sentences. */
    SPAN_02("respan/span-02", "Span 02"),

    /** Stricter model, two to three times faster, ~$0.000134 per request of 20 sentences. */
    JEV_1_14("typesafe/jev-1.14", "Jev 1.14"),
    ;

    companion object {
        val default: JevAdModel = SPAN_02

        /** Unknown or empty slugs fall back to [default] so a stale preference never breaks analysis. */
        fun fromSlug(slug: String?): JevAdModel = entries.firstOrNull { it.slug == slug?.trim() } ?: default
    }
}

/**
 * The model chosen in the developer page. The slug is stored as a plain string because
 * [Settings] lives below this module and cannot reference [JevAdModel].
 */
fun Settings.selectedJevAdModel(): JevAdModel = JevAdModel.fromSlug(jevAdModel.value)
