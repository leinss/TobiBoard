// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.local

import android.content.Context
import android.os.StatFs
import java.io.File

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
