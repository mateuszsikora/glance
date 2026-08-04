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

    fun decide(
        manifest: UpdateManifest,
        installedVersionCode: Int,
        attempts: UpdateAttempts,
        uptimeMs: Long
    ): UpdateDecision {
        if (manifest.versionCode <= installedVersionCode) return UpdateDecision.UpToDate
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
