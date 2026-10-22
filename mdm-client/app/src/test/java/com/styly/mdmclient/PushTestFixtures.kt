package com.styly.mdmclient

import java.util.UUID

internal fun pushTestCommand(
    jobId: String? = UUID.randomUUID().toString(),
    attempt: Int = PushProtocol.ATTEMPT_V1,
    artifactId: String? = UUID.randomUUID().toString(),
    artifactUrl: String = "http://server/artifact.zip",
    artifactSize: Long? = 42L,
    artifactSha256: String? = "a".repeat(64),
    bundleFilename: String = "bundle.zip",
    destPath: String = "/sdcard/STYLY/content",
    deleteExtras: Boolean = false,
    revision: Long = 0L,
    artifactEtag: String? = null,
) = PushProtocol.Command(
    jobId = jobId,
    attempt = attempt,
    artifactId = artifactId,
    artifactUrl = artifactUrl,
    artifactSize = artifactSize,
    artifactSha256 = artifactSha256,
    bundleFilename = bundleFilename,
    destPath = destPath,
    deleteExtras = deleteExtras,
    revision = revision,
    artifactEtag = artifactEtag,
)
