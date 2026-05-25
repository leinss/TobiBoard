// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

/**
 * Snapshot of where a model is in the download pipeline. Emitted as a stream by
 * [ModelDownloader] and surfaced verbatim to the UI.
 */
internal sealed interface DownloadState {
    /** No record on disk and no active job. */
    data object NotDownloaded : DownloadState

    /** Queued but no bytes transferred yet. */
    data object Queued : DownloadState

    /** Bytes are arriving. [bytesTotal] may be 0 if Content-Length is unknown. */
    data class Downloading(
        val bytesDownloaded: Long,
        val bytesTotal: Long,
        val currentFile: String,
        val fileIndex: Int,
        val fileCount: Int,
    ) : DownloadState

    /** All bytes are on disk; computing SHA-256. */
    data class Verifying(val currentFile: String) : DownloadState

    /** All files present and verified. */
    data object Ready : DownloadState

    /** Stopped by the user. Any `.part` file is retained for resume. */
    data object Cancelled : DownloadState

    /** Unrecoverable error. The user must retry. */
    data class Failed(val reason: String) : DownloadState
}
