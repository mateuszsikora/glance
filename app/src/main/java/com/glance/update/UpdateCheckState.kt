package com.glance.update

/**
 * What the most recent update check learned, persisted so both settings surfaces can describe the
 * updater without contacting the server themselves.
 *
 * [serverReachable] reports only whether the manifest could be fetched. A manifest that arrives but
 * does not parse still counts as reachable, because that is what separates a network problem from a
 * publishing one — and the two have different fixes.
 *
 * [availableVersionCode] is the version the server last offered, kept even when it installs itself
 * straight away; callers compare it against the running build rather than trusting it to be newer.
 */
data class UpdateCheckState(
    val checkedAt: Long = 0L,
    val serverReachable: Boolean = false,
    val availableVersionCode: Int = 0,
    val availableVersionName: String = ""
) {
    /** True before the first check has run, when nothing is known about the server yet. */
    val neverChecked: Boolean get() = checkedAt <= 0L
}
