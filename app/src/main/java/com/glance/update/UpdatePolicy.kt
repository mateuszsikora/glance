package com.glance.update

/** Why an available manifest did or did not lead to an installation attempt. */
sealed class UpdateDecision {
    object Install : UpdateDecision()
    object UpToDate : UpdateDecision()

    /**
     * The running build has not been alive long enough to be considered healthy. Android cannot
     * downgrade a package, so a broken update can never be rolled back automatically; refusing to
     * chain another install while the current one keeps restarting at least stops the churn and
     * leaves the tablet reachable through the remote configuration panel.
     */
    object NotSettled : UpdateDecision()

    /** The same versionCode failed to install repeatedly; stop retrying until a newer one appears. */
    object Abandoned : UpdateDecision()

    /**
     * A newer build exists but this check may not install it, because automatic updates are off.
     * It is recorded and offered in settings so an operator can install it deliberately.
     */
    object Available : UpdateDecision()
}

/** State persisted between update checks, so failures survive a restart. */
data class UpdateAttempts(
    val versionCode: Int,
    val count: Int
)

object UpdatePolicy {

    /** Consecutive failures for one versionCode before it is abandoned. */
    const val MAX_ATTEMPTS = 3

    /** How long the running build must stay up before it may install a replacement. */
    const val MIN_UPTIME_MS = 15 * 60 * 1000L

    /**
     * [install] is the automatic-update switch: false turns this into a check that only reports
     * what is on offer. It is decided before [force], so pressing "check for updates" with the
     * switch off stays a check — installing is a separate button.
     *
     * [force] marks an installation an operator asked for explicitly. Both remaining guards exist
     * to stop an unattended tablet from churning on its own, so a human standing at the settings
     * page overrides them: the crash-loop guard has nothing to protect against when someone is
     * watching, and an abandoned version is exactly what a manual retry is for.
     */
    fun decide(
        manifest: UpdateManifest,
        installedVersionCode: Int,
        attempts: UpdateAttempts,
        uptimeMs: Long,
        install: Boolean = true,
        force: Boolean = false
    ): UpdateDecision {
        if (manifest.versionCode <= installedVersionCode) return UpdateDecision.UpToDate
        if (!install) return UpdateDecision.Available
        if (force) return UpdateDecision.Install
        if (attempts.versionCode == manifest.versionCode && attempts.count >= MAX_ATTEMPTS) {
            return UpdateDecision.Abandoned
        }
        if (uptimeMs < MIN_UPTIME_MS) return UpdateDecision.NotSettled
        return UpdateDecision.Install
    }

    /** Records one more failure, restarting the count when a different version is offered. */
    fun recordFailure(previous: UpdateAttempts, versionCode: Int): UpdateAttempts {
        val count = if (previous.versionCode == versionCode) previous.count + 1 else 1
        return UpdateAttempts(versionCode, count)
    }
}
