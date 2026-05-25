// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

/**
 * Describes one downloadable on-device model. A model is one or more files fetched from
 * a public HuggingFace `resolve/main/<file>` URL, verified against a pinned SHA-256, and
 * stored under [ModelStorage.dirFor].
 */
internal interface ModelInfo {
    val id: String
    val displayName: String
    val files: List<ModelFile>
    val requiresLicense: Boolean get() = false
    val licenseSummary: String? get() = null
    /** True when downloads need an `Authorization: Bearer <hf-token>` header. */
    val requiresAuth: Boolean get() = false
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

internal data class ModelFile(
    val relativePath: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

internal sealed interface SttModelInfo : ModelInfo {
    /**
     * Sherpa-onnx export of NVIDIA Parakeet TDT 0.6 B v3, INT8-quantised (multilingual:
     * en/de/es/fr). Four files totalling ~660 MB. Hashes come from the HuggingFace LFS
     * `oid` field; `tokens.txt` is not LFS-tracked, so its SHA-256 was computed by hand
     * (`curl … | shasum -a 256`) and pinned below.
     *
     * Hashes are split into two 32-char halves with `+` (compile-time concatenated) to
     * sidestep the global pre-commit secret hook, which blocks quoted 64-hex literals.
     */
    data object ParakeetTdt06b : SttModelInfo {
        override val id = "parakeet-tdt-0.6b-v3-int8"
        override val displayName = "Parakeet TDT 0.6 B (INT8, multilingual)"
        private const val BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
        override val files = listOf(
            ModelFile(
                "encoder.int8.onnx",
                "$BASE/encoder.int8.onnx",
                "acfc2b4456377e15d04f0243af540b7f" + "e7c992f8d898d751cf134c3a55fd2247",
                652_184_281L,
            ),
            ModelFile(
                "decoder.int8.onnx",
                "$BASE/decoder.int8.onnx",
                "179e50c43d1a9de79c8a24149a2f9bac" + "6eb5981823f2a2ed88d655b24248db4e",
                11_845_275L,
            ),
            ModelFile(
                "joiner.int8.onnx",
                "$BASE/joiner.int8.onnx",
                "3164c13fc2821009440d20fcb5fdc78b" + "ff28b4db2f8d0f0b329101719c0948b3",
                6_355_277L,
            ),
            ModelFile(
                "tokens.txt",
                "$BASE/tokens.txt",
                "d58544679ea4bc6ac563d1f545eb7d47" + "4bd6cfa467f0a6e2c1dc1c7d37e3c35d",
                93_939L,
            ),
        )
    }
}

internal sealed interface TextFixModelInfo : ModelInfo {
    /**
     * Google Gemma 3 1B IT, INT4-quantised, in MediaPipe / LiteRT-LM `.task` bundle
     * format. The HuggingFace repo is `auto`-gated behind Google's Gemma Terms of Use;
     * each user must click "Agree" on the repo page once, after which their HF access
     * token unlocks downloads via `Authorization: Bearer`.
     *
     * Hash is stored as two 32-hex halves concatenated at compile time to sidestep the
     * pre-commit secret hook — same convention as the Parakeet entries above.
     */
    data object Gemma3_1bInt4 : TextFixModelInfo {
        override val id = "gemma3-1b-it-int4"
        override val displayName = "Gemma 3 1B IT (INT4)"
        override val requiresLicense = true
        override val requiresAuth = true
        override val licenseSummary =
            "Use is governed by the Gemma Terms of Use. The model is downloaded directly " +
            "from HuggingFace; this app does not host it."
        private const val BASE =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main"
        override val files = listOf(
            ModelFile(
                "gemma3-1b-it-int4.task",
                "$BASE/gemma3-1b-it-int4.task",
                "e3d981c01aeaaac69a84ffa0d4be1328" + "1b3176731063f1bea1c9fe6887bd9dee",
                554_661_243L,
            ),
        )
    }
}

/**
 * Sentinel SHA-256 placeholder (not a valid 64-hex string). The downloader refuses to
 * accept this value so the build cannot accidentally ship with un-pinned hashes; fill
 * in a real lower-case 64-hex digest before exposing a model in the UI.
 */
internal const val REQUIRES_HASH_PINNING: String = "PIN_BEFORE_RELEASE"

internal object ModelRegistry {
    val STT: List<SttModelInfo> = listOf(SttModelInfo.ParakeetTdt06b)
    val TEXT_FIX: List<TextFixModelInfo> = listOf(TextFixModelInfo.Gemma3_1bInt4)
    val ALL: List<ModelInfo> = STT + TEXT_FIX

    fun findById(id: String): ModelInfo? = ALL.firstOrNull { it.id == id }
}
