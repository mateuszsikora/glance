package com.glance.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the outcome of an update session.
 *
 * A successful self-update kills this process before the status arrives, so in practice this
 * receiver reports failures. Attempts are counted before the session is committed (see
 * [UpdateChecker]) precisely because success is not observable from here.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Update installed")
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                Log.w(
                    TAG,
                    "Installation needs user confirmation, which a kiosk cannot show. " +
                        "Silent updates require Glance to be Device Owner."
                )
            else ->
                Log.w(TAG, "Update installation failed (status=$status): $message")
        }

        UpdateStorage.clearStagedApk(context)
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.glance.ACTION_INSTALL_STATUS"
    }
}
