package com.meowmba.shared.agent

import com.meowmba.shared.agent.tools.ToolDefinition

/**
 * Chat-template wrapper for on-device instruction-tuned models.
 * Ported from `core/agent/ChatTemplate.kt`, unchanged in behavior.
 *
 * On-device LLM runtimes often send the prompt RAW — they do not apply any
 * chat template. Instruction models trained on ChatML (Qwen, Hermes) or
 * Gemma leak template tokens (`<|im_start|>`), drift languages, or ramble
 * when given a flat prompt.
 *
 * The family is detected from the model path (same convention the agent
 * brain already uses for hermes detection). Pure and deterministic.
 */
object ChatTemplate {

    enum class Family { QWEN, GEMMA, HERMES, PLAIN }

    fun familyFor(modelPath: String): Family {
        val p = modelPath.lowercase()
        return when {
            "qwen" in p -> Family.QWEN
            "gemma" in p -> Family.GEMMA
            "hermes" in p -> Family.HERMES
            else -> Family.PLAIN
        }
    }

    /** Wrap a flat prompt in the model's native chat format. */
    fun wrap(modelPath: String, prompt: String): String = when (familyFor(modelPath)) {
        // ChatML — Qwen 2.5 instruct and Hermes 3 share it
        Family.QWEN, Family.HERMES ->
            "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
                "<|im_start|>user\n$prompt<|im_end|>\n" +
                "<|im_start|>assistant\n"
        Family.GEMMA ->
            "<start_of_turn>user\n$prompt<end_of_turn>\n" +
                "<start_of_turn>model\n"
        Family.PLAIN -> prompt
    }
}
