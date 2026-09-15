package com.example.AudioCut.asr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 离线模型管理：负责识别模型（SenseVoice）与标点模型（punct-ct）的存放、检测、下载与导入。
 *
 * 目录约定（应用私有目录 filesDir/models 下）：
 * - models/sense_voice/  : SenseVoice 识别模型（model.int8.onnx + tokens.txt）
 * - models/punct_ct/     : punct-ct 标点恢复模型（model.int8.onnx + tokens.json）
 * - models/vad/          : Silero VAD 模型（从 assets 拷贝，assets 仅保留 silero_vad.int8.onnx）
 *
 * 下载失败的场景由界面层弹卡片展示下载地址，用户手动下载后通过 [installFromUri] 导入。
 */
class ModelManager(private val context: Context) {

    /** 模型根目录：filesDir/models */
    private val modelsRoot: File
        get() = File(context.filesDir, "models")

    val senseVoiceDir: File
        get() = File(modelsRoot, ModelType.SENSE_VOICE.dirName)

    val punctDir: File
        get() = File(modelsRoot, ModelType.PUNCT_CT.dirName)

    val vadDir: File
        get() = File(modelsRoot, "vad")

    /** VAD 模型文件（assets 中唯一保留的模型文件） */
    val vadModelFile: File
        get() = File(vadDir, "silero_vad.int8.onnx")

    // ========== 模型类型定义 ==========
    enum class ModelType(
        val displayName: String,
        val dirName: String,
        val downloadUrl: String,
        val requiredFiles: List<String>
    ) {
        SENSE_VOICE(
            displayName = "SenseVoice 识别模型",
            dirName = "sense_voice",
            downloadUrl = "https://links.8uid.com/d/152de4ca37ae0b417573e36460e37cf7",
            requiredFiles = listOf("model.int8.onnx", "tokens.txt")
        ),
        PUNCT_CT(
            displayName = "标点恢复模型（punct-ct）",
            dirName = "punct_ct",
            downloadUrl = "https://links.8uid.com/d/6876c6ae0886d371cdd27c8d9b051235",
            requiredFiles = listOf("model.int8.onnx", "tokens.json")
        )
    }

    // ========== 状态检测 ==========
    fun isModelReady(type: ModelType): Boolean {
        val dir = modelDir(type)
        return type.requiredFiles.all { name ->
            val f = File(dir, name)
            val minSize = if (name.endsWith(".onnx")) {
                if (type == ModelType.SENSE_VOICE) 50_000_000L else 1_000_000L
            } else {
                1_000L
            }
            f.exists() && f.isFile && f.length() > minSize
        }
    }

    fun isSenseVoiceReady(): Boolean = isModelReady(ModelType.SENSE_VOICE)

    fun isPunctReady(): Boolean = isModelReady(ModelType.PUNCT_CT)

    private fun modelDir(type: ModelType): File = when (type) {
        ModelType.SENSE_VOICE -> senseVoiceDir
        ModelType.PUNCT_CT -> punctDir
    }

    // ========== VAD 模型（assets 拷贝） ==========
    /**
     * 确保 VAD 模型就绪：从 assets（仅保留 silero_vad.int8.onnx）拷贝到 filesDir/models/vad，
     * 同时迁移旧版本 model_offline 目录下的模型文件。
     * assets 兼容两种布局：assets/silero_vad.int8.onnx 或 assets/model_offline/silero_vad.int8.onnx
     */
    suspend fun ensureVadModel(): File? = withContext(Dispatchers.IO) {
        runCatching {
            migrateLegacyModels()
            if (vadModelFile.exists() && vadModelFile.length() > 0L) return@withContext vadModelFile

            vadDir.mkdirs()
            val assetPaths = listOf(
                "model_offline/silero_vad.int8.onnx",
                "silero_vad.int8.onnx"
            )
            val input = assetPaths.firstNotNullOfOrNull { path ->
                runCatching { context.assets.open(path) }.getOrNull()
            } ?: return@withContext null

            input.use { stream ->
                FileOutputStream(vadModelFile).use { output -> stream.copyTo(output) }
            }
            if (vadModelFile.exists() && vadModelFile.length() > 0L) vadModelFile else null
        }.getOrNull()
    }

    /**
     * 迁移旧版目录 filesDir/model_offline 下的模型文件到新目录。
     * 旧版将 SenseVoice 模型打进 assets 并拷贝到 model_offline，新版改为独立模型目录。
     */
    private fun migrateLegacyModels() {
        val legacy = File(context.filesDir, "model_offline")
        if (!legacy.exists()) return

        // 迁移识别模型
        if (!isSenseVoiceReady()) {
            val legacyModel = File(legacy, "model.int8.onnx")
            val legacyTokens = File(legacy, "tokens.txt")
            if (legacyModel.exists() && legacyTokens.exists()) {
                senseVoiceDir.mkdirs()
                legacyModel.copyTo(File(senseVoiceDir, "model.int8.onnx"), overwrite = true)
                legacyTokens.copyTo(File(senseVoiceDir, "tokens.txt"), overwrite = true)
            }
        }

        // 迁移 VAD 模型
        if (!vadModelFile.exists()) {
            val legacyVad = File(legacy, "silero_vad.int8.onnx")
            if (legacyVad.exists()) {
                vadDir.mkdirs()
                legacyVad.copyTo(vadModelFile, overwrite = true)
            }
        }
    }

    // ========== 下载 ==========
    /**
     * 下载模型压缩包并解压安装。
     * @param onProgress 下载进度回调（0f~1f）；总长度未知时回调 -1f，界面显示不确定进度
     * @param onExtract 解压进度回调（0f~1f），下载完成后进入解压阶段
     * 支持协程取消：下载/解压循环中检查取消，点击停止时清理临时文件并传播取消
     */
    suspend fun downloadAndInstall(
        type: ModelType,
        onProgress: (Float) -> Unit,
        onExtract: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val archiveFile = File(context.cacheDir, "download_${type.name.lowercase()}.tar.bz2")
        try {
            if (archiveFile.exists()) archiveFile.delete()

            // 1. 下载
            val connection = (URL(type.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AudioCut-ModelInstaller/1.0")
                useCaches = false
            }
            try {
                val code = connection.responseCode
                if (code !in 200..399) {
                    error("下载失败（HTTP $code）")
                }
                val total = connection.contentLengthLong
                FileOutputStream(archiveFile).use { output ->
                    val input = BufferedInputStream(connection.inputStream)
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        } else {
                            onProgress(-1f)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (!archiveFile.exists() || archiveFile.length() == 0L) {
                error("下载内容为空")
            }

            // 2. 解压安装（带解压进度）
            extractAndInstall(type, archiveFile, onExtract).getOrElse {
                error(it.message ?: "模型解压安装失败")
            }
            archiveFile.delete()
            Result.success(Unit)
        } catch (e: CancellationException) {
            // 用户点击停止：清理未完成的临时文件后向调用方传播取消
            archiveFile.delete()
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========== 本地导入 ==========
    /**
     * 导入用户手动下载的模型压缩包（通过文件选择器选取的 Uri）。
     * @param onProgress 文件复制进度（未知大小时回调 -1f）
     * @param onExtract 解压进度回调（0f~1f）
     * 支持协程取消：复制/解压循环中检查取消，点击停止时清理临时文件并传播取消
     */
    suspend fun installFromUri(
        type: ModelType,
        uri: Uri,
        onProgress: (Float) -> Unit,
        onExtract: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val archiveFile = File(context.cacheDir, "import_${type.name.lowercase()}.tar.bz2")
        try {
            if (archiveFile.exists()) archiveFile.delete()

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(archiveFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        onProgress(-1f) // 本地复制很快，显示不确定进度
                    }
                }
            } ?: error("无法读取所选文件")

            if (archiveFile.length() == 0L) error("所选文件为空")

            extractAndInstall(type, archiveFile, onExtract).getOrElse {
                error(it.message ?: "模型解压安装失败")
            }
            archiveFile.delete()
            Result.success(Unit)
        } catch (e: CancellationException) {
            archiveFile.delete()
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========== 解压安装 ==========
    /**
     * 解压 tar.bz2 / tar.gz 压缩包到对应模型目录，并校验必需文件。
     * 解压后把必需文件统一移动到目录根部，再清理多余文件。
     * @param onExtract 解压进度回调（0f~1f，按压缩包已读字节估算）
     */
    private suspend fun extractAndInstall(
        type: ModelType,
        archiveFile: File,
        onExtract: (Float) -> Unit = {}
    ): Result<Unit> {
        val destDir = modelDir(type)
        // 全新安装前清理旧目录，避免残留文件干扰校验
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        try {
            // 计数输入流：按压缩字节推进解压进度
            val totalBytes = archiveFile.length()
            var readBytes = 0L
            var lastReportedPct = -1
            // 解压阶段立即回调 0f，确保 UI 从"正在下载"切换到"正在解压"
            onExtract(0f)
            val counting = object : InputStream() {
                private val delegate = BufferedInputStream(FileInputStream(archiveFile))
                override fun read(): Int {
                    val b = delegate.read()
                    if (b != -1) {
                        readBytes++
                        report()
                    }
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = delegate.read(b, off, len)
                    if (n > 0) {
                        readBytes += n
                        report()
                    }
                    return n
                }

                override fun close() = delegate.close()

                /** 每越过 1% 回调一次，避免频繁刷新 UI */
                private fun report() {
                    if (totalBytes <= 0) return
                    val pct = ((readBytes * 100) / totalBytes).toInt()
                    if (pct != lastReportedPct) {
                        lastReportedPct = pct
                        onExtract((readBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }

            val stream: InputStream = when {
                archiveFile.name.endsWith(".tar.gz") || archiveFile.name.endsWith(".tgz") ->
                    GZIPInputStream(counting)
                else ->
                    BZip2CompressorInputStream(counting)
            }

            stream.use { raw ->
                val tar = TarArchiveInputStream(raw)
                var entry = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val rel = normalizePath(entry.name)
                        if (rel.isNotBlank() && !hasTraversal(rel)) {
                            val outFile = File(destDir, rel)
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                while (tar.read(buffer).also { read = it } != -1) {
                                    currentCoroutineContext().ensureActive()
                                    out.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
            // 解压完成
            onExtract(1.0f)
        } catch (e: CancellationException) {
            destDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            destDir.deleteRecursively()
            return Result.failure(e)
        }

        // 校验必需文件（允许位于子目录中）
        val missing = type.requiredFiles.filter { name ->
            destDir.walkTopDown().none { it.isFile && it.name == name }
        }
        if (missing.isNotEmpty()) {
            destDir.deleteRecursively()
            return Result.failure(IllegalStateException("压缩包中缺少文件: ${missing.joinToString(", ")}"))
        }

        // 将必需文件移动到目录根部，清理其它多余文件
        for (name in type.requiredFiles) {
            val target = File(destDir, name)
            val found = destDir.walkTopDown().first { it.isFile && it.name == name }
            if (found.absolutePath != target.absolutePath) {
                found.copyTo(target, overwrite = true)
                found.delete()
            }
        }
        // 删除残留子目录（test_wavs、README 等）
        destDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
        destDir.listFiles()?.filter { it.isFile && it.name !in type.requiredFiles }?.forEach { it.delete() }

        return Result.success(Unit)
    }

    private fun normalizePath(name: String): String {
        return name.replace('\\', '/').trimStart('.', '/')
    }

    /** 防路径穿越：拒绝包含 .. 路径段的条目 */
    private fun hasTraversal(rel: String): Boolean {
        return rel.split('/').any { it == ".." }
    }
}
