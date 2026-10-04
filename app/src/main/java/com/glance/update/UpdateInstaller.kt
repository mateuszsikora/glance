package com.glance.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.glance.kiosk.LockTaskHelper
import java.io.File
import java.security.MessageDigest

/**
 * Verifies and installs a downloaded APK.
 *
 * Installation is silent only because Glance is Device Owner: Android exempts a device owner from
 * the confirmation dialog that every other installer must show. Without Device Owner there is no
 * usable path from a LockTask kiosk, so the update is skipped rather than surfacing a dialog the
 * user cannot reach.
 */
internal object UpdateInstaller {

    sealed class Result {
        /** The session was committed; the process is about to be killed and restarted. */
        object Committed : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun install(context: Context, apk: File, manifest: UpdateManifest, restore: Boolean = false): Result {
        if (!LockTaskHelper.isDeviceOwner(context)) {
            return Result.Rejected("Glance is not Device Owner; silent installation is unavailable")
        }
        val rejection = verify(context, apk, manifest, restore)
        if (rejection != null) return Result.Rejected(rejection)
        val state = RestoreState(context)
        var sessionId = -1

        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            sessionId = installer.createSession(params)
            state.begin(manifest, "${com.glance.BuildConfig.CODE_IDENTITY} / installation ${com.glance.BuildConfig.VERSION_CODE}", sessionId)
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_ENTRY, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(statusIntentSender(context, sessionId))
            }
            Log.i(TAG, "Committed update session $sessionId")
            Result.Committed
        }.getOrElse { error ->
            if (sessionId >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            Log.e(TAG, "Unable to commit update session", error)
            Result.Rejected(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun statusIntentSender(context: Context, sessionId: Int): android.content.IntentSender {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
        // PackageInstaller writes its status extras into this intent, so it must stay mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    /**
     * Android already refuses an update signed by a different certificate. Checking first turns
     * that late, opaque rejection into an early, logged one, and avoids streaming a large APK into
     * a session that cannot succeed.
     */
    internal fun verify(context: Context, apk: File, manifest: UpdateManifest, restore: Boolean): String? {
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, signatureFlags() or PackageManager.GET_META_DATA)
            ?: return "Downloaded file is not an APK"
        val installed = pm.getPackageInfo(context.packageName, signatureFlags())
        return UpdateVerification.reject(manifest, identity(candidate), identity(installed),
            RestoreState(context).dataContract, restore)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val metadata = info.applicationInfo?.metaData
        return ApkIdentity(info.packageName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            certificateDigests(info), metadata?.getString("com.glance.CODE_IDENTITY").orEmpty(),
            metadata?.getString("com.glance.DATA_CONTRACT").orEmpty(),
            metadata?.getString("com.glance.UPDATE_KIND") ?: "normal")
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
    }

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<String> {
        // apkContentsSigners answers "which certificates signed this APK" for both the archive and
        // the installed package. Comparing the rotation history instead would need both sides to
        // report the same lineage, which is a different and weaker question.
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }

        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty()
            .mapTo(mutableSetOf()) { signature ->
                digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }
    }

    private const val TAG = "UpdateInstaller"
    private const val APK_ENTRY = "glance-update"
}
