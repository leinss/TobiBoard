// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.MessageDigest

/**
 * On-disk layout for downloaded models. Models live under `noBackupFilesDir/models/<id>/`
 * — large, redownloadable, not user data, so deliberately excluded from Auto Backup.
 *
 *   noBackupFilesDir/
 *     models/
 *       parakeet-tdt-0.6b-v2/
 *         encoder.onnx
 *         decoder.onnx
 *         joiner.onnx
 *         tokens.txt
 *         encoder.onnx.part   ← present only mid-download; resume-target
 */
internal object ModelStorage {

    private const val ROOT = "models"
    const val PART_SUFFIX = ".part"

    fun rootDir(context: Context): File = File(context.noBackupFilesDir, ROOT)

    fun dirFor(context: Context, model: ModelInfo): File =
        File(rootDir(context), model.id).also { it.mkdirs() }

    fun fileFor(context: Context, model: ModelInfo, file: ModelFile): File =
        File(dirFor(context, model), file.relativePath)

    fun partFileFor(context: Context, model: ModelInfo, file: ModelFile): File =
        File(dirFor(context, model), file.relativePath + PART_SUFFIX)

    /** All required files present (does not re-verify SHA — that only happens at download time). */
    fun isReady(context: Context, model: ModelInfo): Boolean =
        model.files.all { fileFor(context, model, it).isFile && fileFor(context, model, it).length() > 0 }

    /**
     * Deep integrity check: returns the relative paths of every required file that is missing,
     * the wrong size, or whose SHA-256 doesn't match the pinned digest — empty when the model is
     * fully intact. Unlike [isReady] this hashes the whole model (hundreds of MB), so it is slow;
     * call it only when a model that passed [isReady] nevertheless fails to load, never on the
     * hot path. Size is compared first so a truncated download is rejected without hashing.
     */
    fun findCorruptFiles(context: Context, model: ModelInfo): List<String> =
        model.files
            .filterNot { isFileIntact(fileFor(context, model, it), it.sha256, it.sizeBytes) }
            .map { it.relativePath }

    /** True when [file] exists, is exactly [expectedSize] bytes, and hashes to [expectedSha]. */
    internal fun isFileIntact(file: File, expectedSha: String, expectedSize: Long): Boolean =
        file.isFile && file.length() == expectedSize && sha256(file).equals(expectedSha, ignoreCase = true)

    /** Streaming SHA-256 of [file], as lower-case hex. Matches the digest the downloader pins. */
    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete(context: Context, model: ModelInfo) {
        dirFor(context, model).deleteRecursively()
    }

    /**
     * Free space currently available on the volume holding [rootDir], for pre-flight
     * checks. Returns 0 on any I/O error so the caller treats unknown-state as "no
     * room" and surfaces a warning rather than letting a multi-hundred-MB download fail
     * mid-stream.
     */
    fun availableBytes(context: Context): Long = try {
        rootDir(context).mkdirs()
        StatFs(rootDir(context).absolutePath).availableBytes
    } catch (_: Exception) {
        0L
    }
}
