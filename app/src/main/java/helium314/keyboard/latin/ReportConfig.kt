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
 * TODO(owner): before any Play **production** release, set [REPORT_EMAIL] to a real, monitored
 * mailbox. This constant is the single source of truth for the AI-output report recipient — the
 * only place to change it. (Note: the inherited HeliBoard gesture-data feature has its own,
 * unrelated submission address, obfuscated in `res/values/gesture_data.xml`; that is a separate
 * upstream channel, not this one.) Keep this on the project's own domain (not a personal address)
 * so it is safe to ship in a public repo.
 */
object ReportConfig {

    /** Placeholder on the project's public domain (leinss.xyz, same host as the F-Droid repo). */
    const val REPORT_EMAIL = "tobiboard-report@leinss.xyz"

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
