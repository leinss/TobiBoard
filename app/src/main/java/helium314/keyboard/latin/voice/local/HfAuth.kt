// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.Context
import helium314.keyboard.latin.voice.SecretStore

/**
 * Hugging Face access token storage. Backed by [SecretStore] (AndroidKeyStore-encrypted
 * SharedPreferences), the same store used for OpenRouter API keys. Some HF model repos
 * are gated ("auto" or "manual") and require an `Authorization: Bearer <token>` header
 * even though the file URL is public — see [ModelInfo.requiresAuth].
 */
internal object HfAuth {
    const val PREF_KEY = "pref_hf_access_token"

    /** Returns the stored token, or null when blank/missing. */
    fun currentToken(context: Context): String? =
        SecretStore.getApiKey(context, PREF_KEY, "").trim().ifBlank { null }

    fun setToken(context: Context, value: String) {
        SecretStore.setApiKey(context, PREF_KEY, value.trim())
    }

    fun clear(context: Context) {
        SecretStore.setApiKey(context, PREF_KEY, "")
    }
}
