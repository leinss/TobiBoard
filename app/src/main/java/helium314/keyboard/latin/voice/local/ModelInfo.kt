// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

/**
 * Describes one downloadable on-device model. A model is one or more files fetched from
 * a public HuggingFace `resolve/main/<file>` URL, verified against a pinned SHA-256, and
 * stored under [ModelStorage.dirFor].
 */
internal sealed interface ModelInfo {
    val id: String
    val displayName: String
    val files: List<ModelFile>
    val requiresLicense: Boolean get() = false
    val licenseSummary: String? get() = null
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
     * Sherpa-onnx export of NVIDIA Parakeet TDT 0.6B v2 (English). Four files:
     * encoder / decoder / joiner ONNX + tokens.txt. Hashes and sizes are placeholders —
     * see [REQUIRES_HASH_PINNING] below; the downloader refuses to start until they
     * are filled in.
     */
    data object ParakeetTdt06b : SttModelInfo {
        override val id = "parakeet-tdt-0.6b-v2"
        override val displayName = "Parakeet TDT 0.6 B (English)"
        private const val BASE =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2/resolve/main"
        override val files = listOf(
            ModelFile("encoder.onnx", "$BASE/encoder.onnx", REQUIRES_HASH_PINNING, 0L),
            ModelFile("decoder.onnx", "$BASE/decoder.onnx", REQUIRES_HASH_PINNING, 0L),
            ModelFile("joiner.onnx", "$BASE/joiner.onnx", REQUIRES_HASH_PINNING, 0L),
            ModelFile("tokens.txt", "$BASE/tokens.txt", REQUIRES_HASH_PINNING, 0L),
        )
    }
}

internal sealed interface TextFixModelInfo : ModelInfo {
    /**
     * Google Gemma 3 1B IT, INT4-quantised, in MediaPipe's `.task` bundle format. Single
     * file. Requires the user to acknowledge Gemma Terms before download.
     */
    data object Gemma3_1bInt4 : TextFixModelInfo {
        override val id = "gemma3-1b-it-int4"
        override val displayName = "Gemma 3 1B IT (INT4)"
        override val requiresLicense = true
        override val licenseSummary =
            "Use is governed by the Gemma Terms of Use. The model is downloaded directly " +
            "from HuggingFace; this app does not host it."
        private const val BASE =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main"
        override val files = listOf(
            ModelFile(
                "gemma3-1b-it-int4.task",
                "$BASE/gemma3-1b-it-int4.task",
                REQUIRES_HASH_PINNING,
                0L,
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
