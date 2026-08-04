package com.glance.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestParserTest {

    @Test
    fun parsesCompleteManifest() {
        val manifest = UpdateManifestParser.parse(
            """
            {"versionCode": 412, "versionName": "1.4-412",
             "url": "http://192.168.1.10:8080/glance.apk", "sha256": "$DIGEST"}
            """.trimIndent()
        )

        assertNotNull(manifest)
        assertEquals(412, manifest!!.versionCode)
        assertEquals("1.4-412", manifest.versionName)
        assertEquals("http://192.168.1.10:8080/glance.apk", manifest.url)
        assertEquals(DIGEST, manifest.sha256)
    }

    @Test
    fun fallsBackToVersionCodeWhenNameIsMissing() {
        val manifest = UpdateManifestParser.parse(
            """{"versionCode": 7, "url": "https://host/a.apk", "sha256": "$DIGEST"}"""
        )

        assertEquals("7", manifest?.versionName)
    }

    @Test
    fun normalizesDigestCase() {
        val manifest = UpdateManifestParser.parse(
            """{"versionCode": 7, "url": "https://host/a.apk", "sha256": "${DIGEST.uppercase()}"}"""
        )

        assertEquals(DIGEST, manifest?.sha256)
    }

    @Test
    fun rejectsIncompleteOrUnusableManifests() {
        val cases = mapOf(
            "not json" to "<html>nope</html>",
            "missing version" to """{"url": "https://host/a.apk", "sha256": "$DIGEST"}""",
            "zero version" to """{"versionCode": 0, "url": "https://h/a.apk", "sha256": "$DIGEST"}""",
            "missing url" to """{"versionCode": 7, "sha256": "$DIGEST"}""",
            "missing digest" to """{"versionCode": 7, "url": "https://host/a.apk"}""",
            "short digest" to """{"versionCode": 7, "url": "https://host/a.apk", "sha256": "abc"}""",
            "non-hex digest" to
                """{"versionCode": 7, "url": "https://host/a.apk", "sha256": "${"z".repeat(64)}"}"""
        )

        cases.forEach { (name, raw) ->
            assertNull("expected $name to be rejected", UpdateManifestParser.parse(raw))
        }
    }

    @Test
    fun rejectsUrlSchemesThatCannotBeDownloaded() {
        val cases = listOf("file:///data/local/tmp/a.apk", "ftp://host/a.apk", "/relative/a.apk")

        cases.forEach { url ->
            val raw = """{"versionCode": 7, "url": "$url", "sha256": "$DIGEST"}"""
            assertNull("expected $url to be rejected", UpdateManifestParser.parse(raw))
        }
    }

    private companion object {
        const val DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
