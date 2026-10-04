package com.glance.update

import android.content.Context
import android.provider.Settings
import com.glance.BuildConfig

/** Separate from user configuration; synchronously durable before PackageInstaller can kill us. */
class RestoreState(private val context: Context) {
    private val prefs = context.getSharedPreferences("glance_restore", Context.MODE_PRIVATE)
    val paused: Boolean get() = prefs.getBoolean("paused", false)
    val sessionId: Int get() = prefs.getInt("session", -1)
    val pending: Int get() = prefs.getInt("pending", 0)
    val outcome: String get() = prefs.getString("outcome", "").orEmpty()
    val candidate: UpdateManifest? get() = UpdateManifestParser.parse(prefs.getString("candidate", "").orEmpty())
    val dataContract: String get() = prefs.getString("dataContract", BuildConfig.DATA_CONTRACT).orEmpty()

    fun offer(raw: String) = synchronized(stateLock) { check(prefs.edit().putString("candidate", raw).commit()) }
    fun record(message: String) = synchronized(stateLock) { check(prefs.edit().putString("outcome", message).commit()) }
    fun begin(manifest: UpdateManifest, current: String, sessionId: Int = -1) = synchronized(stateLock) {
        check(prefs.edit().putInt("pending", manifest.versionCode).putInt("session", sessionId)
            .putInt("bootCount", bootCount())
            .putBoolean("paused", paused || manifest.kind == "restore")
            .putString("outcome", "Installing $current → ${manifest.codeIdentity.ifBlank { manifest.versionName }} / installation ${manifest.versionCode}")
            .commit())
    }
    fun failed(message: String) = synchronized(stateLock) {
        check(prefs.edit().remove("pending").remove("session").remove("bootCount").putString("outcome", "Installation failed: $message").commit())
    }
    fun reconcile(installed: Int = BuildConfig.VERSION_CODE) = synchronized(stateLock) {
        val attempted = pending
        if (attempted in 1..installed) {
            // An intermediate legacy build cannot reconcile our bookkeeping. Once a newer
            // installation is running, that old request must not be treated as interrupted.
            val result = if (attempted == installed) {
                "Installed code ${BuildConfig.CODE_IDENTITY} / installation $installed successfully"
            } else {
                "Pending installation $attempted superseded by running installation $installed"
            }
            check(prefs.edit().remove("pending").remove("session").remove("bootCount")
                .putString("outcome", result).commit())
        }
        check(prefs.edit().putString("dataContract", BuildConfig.DATA_CONTRACT).commit())
    }
    private fun bootCount(): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    /** A sealed session is not proof of progress: Android 8 restores it after reboot without
     * re-enqueuing commit or restoring its result observer. Leave same-boot commits alone. */
    fun recoverInterruptedSession() = synchronized(stateLock) {
        if (pending == 0) return@synchronized
        val session = context.packageManager.packageInstaller.getSessionInfo(sessionId)
        val previousBoot = prefs.getInt("bootCount", -1)
        val currentBoot = bootCount()
        val rebooted = previousBoot >= 0 && currentBoot >= 0 && previousBoot != currentBoot
        if (session == null || !session.isSealed || (rebooted && !session.isActive)) {
            runCatching {
                abandonPending("Interrupted installation abandoned; retry or resume updates explicitly")
            }.onFailure {
                record("Could not abandon interrupted installation: ${it.message}. Cancel it from the remote panel.")
            }
        }
    }

    /** Also available explicitly to an authenticated operator when boot identity is unavailable. */
    internal fun abandonPending(message: String) = synchronized(stateLock) {
        check(pending != 0) { "No installation is pending" }
        val installer = context.packageManager.packageInstaller
        if (installer.getSessionInfo(sessionId) != null) installer.abandonSession(sessionId)
        check(prefs.edit().remove("pending").remove("session").remove("bootCount")
            .putBoolean("paused", true).putString("outcome", message).commit())
    }

    fun resume() = synchronized(stateLock) {
        check(pending == 0) { "Installation is still pending" }
        check(prefs.edit().putBoolean("paused", false).putString("outcome", "Normal updates resumed").commit())
    }

    /** Keep callback validation, its terminal state change and staging cleanup indivisible with
     * cancellation and replacement. This lock is never held during network downloads. */
    internal fun forSession(reportedSession: Int, action: () -> Unit) = synchronized(stateLock) {
        if (pending != 0 && reportedSession == sessionId) action()
    }

    private companion object {
        val stateLock = Any()
    }
}
