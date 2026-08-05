package com.glance.update

import org.json.JSONObject
import java.util.Locale

/**
 * Contents of the update manifest published by a self-hosted updater.
 *
 * The manifest is deliberately tiny so the tablet can poll it frequently without downloading the
 * APK itself:
 *
 * ```json
 * {"versionCode": 412, "versionName": "1.4-412", "url": "http://host/glance.apk", "sha256": "…"}
 * ```
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String
)

object UpdateManifestParser {

    /** Returns null for anything that is not a complete, well-formed manifest. */
    fun parse(raw: String): UpdateManifest? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val versionCode = json.optInt(FIELD_VERSION_CODE, -1)
        if (versionCode <= 0) return null

        val url = json.optString(FIELD_URL).trim()
        if (!isSupportedUrl(url)) return null

        // A truncated or corrupted download would be rejected by the signature check anyway, but
        // verifying the digest first keeps a failed transfer from ever reaching PackageInstaller.
        val sha256 = json.optString(FIELD_SHA256).trim().lowercase(Locale.US)
        if (!SHA256_REGEX.matches(sha256)) return null

        val versionName = json.optString(FIELD_VERSION_NAME).trim()

        return UpdateManifest(
            versionCode = versionCode,
            versionName = versionName.ifBlank { versionCode.toString() },
            url = url,
            sha256 = sha256
        )
    }

    /**
     * Cleartext HTTP is allowed because self-hosted updaters normally serve the APK by IP on a
     * local network, where certificate setup is impractical. Transport is not the trust anchor
     * here: [UpdateInstaller] rejects any APK not signed by the certificate that signed the
     * running installation, and Android enforces the same rule again during installation.
     */
    private fun isSupportedUrl(url: String): Boolean {
        val lowercase = url.lowercase(Locale.US)
        return lowercase.startsWith("http://") || lowercase.startsWith("https://")
    }

    private const val FIELD_VERSION_CODE = "versionCode"
    private const val FIELD_VERSION_NAME = "versionName"
    private const val FIELD_URL = "url"
    private const val FIELD_SHA256 = "sha256"
    private val SHA256_REGEX = Regex("[0-9a-f]{64}")
}
