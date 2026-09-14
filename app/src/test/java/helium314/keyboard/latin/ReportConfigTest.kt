// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ReportConfigTest {

    private val context get() = ApplicationProvider.getApplicationContext<App>()

    @Test
    fun reportIntentIsASendToMailToTheConfiguredAddress() {
        val intent = ReportConfig.reportIntent(context, "some output")
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        // mailto: is an opaque URI; the recipient is the scheme-specific part up to the query.
        val recipient = intent.data?.schemeSpecificPart?.substringBefore("?")
        assertEquals(ReportConfig.REPORT_EMAIL, recipient)
    }

    @Test
    fun reportIntentBodyQuotesTheAiOutputVerbatim() {
        val aiOutput = "objectionable AI sentence 😀 with emoji"
        val intent = ReportConfig.reportIntent(context, aiOutput)
        val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(body.contains(aiOutput), "report body must contain the AI output verbatim")
        assertTrue(
            body.contains(context.getString(R.string.report_ai_body_prefix)),
            "report body must contain the explanatory prefix",
        )
        assertEquals(context.getString(R.string.report_ai_subject), intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun reportIntentCarriesSubjectAndBodyInTheMailtoQueryString() {
        // ACTION_SENDTO clients (notably Gmail) read subject/body from the mailto: query string and
        // ignore EXTRA_SUBJECT/EXTRA_TEXT — so the query string, not just the extras, must carry them.
        // mailto: is opaque, so getQueryParameter() returns null; assert on the scheme-specific part.
        val aiOutput = "bad output with spaces & ampersand"
        val intent = ReportConfig.reportIntent(context, aiOutput)
        val ssp = intent.data?.schemeSpecificPart.orEmpty()
        assertTrue(ssp.contains("?subject="), "mailto must carry the subject in its query string")
        assertTrue(ssp.contains("&body="), "mailto must carry the body in its query string")
        // Decoding the whole query string back must reveal the verbatim AI output and the prefix.
        val decoded = android.net.Uri.decode(ssp)
        assertTrue(decoded.contains(aiOutput), "decoded mailto body must contain the AI output verbatim")
        assertTrue(
            decoded.contains(context.getString(R.string.report_ai_body_prefix)),
            "decoded mailto body must contain the explanatory prefix",
        )
    }

    @Test
    fun reportIntentIsLaunchableFromANonActivityContext() {
        // Launched from the IME (a Service context), so it must carry NEW_TASK or startActivity throws.
        val intent = ReportConfig.reportIntent(context, "x")
        assertTrue(
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
            "report intent must set FLAG_ACTIVITY_NEW_TASK to launch from the IME context",
        )
    }
}
