package com.glance.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glance.BuildConfig
import com.glance.config.AppConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RecoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val installed = ApkIdentity("com.glance", 31, setOf("certificate"), "code31", BuildConfig.DATA_CONTRACT)
    private val restore = UpdateManifest(1000201, "restore30", "https://host/restore.apk", "a".repeat(64),
        "code30", BuildConfig.DATA_CONTRACT, "restore")
    private val restored = installed.copy(versionCode = restore.versionCode.toLong(), codeIdentity = "code30", kind = "restore")

    @Before fun reset() {
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 7)
        context.getSharedPreferences("glance_restore", 0).edit().clear().commit()
        context.getSharedPreferences("glance_config", 0).edit().clear().commit()
    }

    @Test fun olderCodeThenNewReleaseUsesIncreasingInstallationNumbers() {
        assertNull(UpdateVerification.reject(restore, restored, installed, BuildConfig.DATA_CONTRACT, true))
        val next = restore.copy(versionCode = 1000301, codeIdentity = "code32", kind = "normal")
        assertNull(UpdateVerification.reject(next, restored.copy(versionCode = 1000301, codeIdentity = "code32", kind = "normal"),
            restored, BuildConfig.DATA_CONTRACT, false))
        assertEquals(UpdateDecision.Install, UpdatePolicy.decide(next, restore.versionCode, UpdateAttempts(0, 0), Long.MAX_VALUE))
    }

    @Test fun rejectsRealDowngradeWrongSignaturePackageVersionAndData() {
        for (candidate in listOf(restored.copy(versionCode = 30), restored.copy(certificates = setOf("wrong")),
            restored.copy(packageName = "other"), restored.copy(versionCode = 1000202),
            restored.copy(dataContract = "unknown-migration"), restored.copy(codeIdentity = "code31"))) {
            assertNotNull(UpdateVerification.reject(restore, candidate, installed, BuildConfig.DATA_CONTRACT, true))
        }
        assertNotNull(UpdateVerification.reject(restore, restored, installed, "newer-data-format", true))
        assertNotNull(UpdateVerification.reject(restore, restored, installed, BuildConfig.DATA_CONTRACT, false))
        assertFalse(UpdateVerification.matchesDigest(restore.sha256, "b".repeat(64)))
        assertTrue(UpdateVerification.matchesDigest(restore.sha256, restore.sha256.uppercase()))
    }

    @Test fun recoveryPauseAndResultSurviveProcessRestartWithoutChangingPinOrConfig() {
        val config = AppConfig(context)
        config.setSettingsPin("583902")
        config.dashboardUrls = listOf("https://example.com/dashboard")
        val before = context.getSharedPreferences("glance_config", 0).all.toMap()
        RestoreState(context).begin(restore, "code31 / installation 31")
        val restarted = RestoreState(context)
        assertTrue(restarted.paused)
        restarted.reconcile(restore.versionCode)
        assertEquals(0, restarted.pending)
        assertTrue(restarted.outcome.contains("successfully"))
        assertEquals(before, context.getSharedPreferences("glance_config", 0).all)
        assertTrue(AppConfig(context).verifySettingsPin("583902"))
        // Even a forced ordinary check is blocked until explicit resume.
        config.updateUrl = "http://127.0.0.1:1/should-not-be-contacted"
        config.updateStatus = "unchanged"
        UpdateChecker(context, config).checkNow(force = true, install = true)
        assertEquals("unchanged", config.updateStatus)
        restarted.resume()
        assertFalse(RestoreState(context).paused)
    }

    @Test fun failuresRemainVisibleAndDoNotSilentlyResumeOta() {
        val state = RestoreState(context)
        state.begin(restore, "code31")
        state.failed("invalid signature")
        assertTrue(RestoreState(context).outcome.contains("invalid signature"))
        assertEquals(0, state.pending)
        assertTrue(state.paused)
    }

    @Test fun downloadedWrongHashIsRejectedBeforePackageInstaller() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            val bytes = "corrupted-apk".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val state = RestoreState(context)
            state.offer("""{"versionCode":1000201,"codeIdentity":"code30","dataContract":"${BuildConfig.DATA_CONTRACT}","kind":"restore","url":"http://127.0.0.1:${server.address.port}/apk","sha256":"${restore.sha256}"}""")
            UpdateChecker(context, AppConfig(context), installedVersionCode = 31).restoreNow(restore.versionCode, restore.sha256)
            assertTrue(state.outcome.contains("failed its checksum"))
            assertEquals(0, state.pending)
            assertFalse(state.paused)
            assertFalse(UpdateStorage.stagedApk(context).exists())
            assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test fun androidFailureCallbackIsPersistedAndUnrelatedSessionsAreIgnored() {
        val state = RestoreState(context)
        state.begin(restore, "code31", sessionId = 123)
        val receiver = UpdateInstallReceiver()
        val intent = android.content.Intent(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID)
            .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE, "bad APK")
            .putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, 124)
        receiver.onReceive(context, intent)
        assertEquals(restore.versionCode, state.pending)
        intent.putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, 123)
        receiver.onReceive(context, intent)
        assertEquals(0, state.pending)
        assertTrue(state.outcome.contains("bad APK"))
        assertTrue(state.paused)
    }

    @Test fun crashBeforeCommitDoesNotPermanentlyBlockFutureUpdates() {
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        RestoreState(context).begin(restore, "code31", id)
        val restarted = RestoreState(context)
        restarted.recoverInterruptedSession()
        assertEquals(0, restarted.pending)
        assertNull(installer.getSessionInfo(id))
        assertTrue(restarted.paused)
        restarted.resume()
        assertFalse(restarted.paused)
    }

    @Test fun rebootAbandonsSealedInactiveSessionForRecoveryAndNormalUpdates() {
        for (kind in listOf("restore", "normal")) {
            android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 7)
            val id = sealedSession(active = false)
            RestoreState(context).begin(restore.copy(kind = kind), "code31", id)
            // Device reboot restores a sealed but inactive session, without its commit task.
            android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 8)
            val restarted = RestoreState(context)
            restarted.reconcile(installed = 31)
            restarted.recoverInterruptedSession()
            assertEquals(0, restarted.pending)
            assertNull(context.packageManager.packageInstaller.getSessionInfo(id))
            assertTrue(restarted.paused)
            assertTrue(restarted.outcome.contains("Interrupted installation abandoned"))
            restarted.resume()
            assertFalse(restarted.paused)
        }
    }

    @Test fun processRestartDoesNotAbandonAnInFlightSealedCommit() {
        val id = sealedSession(active = false)
        RestoreState(context).begin(restore, "code31", id)
        val restarted = RestoreState(context)
        restarted.reconcile(installed = 31)
        restarted.recoverInterruptedSession()
        assertEquals(restore.versionCode, restarted.pending)
        assertNotNull(context.packageManager.packageInstaller.getSessionInfo(id))
        assertThrows(IllegalStateException::class.java) { restarted.resume() }
    }

    @Test fun rebootDoesNotAbandonSessionThatAndroidIsActivelyProcessing() {
        val id = sealedSession(active = true)
        RestoreState(context).begin(restore, "code31", id)
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 8)
        RestoreState(context).recoverInterruptedSession()
        assertEquals(restore.versionCode, RestoreState(context).pending)
        assertNotNull(context.packageManager.packageInstaller.getSessionInfo(id))
    }

    @Test fun unknownBootIdentityCanBeCancelledExplicitlyWithStaleRequestProtection() {
        val id = sealedSession(active = false)
        RestoreState(context).begin(restore, "code31", id)
        context.getSharedPreferences("glance_restore", 0).edit().remove("bootCount").commit()
        val restarted = RestoreState(context)
        restarted.recoverInterruptedSession()
        assertEquals(restore.versionCode, restarted.pending)
        val checker = UpdateChecker(context, AppConfig(context), installedVersionCode = 31)
        assertThrows(IllegalStateException::class.java) { checker.cancelPending(restore.versionCode, id + 1) }
        assertEquals(restore.versionCode, restarted.pending)
        checker.cancelPending(restore.versionCode, id)
        assertEquals(0, restarted.pending)
        assertNull(context.packageManager.packageInstaller.getSessionInfo(id))
        assertTrue(restarted.paused)
        checker.resumeUpdates()
        assertFalse(restarted.paused)
    }

    private fun sealedSession(active: Boolean): Int {
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val session = installer.getSessionInfo(id)!!
        session.javaClass.getDeclaredField("sealed").apply { isAccessible = true }.setBoolean(session, true)
        session.javaClass.getDeclaredField("active").apply { isAccessible = true }.setBoolean(session, active)
        return id
    }

    @Test fun laterUpgradeFromLegacyCodeClearsOldPendingStateWithoutPausingUpdates() {
        RestoreState(context).begin(restore.copy(versionCode = 36, kind = "normal"), "installation 35", 123)
        // Legacy code ran as installation 36 but could not consume the recovery bookkeeping.
        val upgraded = RestoreState(context)
        upgraded.reconcile(installed = 1000301)
        upgraded.recoverInterruptedSession()
        assertEquals(0, upgraded.pending)
        assertEquals(-1, upgraded.sessionId)
        assertFalse(upgraded.paused)
        assertTrue(upgraded.outcome.contains("superseded"))
    }

    @Test fun staleConfirmationAndIncompatibleDataNeverStartInstall() {
        val state = RestoreState(context)
        state.offer("""{"versionCode":1000201,"versionName":"30","url":"https://host/a.apk","sha256":"${restore.sha256}","codeIdentity":"code30","kind":"restore","dataContract":"${"b".repeat(64)}"}""")
        val checker = UpdateChecker(context, AppConfig(context), installedVersionCode = 31)
        checker.restoreNow(1000200, restore.sha256)
        assertTrue(state.outcome.contains("stale confirmation"))
        checker.restoreNow(1000201, restore.sha256)
        assertTrue(state.outcome.contains("incompatible data"))
        assertFalse(state.paused)
        assertEquals(0, state.pending)
    }
}
