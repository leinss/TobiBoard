// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.settings.Defaults

enum class AiProvider(val prefValue: String) {
    OPENROUTER("openrouter"),
    PAYPERQ("payperq");

    companion object {
        fun fromPref(value: String?): AiProvider =
            values().firstOrNull { it.prefValue == value } ?: OPENROUTER
    }
}

internal fun AiProvider.apiKeyPrefKey(): String = when (this) {
    AiProvider.OPENROUTER -> helium314.keyboard.latin.settings.Settings.PREF_OPENROUTER_API_KEY
    AiProvider.PAYPERQ -> helium314.keyboard.latin.settings.Settings.PREF_PAYPERQ_API_KEY
}

internal fun AiProvider.defaultApiKey(): String = when (this) {
    AiProvider.OPENROUTER -> Defaults.PREF_OPENROUTER_API_KEY
    AiProvider.PAYPERQ -> Defaults.PREF_PAYPERQ_API_KEY
}

internal fun resolveProviderModel(selectedModel: String, customModel: String): String? =
    resolveVoiceModel(selectedModel, customModel)

internal const val MODEL_CUSTOM = "custom"

/**
 * Slugs (other than [MODEL_CUSTOM]) that each picker offers, derived from [ModelCatalog].
 * Used to decide whether the user's saved selection is still valid after switching provider
 * — if it is, we leave it alone instead of silently overwriting a deliberate choice.
 *
 * Note: OpenRouter "latest" floating aliases are catalogued literally as `~author/model-latest`
 * (e.g. `~google/gemini-flash-latest`). The leading tilde is part of the slug — requests using
 * the bare form return 400 "not a valid model ID". Pinned slugs use the bare form.
 */
private val OPENROUTER_VOICE_SLUGS = ModelCatalog.OPENROUTER_VOICE.mapTo(mutableSetOf()) { it.slug }
private val OPENROUTER_STT_SLUGS = ModelCatalog.OPENROUTER_STT.mapTo(mutableSetOf()) { it.slug }
private val OPENROUTER_TEXT_FIX_SLUGS = ModelCatalog.OPENROUTER_TEXT_FIX.mapTo(mutableSetOf()) { it.slug }

internal fun AiProvider.supportsVoiceSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return when (this) {
        AiProvider.OPENROUTER -> slug in OPENROUTER_VOICE_SLUGS
        // PayPerQ has no bundled catalog — only Custom Model ID is accepted.
        AiProvider.PAYPERQ -> false
    }
}

internal fun supportsOpenRouterSttSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in OPENROUTER_STT_SLUGS
}

internal fun AiProvider.supportsTextFixSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return when (this) {
        AiProvider.OPENROUTER -> slug in OPENROUTER_TEXT_FIX_SLUGS
        AiProvider.PAYPERQ -> false
    }
}

/**
 * Default voice/text-fix model slug to use when switching to [provider]. OpenRouter has
 * a bundled catalog so we use the global default; PayPerQ ships no catalog, so the
 * picker resolves to "Custom" and the user is prompted to enter their own slug.
 */
internal fun AiProvider.defaultModelSlug(globalDefault: String): String = when (this) {
    AiProvider.OPENROUTER -> globalDefault
    AiProvider.PAYPERQ -> MODEL_CUSTOM
}
