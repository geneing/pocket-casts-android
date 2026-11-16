package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import ai.moonshine.voice.JNI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineSpeechModelTest {
    @Test
    fun `a plain language code maps to its model`() {
        assertEquals(MoonshineLanguage.EN, speechLanguage("en"))
        assertEquals(MoonshineLanguage.ES, speechLanguage("es"))
    }

    @Test
    fun `a regional code maps to its primary subtag because the catalog only matches exact codes`() {
        assertEquals(MoonshineLanguage.ES, speechLanguage("es-us"))
        assertEquals(MoonshineLanguage.ES, speechLanguage("ES-US"))
        assertEquals(MoonshineLanguage.ES, speechLanguage("es_419"))
        assertEquals(MoonshineLanguage.EN, speechLanguage("en-GB"))
        assertEquals(MoonshineLanguage.EN, speechLanguage("  EN  "))
    }

    @Test
    fun `a language with no model maps to nothing`() {
        assertNull(speechLanguage("pt-BR"))
        assertNull(speechLanguage("de"))
        assertNull(speechLanguage(null))
        assertNull(speechLanguage(""))
        assertNull(speechLanguage("   "))
    }

    @Test
    fun `every model is requested as tiny streaming instead of the catalog default`() {
        assertEquals(JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING, MoonshineLanguage.EN.spec().modelArch)
        assertEquals(JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING, MoonshineLanguage.ES.spec().modelArch)
    }

    @Test
    fun `word timestamps are requested for English only`() {
        assertTrue(MoonshineLanguage.EN.spec().includeWordTimestamps)
        assertFalse(MoonshineLanguage.ES.spec().includeWordTimestamps)
        assertFalse(MoonshineLanguage.EN.spec().includeSpelling)
    }

    @Test
    fun `the cache key matches the directory the installer has to push to`() {
        assertEquals("stt-en-2-wt", MoonshineLanguage.EN.cacheKey)
        assertEquals("stt-es-2", MoonshineLanguage.ES.cacheKey)
    }

    @Test
    fun `each language's version tag is distinct and records the word-timestamp policy`() {
        assertNotEquals(MoonshineLanguage.EN.versionTag, MoonshineLanguage.ES.versionTag)
        assertEquals("moonshine-0.1.6/tiny-streaming-en/wt/cues2", MoonshineLanguage.EN.versionTag)
        assertEquals("moonshine-0.1.6/tiny-streaming-es/cues2", MoonshineLanguage.ES.versionTag)
    }

    @Test
    fun `the engine version covers engine, architecture and every language policy`() {
        assertEquals("moonshine-0.1.6/tiny-streaming-en-wt,es/cues2", MoonshineModels.engineVersion)
    }
}
