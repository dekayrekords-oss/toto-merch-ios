package com.meowmba.shared.agent

/**
 * Curated on-device model catalog.
 * Ported from `core/agent/ModelCatalog.kt` unchanged (pure data).
 *
 * Every entry was verified against the Hugging Face API (2026-07):
 *  - URLs resolve to real MediaPipe/LiteRT `.task` bundles
 *  - `requiresHfToken = false` entries are truly ungated (Apache 2.0 repos,
 *    plain HTTPS download, no account)
 *  - `requiresHfToken = true` entries are license-gated (Gemma) and need a
 *    free Hugging Face token after accepting the license in a browser
 *
 * Any other model can still be downloaded via a custom URL — the catalog is
 * guidance, not a fence.
 */
data class LocalModelOption(
    val id: String,
    val displayName: String,
    val description: String,
    val approxSize: String,
    val downloadUrl: String,
    /** Destination file name inside the app's private models directory. */
    val fileName: String,
    val minRamGb: Int,
    val requiresHfToken: Boolean,
    val recommended: Boolean = false,
    val runtime: LocalModelRuntime = LocalModelRuntime.LITERT,
)

enum class LocalModelRuntime(val label: String, val canActivate: Boolean) {
    LITERT("LiteRT / MediaPipe", true),
    NEEDLE("Needle native", false),
}

object ModelCatalog {

    private const val HF = "https://huggingface.co"

    val options: List<LocalModelOption> = listOf(
        LocalModelOption(
            id = "needle-2",
            displayName = "Needle 2",
            description = "45M-parameter structured tool router by Cactus Compute. Apache 2.0 and optimized for tiny on-device agents.",
            approxSize = "~13.7 MB",
            downloadUrl = "$HF/Cactus-Compute/needle2/resolve/b739205bcda01f71ebf0ba180f75a885d515ad96/needle2.cact",
            fileName = "needle2.cact",
            minRamGb = 1,
            requiresHfToken = false,
            recommended = true,
            runtime = LocalModelRuntime.NEEDLE,
        ),
        LocalModelOption(
            id = "gemma-4-e2b",
            displayName = "Gemma 4 E2B IT",
            description = "Google's mobile-first model. Apache 2.0, no token needed. Best quality for 11 GB devices.",
            approxSize = "~2.59 GB",
            downloadUrl = "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            minRamGb = 4,
            requiresHfToken = false,
            recommended = false
        ),
        LocalModelOption(
            id = "qwen-0.5b",
            displayName = "Qwen 2.5 0.5B Instruct",
            description = "Tiny. Runs on any phone, instant routing decisions.",
            approxSize = "~0.6 GB",
            downloadUrl = "$HF/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
                "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            fileName = "qwen2.5-0.5b-instruct-q8.task",
            minRamGb = 3,
            requiresHfToken = false
        ),
        LocalModelOption(
            id = "qwen-1.5b",
            displayName = "Qwen 2.5 1.5B Instruct",
            description = "Best balance of quality and footprint. No account needed.",
            approxSize = "~1.7 GB",
            downloadUrl = "$HF/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            fileName = "qwen2.5-1.5b-instruct-q8.task",
            minRamGb = 4,
            requiresHfToken = false
        ),
        LocalModelOption(
            id = "qwen-1.5b-lc",
            displayName = "Qwen 2.5 1.5B (long context)",
            description = "Same model with a 4096-token window — better for memory recall prompts.",
            approxSize = "~1.8 GB",
            downloadUrl = "$HF/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            fileName = "qwen2.5-1.5b-instruct-q8-4096.task",
            minRamGb = 4,
            requiresHfToken = false
        ),
        LocalModelOption(
            id = "gemma-3-1b",
            displayName = "Gemma 3 1B IT",
            description = "Google's smallest full-quality model. Needs a free Hugging Face token.",
            approxSize = "~0.6 GB",
            downloadUrl = "$HF/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            fileName = "gemma3-1b-it-int4.task",
            minRamGb = 3,
            requiresHfToken = true
        )
    )

    fun optionForFile(fileName: String): LocalModelOption? =
        options.find { it.fileName == fileName }

    /**
     * Filename safety for custom downloads: simple name only (no paths or
     * traversal), and it must be a MediaPipe-runnable bundle.
     */
    fun isValidModelFileName(name: String): Boolean =
        name.matches(Regex("[a-zA-Z0-9._-]+")) &&
            (name.endsWith(".task") || name.endsWith(".litertlm") || name.endsWith(".cact"))
}

/**
 * Catalog entry for on-device image-generation models
 * (stable-diffusion.cpp, GGUF format). Parallel type, see original rationale.
 */
data class ImageModelOption(
    val id: String,
    val displayName: String,
    val description: String,
    val approxSize: String,
    val downloadUrl: String,
    /** Destination file name inside the app's private models directory. */
    val fileName: String,
    val minRamGb: Int,
    val requiresHfToken: Boolean,
    val recommended: Boolean = false
)

object ImageModelCatalog {

    private const val HF = "https://huggingface.co"

    val options: List<ImageModelOption> = listOf(
        ImageModelOption(
            id = "sdxl-lightning-4step-q8",
            displayName = "SDXL-Lightning (4-step, Q8_0)",
            description = "ByteDance's distilled SDXL for few-step generation, GGUF-quantized " +
                "for stable-diffusion.cpp. Generates 1024px images in ~4 steps.",
            approxSize = "~4.1 GB",
            downloadUrl = "$HF/OlegSkutte/SDXL-Lightning-GGUF/resolve/main/sdxl_lightning_4step.q8_0.gguf",
            fileName = "sdxl_lightning_4step.q8_0.gguf",
            minRamGb = 6,
            requiresHfToken = false,
            recommended = true
        )
    )

    fun optionForFile(fileName: String): ImageModelOption? =
        options.find { it.fileName == fileName }
}
