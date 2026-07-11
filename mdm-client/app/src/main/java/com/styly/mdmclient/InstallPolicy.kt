package com.styly.mdmclient

/**
 * Decides whether a downloaded APK may be handed to the silent installer.
 *
 * EXECUTE_INSTALL carries the SHA-256 of the exact file the server is serving (#39);
 * the download travels over plain LAN HTTP, so this comparison is the only integrity
 * check the bytes ever get. Free of Android imports so it runs under plain JUnit on
 * the host JVM.
 */
object InstallPolicy {

    /**
     * Null means proceed; otherwise a human-readable refusal for INSTALL_RESULT.
     *
     * A normal install without a server-supplied hash proceeds unverified (older
     * servers never send one). A *self*-update without it is refused outright: a bad
     * client build costs remote control of the device and Android offers no rollback,
     * so the client never installs unverified bytes over itself.
     */
    fun hashGateError(
        expectedFullSha256: String?,
        actualFullSha256: String?,
        isSelfUpdate: Boolean,
    ): String? {
        val expected = expectedFullSha256?.trim().orEmpty()
        if (expected.isEmpty()) {
            return if (isSelfUpdate) {
                "Self-update requires APK hashes from the server; update the server first"
            } else {
                null
            }
        }
        val actual = actualFullSha256?.trim().orEmpty()
        if (actual.isEmpty()) {
            return "Could not compute the downloaded APK's hash"
        }
        if (!expected.equals(actual, ignoreCase = true)) {
            return "APK hash mismatch (expected $expected, got $actual)"
        }
        return null
    }
}
