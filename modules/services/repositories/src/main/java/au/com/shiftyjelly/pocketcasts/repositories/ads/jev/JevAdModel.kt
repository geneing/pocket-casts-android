package au.com.shiftyjelly.pocketcasts.repositories.ads.jev

/**
 * The jev choice-classifier models served by OpenRouter's System One endpoint.
 *
 * [slug] is the id sent in the request. Responses carry a dated suffix
 * (`typesafe/jev-1.14-20261014`), so the slug is matched as a prefix and never as an exact identity.
 * There are deliberately only two models: both are verified against the endpoint and costed at
 * roughly $0.00012 per request of 20 sentences.
 */
enum class JevAdModel(val slug: String, val displayName: String) {
    /** The more lenient model, ~1.2 s for 20 sentences. */
    SPAN_02("respan/span-02", "Span 02"),

    /** Default: stricter, two to three times faster, ~$0.000134 per request of 20 sentences. */
    JEV_1_14("typesafe/jev-1.14", "Jev 1.14"),
    ;

    companion object {
        val default: JevAdModel = JEV_1_14

        /** Unknown or empty slugs fall back to [default] so a stale preference never breaks analysis. */
        fun fromSlug(slug: String?): JevAdModel = entries.firstOrNull { it.slug == slug?.trim() } ?: default
    }
}
