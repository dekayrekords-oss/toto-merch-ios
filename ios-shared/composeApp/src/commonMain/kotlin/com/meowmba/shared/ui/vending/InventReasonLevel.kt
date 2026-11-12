package com.meowmba.shared.ui.vending

/**
 * Quality vs speed for Become-anything invent / refine.
 * Higher levels run more invent attempts and schema-review repair passes.
 */
enum class InventReasonLevel(
    val label: String,
    val detail: String,
    /** Fresh invent generation attempts (first success that parses wins; otherwise last). */
    val inventPasses: Int,
    /** Extra review/repair passes after a successful parse (0 = schema repair only on failure). */
    val reviewPasses: Int,
) {
    FAST("Faster", "One invent pass, repair only if the drawing fails schema", 1, 0),
    BALANCED("Balanced", "One invent pass plus a quality review", 1, 1),
    THOROUGH("Higher quality", "Two invent passes and two review iterations", 2, 2);

    companion object {
        fun fromKey(raw: String?): InventReasonLevel =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BALANCED
    }
}
