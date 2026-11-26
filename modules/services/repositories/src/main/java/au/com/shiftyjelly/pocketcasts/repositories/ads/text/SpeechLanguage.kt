package au.com.shiftyjelly.pocketcasts.repositories.ads.text

/**
 * The languages the app's speech recognition is allowed to be asked for.
 *
 * Both engines cover the same European set: Parakeet's multilingual graph and the remote Nemotron
 * model were trained on it. A podcast in any other language is left without generated detection
 * instead of being handed to a model that would answer with confident nonsense, which is worse than
 * no transcript at all.
 */
internal object SpeechLanguage {
    /** The ISO-639-1 language to ask an engine for, or null when it is not covered. */
    fun primary(podcastLanguage: String?): String? {
        val normalised = podcastLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ENGLISH_LANGUAGE
        val primary = normalised.substringBefore('-').substringBefore('_')
        return primary.takeIf { it in MULTILINGUAL }
    }

    /** What a podcast that declares no language is transcribed as. */
    private const val ENGLISH_LANGUAGE = "en"

    private val MULTILINGUAL = setOf(
        "bg",
        "hr",
        "cs",
        "da",
        "nl",
        "el",
        "en",
        "et",
        "fi",
        "fr",
        "de",
        "hu",
        "it",
        "lv",
        "lt",
        "mt",
        "pl",
        "pt",
        "ro",
        "sk",
        "sl",
        "es",
        "sv",
        "ru",
        "uk",
    )
}
