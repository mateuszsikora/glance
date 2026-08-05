package com.glance.update

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glance.BuildConfig
import com.glance.GlanceApp
import com.glance.config.AppConfig

/**
 * Fetches the update manifest and, when a newer signed build is offered, installs it.
 *
 * Every step is blocking; call [checkNow] from a background thread only.
 */
class UpdateChecker(
    private val context: Context,
    private val config: AppConfig = GlanceApp.instance.appConfig,
    private val installedVersionCode: Int = BuildConfig.VERSION_CODE,
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * @param install whether a newer build may be installed by this check. Defaults to the
     *   automatic-update switch, so a check runs — and keeps the reported state fresh — either way.
     * @param force skips the guards that protect an unattended tablet. Only meaningful together
     *   with [install], and reserved for an operator asking for a specific installation.
     */
    fun checkNow(force: Boolean = false, install: Boolean = config.autoUpdateEnabled) {
        val url = config.updateUrl
        if (url.isBlank()) return

        val raw = UpdateDownloader.fetchManifest(url) ?: run {
            // The previously offered version is left untouched: a server that cannot be reached
            // has not withdrawn anything, it has simply told us nothing.
            config.updateCheck = config.updateCheck.copy(checkedAt = now(), serverReachable = false)
            record("Could not reach the update server")
            return
        }
        val manifest = UpdateManifestParser.parse(raw) ?: run {
            config.updateCheck = UpdateCheckState(checkedAt = now(), serverReachable = true)
            record("Update manifest is malformed")
            return
        }
        config.updateCheck = UpdateCheckState(
            checkedAt = now(),
            serverReachable = true,
            availableVersionCode = manifest.versionCode,
            availableVersionName = manifest.versionName
        )

        val decision = UpdatePolicy.decide(
            manifest = manifest,
            installedVersionCode = installedVersionCode,
            attempts = config.updateAttempts,
            uptimeMs = SystemClock.elapsedRealtime() - GlanceApp.instance.processStartElapsedMs,
            install = install,
            force = force
        )
        when (decision) {
            UpdateDecision.UpToDate -> record("Up to date (build $installedVersionCode)")
            UpdateDecision.Available ->
                record("${describe(manifest)} is available to install")
            UpdateDecision.NotSettled ->
                Log.i(TAG, "Build $installedVersionCode has not settled yet; deferring update")
            UpdateDecision.Abandoned ->
                record("Build ${manifest.versionCode} failed repeatedly and was abandoned")
            UpdateDecision.Install -> install(manifest)
        }
    }

    /** Installs whatever the server currently offers, whatever the switch and the guards say. */
    fun installNow() = checkNow(force = true, install = true)

    private fun describe(manifest: UpdateManifest): String =
        UpdateSummary.version(manifest.versionName, manifest.versionCode)

    private fun install(manifest: UpdateManifest) {
        Log.i(TAG, "Update ${manifest.versionName} (${manifest.versionCode}) available")
        val apk = UpdateStorage.stagedApk(context)

        val digest = UpdateDownloader.downloadApk(manifest.url, apk)
        if (digest == null) {
            record("Download of build ${manifest.versionCode} failed")
            countAttempt(manifest)
            return
        }
        if (!digest.equals(manifest.sha256, ignoreCase = true)) {
            Log.w(TAG, "Digest mismatch: expected ${manifest.sha256}, got $digest")
            record("Build ${manifest.versionCode} failed its checksum")
            UpdateStorage.clearStagedApk(context)
            countAttempt(manifest)
            return
        }

        // Counted before committing: a successful self-update kills this process, so this is the
        // last point at which anything can be persisted. GlanceApp clears it on the next start
        // once the new versionCode is actually running.
        countAttempt(manifest)

        when (val result = UpdateInstaller.install(context, apk)) {
            is UpdateInstaller.Result.Committed ->
                record("Installing build ${manifest.versionCode}")
            is UpdateInstaller.Result.Rejected -> {
                Log.w(TAG, "Update rejected: ${result.reason}")
                record("Build ${manifest.versionCode} rejected: ${result.reason}")
                UpdateStorage.clearStagedApk(context)
            }
        }
    }

    private fun countAttempt(manifest: UpdateManifest) {
        config.updateAttempts = UpdatePolicy.recordFailure(
            config.updateAttempts,
            manifest.versionCode
        )
    }

    private fun record(status: String) {
        Log.i(TAG, status)
        config.updateStatus = status
    }

    companion object {
        private const val TAG = "UpdateChecker"
    }
}
