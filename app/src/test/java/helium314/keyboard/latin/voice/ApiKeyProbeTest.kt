// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every probe failure used to be reported as "Network error", so a rejected key, an unparseable
 * response and a provider outage all read as "check your connection". The ZDR branch additionally
 * failed open: any exception and any non-200 answered "supported", telling the user a privacy
 * guarantee held when nothing had checked it.
 */
class ApiKeyProbeTest {

    @Test
    fun successKeepsGoing() {
        assertNull(ApiKeyProbe.forStatus(200))
    }

    @Test
    fun authFailuresAreNotNetworkFailures() {
        assertEquals(ApiKeyProbeOutcome.INVALID_KEY, ApiKeyProbe.forStatus(401))
        assertEquals(ApiKeyProbeOutcome.INVALID_KEY, ApiKeyProbe.forStatus(403))
    }

    @Test
    fun unknownModelIsDistinctFromABadKey() {
        assertEquals(ApiKeyProbeOutcome.INVALID_MODEL, ApiKeyProbe.forStatus(404))
    }

    @Test
    fun otherStatusesBlameTheProviderNotTheConnection() {
        assertEquals(ApiKeyProbeOutcome.PROVIDER_ERROR, ApiKeyProbe.forStatus(500))
        assertEquals(ApiKeyProbeOutcome.PROVIDER_ERROR, ApiKeyProbe.forStatus(429))
    }

    @Test
    fun transportFailuresAreOffline() {
        assertEquals(ApiKeyProbeOutcome.OFFLINE, ApiKeyProbe.forException(java.net.UnknownHostException("openrouter.ai")))
        assertEquals(ApiKeyProbeOutcome.OFFLINE, ApiKeyProbe.forException(java.net.SocketTimeoutException()))
        assertEquals(ApiKeyProbeOutcome.OFFLINE, ApiKeyProbe.forException(java.io.IOException("boom")))
    }

    @Test
    fun aParseFailureIsNotAConnectionProblem() {
        assertEquals(ApiKeyProbeOutcome.UNEXPECTED_RESPONSE, ApiKeyProbe.forException(org.json.JSONException("bad")))
        assertEquals(ApiKeyProbeOutcome.UNEXPECTED_RESPONSE, ApiKeyProbe.forException(IllegalArgumentException("Probe response too large")))
        assertEquals(ApiKeyProbeOutcome.UNEXPECTED_RESPONSE, ApiKeyProbe.forException(SecurityException()))
    }

    @Test
    fun anUnverifiableZdrCheckIsReportedAsUnverifiedNotAsSupported() {
        assertEquals(ApiKeyProbeOutcome.OK_ZDR_UNVERIFIED, ApiKeyProbe.withZdr(ZdrSupport.UNKNOWN))
        assertEquals(ApiKeyProbeOutcome.OK, ApiKeyProbe.withZdr(ZdrSupport.SUPPORTED))
        assertEquals(ApiKeyProbeOutcome.OK_ZDR_UNAVAILABLE, ApiKeyProbe.withZdr(ZdrSupport.UNSUPPORTED))
    }
}
