package com.meowmba.shared.agent

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Implemented by the native iOS llama.cpp host; no server or API credentials. */
interface OnDeviceDriver {
    fun isInstalled(): Boolean
    fun install(onProgress: (Float) -> Unit, onComplete: () -> Unit, onError: (String) -> Unit)
    fun generate(prompt: String, onToken: (String) -> Unit, onComplete: () -> Unit, onError: (String) -> Unit)
    fun cancel()
    fun unload()
    /** Which catalog model [isInstalled], [install] and [generate] act on. */
    fun select(fileName: String, url: String, bytes: Long, sha256: String) {}
    /** Model files present on the phone. */
    fun installedFiles(): List<String> = emptyList()
    fun delete(fileName: String) {}
}

/** A GGUF model the phone runs through llama.cpp; size and SHA-256 are checked after download. */
data class OnDeviceModelOption(
    val id: String, val name: String, val description: String, val fileName: String, val url: String,
    val bytes: Long, val sha256: String, val minRamGb: Int, val recommended: Boolean = false,
) {
    val sizeLabel: String get() = if (bytes >= 1_000_000_000) "${bytes / 100_000_000 / 10.0} GB" else "${bytes / 1_000_000} MB"
}

/**
 * Checked against the Hugging Face API on 2026-09-24: ungated repos, exact byte counts and SHA-256
 * from the LFS pointers. Android's local catalog is MediaPipe `.task` bundles, which iOS cannot
 * run; these are GGUF models for the app's llama.cpp runtime.
 */
object OnDeviceCatalog {
    private const val HF = "https://huggingface.co"
    val options: List<OnDeviceModelOption> = listOf(
        OnDeviceModelOption("qwen3-0.6b", "Qwen3 0.6B", "Fastest. Good for quick chat on any iPhone.", "Qwen3-0.6B-Q8_0.gguf", "$HF/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf", 639446688L, "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", 3, recommended = true),
        OnDeviceModelOption("llama-3.2-1b", "Llama 3.2 1B", "Meta's small instruct model. Friendly, quick replies.", "Llama-3.2-1B-Instruct-Q4_K_M.gguf", "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807694464L, "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", 4),
        OnDeviceModelOption("gemma-3-1b", "Gemma 3 1B", "Google's small model. Clear, concise answers.", "google_gemma-3-1b-it-Q4_K_M.gguf", "$HF/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf", 806058496L, "12bf0fff8815d5f73a3c9b586bd8fee8e7b248c935de70dec367679873d0f29d", 4),
        OnDeviceModelOption("smollm2-1.7b", "SmolLM2 1.7B", "Hugging Face's compact assistant model.", "SmolLM2-1.7B-Instruct-Q4_K_M.gguf", "$HF/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf", 1055609824L, "77665ea4815999596525c636fbeb56ba8b080b46ae85efef4f0d986a139834d7", 4),
        OnDeviceModelOption("qwen3-1.7b", "Qwen3 1.7B", "Noticeably smarter than 0.6B; still quick on recent iPhones.", "Qwen3-1.7B-Q8_0.gguf", "$HF/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf", 1834426016L, "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a", 6),
        OnDeviceModelOption("llama-3.2-3b", "Llama 3.2 3B", "Stronger reasoning; slower and warmer.", "Llama-3.2-3B-Instruct-Q4_K_M.gguf", "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2019377696L, "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff", 6),
        OnDeviceModelOption("phi-3.5-mini", "Phi-3.5 mini", "Microsoft's 3.8B model. Good at explaining.", "Phi-3.5-mini-instruct-Q4_K_M.gguf", "$HF/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf", 2393232672L, "e4165e3a71af97f1b4820da61079826d8752a2088e313af0c7d346796c38eff5", 8),
        OnDeviceModelOption("gemma-3-4b", "Gemma 3 4B", "Strong writer; needs 8 GB RAM.", "google_gemma-3-4b-it-Q4_K_M.gguf", "$HF/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf", 2489758112L, "4996030242583a40aa151ff93f49ed787ac8c25e4120c3ae4588b2e2a7d1ae94", 8),
        OnDeviceModelOption("qwen3-4b", "Qwen3 4B", "Best reasoning here; needs 8 GB RAM and runs warm.", "Qwen3-4B-Q4_K_M.gguf", "$HF/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", 2497280256L, "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", 8),
    )
    fun byId(id: String?): OnDeviceModelOption? = options.firstOrNull { it.id == id }
    val default: OnDeviceModelOption get() = options.first()
}

object OnDeviceModel {
    private val storage = com.meowmba.shared.core.DefaultTextStorage
    /** The model chat uses on the phone; persisted, Qwen3 0.6B until changed. */
    val active = MutableStateFlow(OnDeviceCatalog.byId(storage.readText("local_model_id")) ?: OnDeviceCatalog.default)
    val NAME: String get() = active.value.name
    var driver: OnDeviceDriver? = null
        set(value) { field = value; value?.let { d -> active.value.let { d.select(it.fileName, it.url, it.bytes, it.sha256) } } }
    fun choose(option: OnDeviceModelOption) {
        storage.writeTextAtomic("local_model_id", option.id)
        active.value = option
        driver?.select(option.fileName, option.url, option.bytes, option.sha256)
    }
}
class OnDeviceProvider : ModelProvider {
    override val name: String get() = "On-device · " + OnDeviceModel.NAME
    override fun generateStreaming(prompt: String): Flow<ModelResponse> = callbackFlow {
        val driver = OnDeviceModel.driver
        if (driver == null || !driver.isInstalled()) {
            trySend(ModelResponse.Error("Download the on-device model in LLM first.")); close()
        } else {
            driver.generate(prompt, { trySend(ModelResponse.Partial(it)) },
                { trySend(ModelResponse.Complete("")); close() },
                { trySend(ModelResponse.Error(it)); close() })
        }
        awaitClose { driver?.cancel() }
    }.buffer(kotlinx.coroutines.channels.Channel.UNLIMITED)
    override suspend fun generate(prompt: String): ModelResponse {
        val text = StringBuilder(); var error: ModelResponse.Error? = null
        generateStreaming(prompt).collect { when(it) {
            is ModelResponse.Partial -> text.append(it.text)
            is ModelResponse.Error -> error = it
            else -> Unit
        } }
        return error ?: ModelResponse.Complete(text.toString())
    }
}
