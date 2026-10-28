package com.lyentech.nano.http

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal object LiteRtLmModelStore {
    /**
     * The model to fetch; any tool-capable `.litertlm` model works. Repos behind a license need a
     * token this sample does not implement, so push those with `adb` instead.
     */
    private const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    private const val FILE_NAME = "gemma-4-E2B-it.litertlm"
    private const val REVISION = "main"

    /** Approximate download size, so the prompt can say what it is about to cost. */
    const val DOWNLOAD_SIZE_LABEL: String = "2.5 GB"

    private const val EXTENSION = ".litertlm"
    private const val PARTIAL_SUFFIX = ".part"

    /** Emit at most this many progress updates, rather than one per buffer. */
    private const val PROGRESS_STEPS = 200

    private const val TIMEOUT_MILLIS = 30_000

    /**
     * The model to run, or null if none has been downloaded or pushed yet. A model you supplied
     * yourself wins over the downloaded one, so pushing a file overrides it without having to delete
     * anything; between several of your own, the first by name wins.
     */
    fun find(context: Context): File? {
        val candidates =
            directory(context)
                .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
                .orEmpty()
                .sortedBy { it.name }
        return candidates.firstOrNull { it.name != FILE_NAME } ?: candidates.firstOrNull()
    }

    /**
     * `.litertlm` files sitting in [directory], newest first, for the model picker.
     *
     * Why a separate entry point instead of reusing [find]: this one keeps the downloaded gemma file
     * in the list. [find] exists to answer "which single model should the sample screen run", and its
     * preference order is a product decision for that screen; a picker that silently hid a file the
     * user just paid 2.5 GB of bandwidth for would look broken.
     *
     * An empty directory (the common first-run case) lists nothing rather than throwing: the caller
     * shows "no model here yet" plus the import directory to push into.
     */
    fun listModels(directory: File): List<File> =
        directory
            .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    /** The default import directory: the app's own external files dir, the `adb push` target. */
    fun importDirectory(context: Context): File = directory(context)

    /** [importDirectory] as a string, for the copyable path hint shown next to the import button. */
    fun importDirectoryPath(context: Context): String = importDirectory(context).absolutePath

    /**
     * Files larger than this are treated as "someone else's model, do not move it". The threshold
     * exists so [importFrom] does not silently relocate a big file we only wanted to reference.
     */
    private const val MOVE_SIZE_LIMIT: Long = 1L shl 30

    /**
     * Puts the picked file where the runner can open it by path.
     *
     * Why this is not always a copy: a media-store pick (Downloads/Movies) hands back a readable fd
     * but no path, and `EngineConfig.modelPath` needs a real path, so those must be copied. A SAF
     * pick can point at a >1 GB model with a usable path
     * (`content://com.android.externalstorage.documents`), and copying 1.5 GB would be pointless —
     * those are moved. When neither holds, we copy anyway and the caller reports the copy's path, so
     * the worst case is a slow import, never a broken one.
     */
    fun importFrom(context: Context, source: Uri): File {
        val destination = directory(context)
        destination.mkdirs()
        val name = displayName(context, source).ifBlank { "imported$EXTENSION" }
        val target = File(destination, name)

        // 同名文件按「已经导入过」处理：目标非空就不再覆盖，避免把上次可用的模型改成半截文件。
        if (target.exists() && target.length() > 0) return target

        val sourcePath = source.documentFilePath()
        if (sourcePath != null && File(sourcePath).length() > MOVE_SIZE_LIMIT) {
            // renameTo 只在同一个挂载点内成功；外部存储根与 /data/data 属于不同挂载点，此时返回 false，
            // 下面会退回复制，所以这里不能用 check 直接抛。
            if (File(sourcePath).renameTo(target)) return target
        }
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选文件：$source")
        return target
    }

    private fun displayName(context: Context, source: Uri): String {
        val cursor =
            runCatching {
                context.contentResolver.query(
                    source,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
            }.getOrNull()
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) return it.getString(index).orEmpty()
        }
        return source.lastPathSegment.orEmpty().substringAfterLast('/')
    }

    /**
     * The filesystem path behind a SAF uri, or null when the provider does not expose one.
     *
     * 只有 externalstorage / downloads provider 的 uri 才能这样还原：它们的 path 段就是绝对路径。
     * 其他 provider（例如 Downloads 的媒体库 uri）拿不到路径，返回 null 交给调用方复制。
     */
    private fun Uri.documentFilePath(): String? {
        if (scheme != "content") return path
        if (authority?.endsWith("documents") != true) return null
        // 形如 primary:Download/model.litertlm，冒号前是卷名，后是卷内相对路径；没有冒号表示拿不到。
        val docId = pathSegments.getOrNull(1) ?: return null
        val relative = docId.substringAfter(':', "")
        if (relative.isEmpty()) return null
        return "/storage/emulated/0/$relative"
    }

    /**
     * Downloads [FILE_NAME], emitting the fraction completed. The bytes go to a temporary file that
     * is renamed only once the transfer finishes, so an interrupted download is never mistaken for a
     * usable model; leaving the screen cancels it, and retrying starts over.
     */
    fun download(context: Context): Flow<Float> =
        flow {
            emit(0f)
            val partial = File(directory(context), FILE_NAME + PARTIAL_SUFFIX)
            try {

                val connection = URL(downloadUrl().also { Log.v("TAG",it) }).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                try {
                    // Without this an error page would be written out as if it were the model.
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "Model download failed with HTTP ${connection.responseCode}."
                    }
                    val total = connection.contentLengthLong
                    val step = if (total > 0) total / PROGRESS_STEPS else Long.MAX_VALUE
                    var copied = 0L
                    var reported = 0L
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (copied - reported >= step) {
                                    reported = copied
                                    emit(copied.toFloat() / total)
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                check(partial.renameTo(File(directory(context), FILE_NAME))) {
                    "Downloaded model could not be moved into place."
                }
            } catch (t: Throwable) {
                // Gigabytes of a half-finished model are not worth keeping for a retry that restarts.
                val unused = partial.delete()
                throw t
            }
            emit(1f)
        }
            .flowOn(Dispatchers.IO)

    /** The `adb push` destination, for the hint shown alongside the download prompt. */
    fun pushDirectory(context: Context): String = importDirectoryPath(context)

    private fun directory(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    private fun downloadUrl(): String = "https://huggingface.co/$REPO/resolve/$REVISION/$FILE_NAME"
}