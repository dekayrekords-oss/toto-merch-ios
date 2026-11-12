package com.meowmba.shared.agent

/**
 * Platform-neutral model-file selection policy.
 *
 * Android and iOS runtime adapters own the actual native engine. This object
 * keeps selection and error presentation identical across those adapters.
 */
object LocalChatModel {
    private val supportedExtensions = setOf("task", "litertlm", "bin")

    fun supports(file: ModelFile): Boolean =
        file.name.substringAfterLast('.', "").lowercase() in supportedExtensions &&
            ModelCatalog.optionForFile(file.name)?.runtime?.canActivate != false

    fun resolve(configured: ModelFile?, installed: List<ModelFile>): ModelFile? {
        if (configured != null && supports(configured) && configured.isFile() && configured.length() > 0) {
            return configured
        }
        val available = installed.filter { supports(it) && it.isFile() && it.length() > 0 }
        return ModelCatalog.options.asSequence()
            .filter { it.runtime.canActivate }
            .mapNotNull { option -> available.firstOrNull { it.name == option.fileName } }
            .firstOrNull()
            ?: available.firstOrNull()
    }

    fun errorSummary(message: String): String? = when {
        listOf(
            "model_data.cc",
            "building tflite model",
            "failed to initialize engine",
            "not a chat model",
            "model file not found",
        ).any { message.contains(it, ignoreCase = true) } ->
            "The local AI model could not load. Select and test a compatible chat model, or re-download it. Needle is a tool router, not a chat model."
        else -> null
    }
}