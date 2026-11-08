package com.liquid.lfmomni.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Hugging Face 上の配布ファイル (LiquidAI/LFM3-Omni-2B-GGUF) */
object ModelCatalog {
    const val REPO = "LiquidAI/LFM3-Omni-2B-GGUF"
    const val BASE_URL = "https://huggingface.co/$REPO/resolve/main"
    const val MODEL_FILE = "LFM3-Omni-2B-Q4_K_M.gguf"
    const val MMPROJ_FILE = "mmproj-LFM3-Omni-2B-Q4_K_M.gguf"

    /** 参考サイズ (進捗表示用。実際の値はレスポンスヘッダから取る) */
    const val MODEL_SIZE_HINT = 1_457_000_000L
    const val MMPROJ_SIZE_HINT = 900_000_000L
}

/** ダウンロード進捗 */
data class DownloadProgress(
    val fileName: String,
    val bytes: Long,
    val total: Long,
) {
    val fraction: Float
        get() = if (total > 0) (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
}

class ModelRepository(context: Context) {

    private val appContext = context.applicationContext
    val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun modelFile(): File = File(modelsDir, ModelCatalog.MODEL_FILE)
    fun mmprojFile(): File = File(modelsDir, ModelCatalog.MMPROJ_FILE)

    fun exists(fileName: String): Boolean = File(modelsDir, fileName).let { it.exists() && it.length() > 0 }

    fun isReady(): Boolean = exists(ModelCatalog.MODEL_FILE) && exists(ModelCatalog.MMPROJ_FILE)

    fun installedFiles(): List<File> =
        modelsDir.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name } ?: emptyList()

    fun delete(fileName: String): Boolean = File(modelsDir, fileName).delete()

    /**
     * Hugging Face からファイルを取得する。途中まで取得済みなら Range で再開する。
     * 戻り値は保存先 File。
     */
    suspend fun download(
        fileName: String,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = File(modelsDir, fileName)
        val part = File(modelsDir, "$fileName.part")
        if (target.exists() && target.length() > 0) {
            return@withContext target
        }

        var existing = if (part.exists()) part.length() else 0L
        if (existing > 0 && existing > 5L * 1024 * 1024) {
            // 途中再開。サーバーが Range 非対応なら作り直す
        } else if (part.exists()) {
            part.delete()
            existing = 0
        }

        val builder = Request.Builder()
            .url("${ModelCatalog.BASE_URL}/$fileName")
            .header("User-Agent", "LFM3-Omni-Local-Android/0.1")
        if (existing > 0) {
            builder.header("Range", "bytes=$existing-")
        }

        val response = http.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }

        val body = response.body ?: throw IOException("empty body")
        val contentLength = body.contentLength()
        val total = if (response.code == 206) existing + contentLength else contentLength
        val append = response.code == 206

        RandomAccessFile(part, "rw").use { raf ->
            if (append) raf.seek(existing) else {
                raf.setLength(0)
                existing = 0
            }
            body.byteStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                var written = existing
                var lastReport = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    raf.write(buffer, 0, read)
                    written += read
                    if (written - lastReport > 512 * 1024) {
                        lastReport = written
                        onProgress(DownloadProgress(fileName, written, total))
                    }
                }
                onProgress(DownloadProgress(fileName, written, total))
            }
        }
        response.close()

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("rename failed")
        target
    }

    /** SAF / ファイルピッカーから選んだファイルを models ディレクトリへコピーする */
    suspend fun importFrom(uri: Uri, targetName: String, onProgress: (Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val target = File(modelsDir, targetName)
            val part = File(modelsDir, "$targetName.part")
            resolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                }
            } ?: throw IOException("ファイルを開けません")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed")
            target
        }
}
