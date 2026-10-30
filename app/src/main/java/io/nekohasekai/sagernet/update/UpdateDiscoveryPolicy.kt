package app.qthrone.mobile.update

/** Foreground release discovery: avoid repeated requests, but retry a temporary network failure soon. */
object UpdateDiscoveryPolicy {
    // One interval everywhere: the open app and the background worker both look every 15 minutes.
    const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
    const val RETRY_INTERVAL_MS = 5 * 60 * 1000L

    fun shouldCheck(now: Long, lastAttempt: Long, lastSucceeded: Boolean): Boolean =
        lastAttempt <= 0 || now < lastAttempt ||
            now - lastAttempt >= if (lastSucceeded) CHECK_INTERVAL_MS else RETRY_INTERVAL_MS

    fun acceptsOffer(
        versionCode: Long,
        versionName: String,
        prerelease: Boolean,
        installedCode: Long,
        installedVersion: String,
        allowBeta: Boolean,
        skippedCode: Long,
    ): Boolean {
        if (prerelease && !allowBeta) return false
        return if (versionCode > 0) versionCode > installedCode && versionCode != skippedCode
        else Semver.compare(versionName, installedVersion)?.let { it > 0 } == true
    }
}
