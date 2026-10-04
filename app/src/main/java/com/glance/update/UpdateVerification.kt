package com.glance.update

/** All facts about an APK below must be read from the archive, never trusted from HTTP metadata. */
internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val certificates: Set<String>,
    val codeIdentity: String = "",
    val dataContract: String = "",
    val kind: String = "normal"
)

internal object UpdateVerification {
    fun reject(
        manifest: UpdateManifest, candidate: ApkIdentity, installed: ApkIdentity,
        dataContract: String, restore: Boolean
    ): String? = when {
        candidate.packageName != installed.packageName -> "Wrong package name"
        installed.certificates.isEmpty() || candidate.certificates != installed.certificates -> "Wrong signing certificate"
        candidate.versionCode != manifest.versionCode.toLong() -> "APK installation number differs from manifest"
        candidate.versionCode <= installed.versionCode -> "Android installation number must increase"
        candidate.kind != manifest.kind -> "APK operation differs from manifest"
        manifest.codeIdentity.isNotBlank() && candidate.codeIdentity != manifest.codeIdentity -> "APK code identity differs from manifest"
        manifest.dataContract.isNotBlank() && candidate.dataContract != manifest.dataContract -> "APK data contract differs from manifest"
        restore && (candidate.kind != "restore" || candidate.codeIdentity.isBlank()) -> "Not a recovery build"
        !restore && candidate.kind != "normal" -> "Recovery requires explicit confirmation"
        restore && (candidate.dataContract.isBlank() || candidate.dataContract != dataContract) -> "Incompatible data or migrations"
        else -> null
    }

    fun matchesDigest(expected: String, actual: String): Boolean = expected.equals(actual, ignoreCase = true)
}
