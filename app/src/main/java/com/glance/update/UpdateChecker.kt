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
    private val installedVersionCode: Int = BuildConfig.VERSION_CODE
) {

    fun checkNow(force: Boolean = false) {
        val url = config.updateUrl
        if (url.isBlank()) return

        val raw = UpdateDownloader.fetchManifest(url) ?: run {
            record("Could not reach the update server")
            return
        }
        val manifest = UpdateManifestParser.parse(raw) ?: run {
            record("Update manifest is malformed")
            return
        }

        val decision = UpdatePolicy.decide(
            manifest = manifest,
            installedVersionCode = installedVersionCode,
            attempts = config.updateAttempts,
            uptimeMs = SystemClock.elapsedRealtime() - GlanceApp.instance.processStartElapsedMs,
            force = force
        )
        when (decision) {
            UpdateDecision.UpToDate -> record("Up to date (build $installedVersionCode)")
            UpdateDecision.NotSettled ->
                Log.i(TAG, "Build $installedVersionCode has not settled yet; deferring update")
            UpdateDecision.Abandoned ->
                record("Build ${manifest.versionCode} failed repeatedly and was abandoned")
            UpdateDecision.Install -> install(manifest)
        }
    }

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
