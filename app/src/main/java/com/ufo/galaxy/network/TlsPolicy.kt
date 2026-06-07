package com.ufo.galaxy.network

import com.ufo.galaxy.BuildConfig

/**
 * Resolves whether self-signed TLS certificates may be accepted in the current build.
 *
 * Production builds must never disable certificate validation, even if a persisted
 * preference or caller requests it.
 */
internal fun resolveAllowSelfSigned(
    requested: Boolean,
    isDebugBuild: Boolean = BuildConfig.DEBUG
): Boolean = requested && isDebugBuild
