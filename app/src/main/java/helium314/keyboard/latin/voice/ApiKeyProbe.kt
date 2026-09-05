// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import androidx.annotation.StringRes
import helium314.keyboard.latin.R

/** What the "Test API key" probe concluded. Each case has exactly one message. */
enum class ApiKeyProbeOutcome(@param:StringRes val messageResId: Int) {
    OK(R.string.voice_test_key_success),

    /** The key works, and the selected model is known not to have a zero-data-retention endpoint. */
    OK_ZDR_UNAVAILABLE(R.string.voice_test_key_success_zdr_unavailable),

    /**
     * The key works, but whether the model has a zero-data-retention endpoint could not be
     * established. Reported as unverified rather than as verified: the probe used to return
     * "supported" on any exception and on any non-200, so a user with ZDR switched on was told a
     * privacy guarantee held when nothing had checked it.
     */
    OK_ZDR_UNVERIFIED(R.string.voice_test_key_success_zdr_unverified),

    INVALID_KEY(R.string.voice_test_key_invalid),
    INVALID_MODEL(R.string.voice_test_key_invalid_model),

    /** The request never reached the provider: no network, DNS failure, timeout, TLS failure. */
    OFFLINE(R.string.voice_test_key_network_error),

    /** The provider answered with a status that is neither success nor an auth/model rejection. */
    PROVIDER_ERROR(R.string.voice_test_key_provider_error),

    /** The provider answered, but the answer could not be parsed or was refused locally. */
    UNEXPECTED_RESPONSE(R.string.voice_test_key_unexpected),
}

/** Tri-state result of the zero-data-retention endpoint check. */
enum class ZdrSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

/**
 * Maps an HTTP status or a thrown exception to a probe outcome.
 *
 * Every failure used to collapse into "Network error", which hid a rejected key behind a wrong
 * diagnosis and told the user to check their connection when the provider had answered fine.
 *
 * Pure, and unit-tested in `ApiKeyProbeTest`.
 */
object ApiKeyProbe {

    /** Null means "keep going": the status is a success and the caller continues its checks. */
    fun forStatus(code: Int): ApiKeyProbeOutcome? = when (code) {
        200 -> null
        401, 403 -> ApiKeyProbeOutcome.INVALID_KEY
        404 -> ApiKeyProbeOutcome.INVALID_MODEL
        else -> ApiKeyProbeOutcome.PROVIDER_ERROR
    }

    fun forException(e: Throwable): ApiKeyProbeOutcome = when (e) {
        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is javax.net.ssl.SSLException,
        -> ApiKeyProbeOutcome.OFFLINE
        is java.io.IOException -> ApiKeyProbeOutcome.OFFLINE
        else -> ApiKeyProbeOutcome.UNEXPECTED_RESPONSE
    }

    /** Combines a working key with the ZDR check, when the user has ZDR switched on. */
    fun withZdr(zdr: ZdrSupport): ApiKeyProbeOutcome = when (zdr) {
        ZdrSupport.SUPPORTED -> ApiKeyProbeOutcome.OK
        ZdrSupport.UNSUPPORTED -> ApiKeyProbeOutcome.OK_ZDR_UNAVAILABLE
        ZdrSupport.UNKNOWN -> ApiKeyProbeOutcome.OK_ZDR_UNVERIFIED
    }
}
