package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether ad analysis may read the transcript the show or our servers published, or has to
 * recognise every episode on the device.
 *
 * Reading a published transcript is both cheaper and better, so it is the default. Recognising
 * everything exists only to exercise the on-device engine end to end while it is being brought up
 * ([Feature.FORCE_ON_DEVICE_TRANSCRIPT], on in debug builds), and it is behind this interface so a
 * test can choose either side without touching global state.
 */
fun interface TranscriptSourcePolicy {
    /** True when published transcripts are to be ignored and the audio recognised instead. */
    fun ignoresPublishedTranscript(): Boolean
}

@Singleton
class FeatureFlagTranscriptPolicy @Inject constructor() : TranscriptSourcePolicy {
    override fun ignoresPublishedTranscript(): Boolean = FeatureFlag.isEnabled(Feature.FORCE_ON_DEVICE_TRANSCRIPT)
}
