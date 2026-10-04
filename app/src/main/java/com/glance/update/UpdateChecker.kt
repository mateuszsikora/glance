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
    fun checkNow(force: Boolean = false, install: Boolean = config.autoUpdateEnabled) = synchronized(operationLock) {
        val state = RestoreState(context)
        if (state.paused || state.pending != 0) return@synchronized
        checkLocked(force, install)
    }

    private fun checkLocked(force: Boolean, install: Boolean) {
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

        if (manifest.kind != "normal") {
            record("Recovery builds require explicit confirmation in the remote panel")
            return
        }
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

    /** Explicit ordinary install; recovery pause, pending session and identity checks still apply. */
    fun installNow() = checkNow(force = true, install = true)

    private fun describe(manifest: UpdateManifest): String =
        UpdateSummary.version(manifest.versionName, manifest.versionCode)

    private fun install(manifest: UpdateManifest, restore: Boolean = false) {
        Log.i(TAG, "Update ${manifest.versionName} (${manifest.versionCode}) available")
        val apk = UpdateStorage.stagedApk(context)

        val digest = UpdateDownloader.downloadApk(manifest.url, apk)
        if (digest == null) {
            record("Download of build ${manifest.versionCode} failed")
            countAttempt(manifest)
            return
        }
        if (!UpdateVerification.matchesDigest(manifest.sha256, digest)) {
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

        when (val result = UpdateInstaller.install(context, apk, manifest, restore)) {
            is UpdateInstaller.Result.Committed ->
                Log.i(TAG, "Installing build ${manifest.versionCode}")
            is UpdateInstaller.Result.Rejected -> {
                Log.w(TAG, "Update rejected: ${result.reason}")
                RestoreState(context).failed(result.reason)
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
        RestoreState(context).record(status)
    }

    fun checkRestore() = synchronized(operationLock) {
        val state = RestoreState(context)
        if (state.pending != 0) return@synchronized
        val url = java.net.URI(config.updateUrl).resolve("glance-restore.json").toString()
        val raw = UpdateDownloader.fetchManifest(url)
        val target = raw?.let(UpdateManifestParser::parse)
        if (target == null || target.kind != "restore") {
            state.offer("")
            state.record("No valid recovery build available")
        } else {
            state.offer(raw)
            state.record("Recovery target: code ${target.codeIdentity} / installation ${target.versionCode}. Confirm to install.")
        }
    }

    fun restoreNow(expectedVersion: Int, expectedHash: String) = synchronized(operationLock) {
        val state = RestoreState(context)
        val target = state.candidate
        if (state.pending != 0 || target == null || target.versionCode != expectedVersion ||
            target.sha256 != expectedHash || target.kind != "restore" || target.versionCode <= installedVersionCode) {
            state.record("Recovery rejected: stale confirmation or installation already pending")
            return@synchronized
        }
        if (target.dataContract != state.dataContract) {
            state.record("Recovery rejected: incompatible data or migrations")
            return@synchronized
        }
        install(target, restore = true)
    }

    fun cancelPending(expectedVersion: Int, expectedSession: Int) = synchronized(operationLock) {
        val state = RestoreState(context)
        check(state.pending != 0 && state.pending == expectedVersion && state.sessionId == expectedSession) {
            "Pending installation changed; reload before cancelling"
        }
        state.abandonPending("Installation cancelled by operator. Updates remain paused until explicitly resumed.")
    }

    fun resumeUpdates() = synchronized(operationLock) { RestoreState(context).resume() }

    companion object {
        // Watchdog, local settings and HTTP workers share one staging file and one install session.
        internal val operationLock = Any()
        private const val TAG = "UpdateChecker"
    }
}
