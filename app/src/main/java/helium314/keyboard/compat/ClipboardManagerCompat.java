// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.compat;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Build;

public class ClipboardManagerCompat {

    public static void clearPrimaryClip(ClipboardManager cm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                cm.clearPrimaryClip();
            } catch (Exception e) {
                // workaround for system-caused crash in https://github.com/HeliBorg/HeliBoard/issues/203
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""));
        }
    }

    public static Long getClipTimestamp(ClipData cd) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final long timestamp = cd.getDescription().getTimestamp();
            if (timestamp > 0) // timestamp is 0 if not set
                return timestamp;
        }
        return System.currentTimeMillis();
    }

    /**
     * Timestamp of the current primary clip read from its description (metadata) only. Reading the
     * description does NOT trigger the OS clipboard-access notification the way reading the clip
     * content does, so this is safe to poll when the keyboard becomes visible to detect whether the
     * clip changed before paying the cost (and privacy toast) of an actual content read.
     * Returns 0 when unavailable (no clip, or API &lt; 26 where clip timestamps don't exist).
     */
    public static long getPrimaryClipDescriptionTimestamp(ClipboardManager cm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final ClipDescription cd = cm.getPrimaryClipDescription();
            if (cd != null)
                return cd.getTimestamp();
        }
        return 0L;
    }

    public static Boolean getClipSensitivity(final ClipDescription cd) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return cd != null && cd.getExtras() != null && cd.getExtras().getBoolean("android.content.extra.IS_SENSITIVE");
        }
        return null; // can't determine
    }
}
