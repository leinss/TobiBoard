// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Single source of truth for the app's AI-output report channel.
 *
 * Google Play's Generative AI policy requires that users can report objectionable AI-generated
 * content. The in-keyboard "Report" actions (text-fix result overlay and the post-insertion undo
 * bar) all route through here, so the destination address lives in exactly one place.
 *
 * [REPORT_EMAIL] is the single source of truth for the AI-output report recipient — the only place
 * to change it. It points at the project's monitored contact inbox (the same address as the public
 * privacy policy). The inherited gesture-data feature now routes to the same inbox, but via its own
 * obfuscated resource string in `res/values/gesture_data.xml` — keep the two in sync if it changes.
 * Keep this on the project's own domain (not a personal address) so it is safe to ship in a public repo.
 */
object ReportConfig {

    /** Project contact inbox on leinss.xyz (same host as the F-Droid repo and the privacy policy). */
    const val REPORT_EMAIL = "inquiry@leinss.xyz"

    /**
     * Build a pre-filled mail intent for reporting a piece of AI output. The user reviews and sends
     * it from their own mail app — that review step is the consent to share the quoted content.
     */
    @JvmStatic
    fun reportIntent(context: Context, aiOutput: String): Intent {
        val subject = context.getString(R.string.report_ai_subject)
        val body = context.getString(R.string.report_ai_body_prefix) + "\n\n" + aiOutput
        // Subject/body go in the mailto: query string: ACTION_SENDTO clients such as Gmail read
        // those but ignore EXTRA_SUBJECT/EXTRA_TEXT, so without this the report body arrives empty.
        // The extras are kept as a fallback for clients that prefer them.
        val uri = Uri.parse(
            "mailto:$REPORT_EMAIL?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body)
        )
        return Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
