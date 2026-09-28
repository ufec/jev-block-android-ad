package me.ethanxu.jevnoisegate.app

import android.content.Context
import com.laya.LayaEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import me.ethanxu.jevnoisegate.core.decision.BackendOutcome
import me.ethanxu.jevnoisegate.core.decision.DecisionBackend
import me.ethanxu.jevnoisegate.core.decision.DecisionInput
import me.ethanxu.jevnoisegate.core.model.FailureReason
import me.ethanxu.jevnoisegate.core.common.log.AppLog
import me.ethanxu.jevnoisegate.core.common.log.LogCategory

data class LocalModelStatus(val message: String = "正在检查模型…", val ready: Boolean = false, val busy: Boolean = false, val progress: Float = 0f)

/** Entire decision path runs inside the app process. No client, key, or network fallback. */
@Singleton
class LocalLayaBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : DecisionBackend {
    private val lock = Any()
    private var engine: LayaEngine? = null
    private val mutableStatus = MutableStateFlow(LocalModelStatus())
    val status = mutableStatus.asStateFlow()
    private val operation = kotlinx.coroutines.sync.Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun download() {
        scope.launch {
            if (!operation.tryLock()) return@launch
            try {
                mutableStatus.value = LocalModelStatus("正在下载模型（约 679 MB）…", busy = true)
                ModelAssets.download(context) { progress ->
                    mutableStatus.value = LocalModelStatus("正在下载模型 · ${(progress * 100).toInt()}%", busy = true, progress = progress)
                }
                synchronized(lock) { readyEngine() }
            } catch (failure: Exception) {
                mutableStatus.value = LocalModelStatus("下载或校验失败，请重试：${failure.message}")
            } finally { operation.unlock() }
        }
    }

    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (!operation.tryLock()) return@withContext
        try {
            mutableStatus.value = LocalModelStatus("正在校验本地模型…", busy = true)
            if (!ModelAssets.verify(context)) {
                me.ethanxu.jevnoisegate.core.common.ServiceState.modelReady = false
                synchronized(lock) { engine?.close(); engine = null }
                mutableStatus.value = LocalModelStatus("模型未下载或校验失败，请下载模型（约 679 MB）")
                return@withContext
            }
            synchronized(lock) { readyEngine() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            me.ethanxu.jevnoisegate.core.common.ServiceState.modelReady = false
            mutableStatus.value = LocalModelStatus("模型初始化失败，消息会放行")
            AppLog.w(LogCategory.MODEL, "Laya", failure) { "本地模型初始化失败" }
        } finally { operation.unlock() }
        Unit
    }

    private fun readyEngine(): LayaEngine {
        engine?.let {
            mutableStatus.value = LocalModelStatus("Laya Multilingual · CPU · 已就绪", ready = true)
            return it
        }
        mutableStatus.value = LocalModelStatus("正在校验本地模型…", busy = true)
        check(ModelAssets.verify(context)) { "模型文件缺失或损坏，请下载模型" }
        mutableStatus.value = LocalModelStatus("正在初始化本地模型…", busy = true)
        val created = LayaEngine(context)
        try {
            created.initialize(LayaEngine.Backend.CPU)
        } catch (failure: Throwable) {
            created.close()
            throw failure
        }
        engine = created
        me.ethanxu.jevnoisegate.core.common.ServiceState.modelReady = true
        mutableStatus.value = LocalModelStatus("Laya Multilingual · CPU · 已就绪", ready = true)
        AppLog.i(LogCategory.MODEL, "Laya") { "本地模型已就绪 backend=CPU" }
        return created
    }

    override suspend fun decide(input: DecisionInput): BackendOutcome = withContext(Dispatchers.IO) {
        try {
            synchronized(lock) {
                require(input.categories.map { it.name }.distinct().size == input.categories.size)
                require(input.categories.size in 2..16)
                val ready = readyEngine()
                val criteria = linkedMapOf<String, Any?>()
                input.categories.forEach { criteria[it.name] = it.description }
                val question = linkedMapOf<String, Any?>(
                    "type" to "choice",
                    "instructions" to "这条消息属于哪一类？若无法确定，选择正常。",
                    "criteria" to criteria,
                )
                // Keep the message first: the 256-token graph truncates from the right.
                val state = buildString {
                    append(input.event.body.take(500))
                    input.event.title?.takeIf { it.isNotBlank() }?.let { append("\n标题：").append(it.take(80)) }
                    if (input.userRules.preferAllow.isNotEmpty()) {
                        append("\n用户希望放行：").append(input.userRules.preferAllow.joinToString("；").take(120))
                    }
                    if (input.userRules.preferBlock.isNotEmpty()) {
                        append("\n用户希望拦截：").append(input.userRules.preferBlock.joinToString("；").take(120))
                    }
                }
                val answer = ready.answer(state, question, LayaEngine.Backend.CPU).answer
                val category = answer["choice"] as? String ?: error("Missing category")
                @Suppress("UNCHECKED_CAST")
                val probabilities = (answer["probabilities"] as? Map<String, Number>)
                    ?.mapValues { it.value.toFloat() } ?: error("Missing probabilities")
                val confidence = probabilities[category] ?: error("Missing selected probability")
                check(confidence.isFinite() && probabilities.values.all { it.isFinite() })
                BackendOutcome.Decided(category, confidence, probabilities)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (engine == null) mutableStatus.value = LocalModelStatus("模型初始化失败，消息会放行")
            BackendOutcome.Failed(FailureReason.MODEL_ERROR, failure.message)
        }
    }
}

/** Downloads pinned files; checks every byte before installing. */
internal object ModelAssets {
    internal data class Asset(val name: String, val size: Long, val sha256: String)
    private val assets = listOf(
        Asset("laya_ml_s256_embeds_wfp16.tflite", 250889408, "f55216170734e38eb287f46de4f11cb1863afba30c09f586650f1ce7b03de81e"),
        Asset("laya_ml_act_head_fp32.tflite", 795816, "30da532a8b752fdc82351740b0194d440a13d342ed77b9f8848978f1a96cf16d"),
        Asset("laya_ml_calibration.json", 9156, "80e148c68154b607e7bb66446f064b1fe5da649e1074c917b25e02551a566f2c"),
        Asset("tokenizer.json", 34363188, "609d8f4c067cd3950f88594c5a802616cea245823836ef5848ee4fc40aab5b6f"),
        Asset("token_embeddings_fp16.bin", 393216000, "58608f7bbd72e03adb3e2db8c4942407058eaeaac38e3abec1d6cb01c1512265"),
        Asset("token_embeddings.json", 801, "8b25844d858ace3833b69354a60536111e9bbcecb2e305bee7971e8dfcbd83ef"),
    )

    private const val BASE = "https://huggingface.co/litert-community/Laya-Multilingual-LiteRT/resolve/058cbf34ed2c6a2854ec60e9534c229d08740387/"

    internal fun valid(file: File, asset: Asset): Boolean {
        if (!file.isFile || file.length() != asset.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
    }

    fun verify(context: Context): Boolean = assets.all { valid(File(context.filesDir, it.name), it) }

    fun download(context: Context, progress: (Float) -> Unit) = download(context.filesDir, assets, progress = progress)

    internal fun download(
        directory: File,
        manifest: List<Asset>,
        connect: (String) -> java.net.HttpURLConnection = { java.net.URL(it).openConnection() as java.net.HttpURLConnection },
        progress: (Float) -> Unit,
    ) {
        val total = manifest.sumOf { it.size }
        var completed = 0L
        manifest.forEach { asset ->
            val target = File(directory, asset.name)
            if (!valid(target, asset)) {
                val temp = File(directory, "${asset.name}.part")
                if (temp.length() > asset.size || (temp.length() == asset.size && !valid(temp, asset))) check(temp.delete())
                if (temp.length() < asset.size) {
                    val offset = temp.length()
                    val connection = connect(BASE + asset.name + "?download=true")
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                    try {
                        val code = connection.responseCode
                        check(connection.url.protocol == "https") { "下载地址必须为 HTTPS" }
                        check(code == 200 || code == 206) { "下载失败 HTTP $code" }
                        val append = code == 206 && offset > 0
                        if (code == 206) check(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "断点响应不匹配" }
                        var received = if (append) offset else 0L
                        connection.inputStream.use { source ->
                            java.io.FileOutputStream(temp, append).buffered().use { sink ->
                                val buffer = ByteArray(1024 * 1024)
                                while (true) {
                                    val n = source.read(buffer)
                                    if (n < 0) break
                                    received += n
                                    check(received <= asset.size) { "下载文件大小异常" }
                                    sink.write(buffer, 0, n)
                                    progress((completed + received).toFloat() / total)
                                }
                            }
                        }
                    } finally { connection.disconnect() }
                }
                if (!valid(temp, asset)) {
                    temp.delete()
                    error("模型校验失败：${asset.name}")
                }
                check(temp.renameTo(target)) { "无法安装模型：${asset.name}" }
            }
            completed += asset.size
            progress(completed.toFloat() / total)
        }
    }
}
