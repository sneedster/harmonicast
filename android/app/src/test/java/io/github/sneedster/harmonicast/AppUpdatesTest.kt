package io.github.sneedster.harmonicast

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String = "v1.2.0", prerelease: Boolean = false, url: String = "https://github.com/sneedster/harmonicast/releases/download/$tag/harmonicast-${tag.removePrefix("v")}.apk", digest: String = "sha256:" + "a".repeat(64)) = """{
        "tag_name":"$tag","prerelease":$prerelease,"draft":false,"body":"Notes",
        "assets":[{"name":"harmonicast-${tag.removePrefix("v")}.apk","browser_download_url":"$url","digest":"$digest","size":100}]
    }"""
    @Test fun comparesNumericVersionsAndRejectsPrereleaseNames() {
        assertTrue(newerVersion("v1.10.0", "1.9.9"))
        assertFalse(newerVersion("1.1.1", "1.1.1"))
        assertFalse(newerVersion("1.0.9", "1.1.1"))
        assertFalse(newerVersion("1.2.0-beta", "1.1.1"))
        assertFalse(newerVersion("999999999999.0.0", "1.1.1"))
    }
    @Test fun acceptsOnlyNewStableRelease() {
        assertEquals("1.2.0", parseAppRelease(release(), "1.1.1")?.version)
        assertNull(parseAppRelease(release(), "1.2.0"))
        assertNull(parseAppRelease(release(prerelease = true), "1.1.1"))
    }
    @Test fun rejectsUnexpectedAssetOriginAndMissingDigest() {
        for (json in listOf(release(url = "https://other.example/app.apk"), release(digest = ""))) {
            try { parseAppRelease(json, "1.1.1"); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsWrongPackageSignerAndDowngrade() {
        validateUpdateIdentity("app", "app", "1.2.0", "1.2.0", 60, 58, setOf("owner"), setOf("owner"))
        for ((pkg, code, signer) in listOf(Triple("other", 60L, "owner"), Triple("app", 58L, "owner"), Triple("app", 60L, "other"))) {
            try { validateUpdateIdentity(pkg, "app", "1.2.0", "1.2.0", code, 58, setOf(signer), setOf("owner")); fail("Expected rejection") }
            catch (_: IllegalStateException) { }
        }
    }
}
