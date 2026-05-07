package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class AddonUrlSanitizerTest {
    @Test
    fun encodesPipeSeparatorsInTorrentioConfigUrl() {
        val raw =
            "https://torrentio.strem.fun/language=french|debridoptions=nodownloadlinks|alldebrid=xxx/manifest.json"
        val expected =
            "https://torrentio.strem.fun/language=french%7Cdebridoptions=nodownloadlinks%7Calldebrid=xxx/manifest.json"

        assertEquals(expected, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun preservesAlreadyEncodedSequences() {
        val raw =
            "https://torrentio.strem.fun/language=french%7Cdebridoptions=nodownloadlinks/manifest.json"

        assertEquals(raw, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun mixesEncodedAndRawIllegalCharactersWithoutDoubleEncoding() {
        val raw = "https://example.com/a%7Cb|c/manifest.json"
        val expected = "https://example.com/a%7Cb%7Cc/manifest.json"

        assertEquals(expected, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun preservesValidUrlStructureCharacters() {
        val raw = "https://user:pass@host.example.com:8443/a/b?x=1&y=2#frag"

        assertEquals(raw, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun encodesSpacesAndBracketsAndOtherIllegalPathCharacters() {
        val raw = "https://example.com/path with spaces/[id]/{extra}/manifest.json"
        val expected =
            "https://example.com/path%20with%20spaces/%5Bid%5D/%7Bextra%7D/manifest.json"

        assertEquals(expected, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun loneStandalonePercentSignIsEncoded() {
        val raw = "https://example.com/100%off/manifest.json"
        val expected = "https://example.com/100%25off/manifest.json"

        assertEquals(expected, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun keepsLowercasePercentEncodedSequenceUntouched() {
        val raw = "https://example.com/path/a%7cb/manifest.json"

        assertEquals(raw, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun encodesPipeInQueryStringWithoutTouchingDelimiters() {
        val raw = "https://example.com/manifest.json?config=french|nolinks&id=42"
        val expected = "https://example.com/manifest.json?config=french%7Cnolinks&id=42"

        assertEquals(expected, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun returnsIdenticalStringWhenAllCharactersAreSafe() {
        val raw = "https://torrentio.strem.fun/manifest.json"

        assertEquals(raw, sanitizeUrlForJavaUri(raw))
    }

    @Test
    fun handlesEmptyStringWithoutThrowing() {
        assertEquals("", sanitizeUrlForJavaUri(""))
    }
}
