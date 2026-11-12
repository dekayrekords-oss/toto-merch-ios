package com.meowmba.shared.behavior

/**
 * Optional, gentle virtual-pet needs. These are game meters, never health diagnoses.
 * Off means self-sufficient, including across restarts; enabling starts fresh. Catch-up
 * is capped at one day and no meter falls below 10%, so absence never kills the pet.
 * The caller owns persistence and the clock; this value object schedules no work.
 *
 * Product language: Food, Energy, Mood (happiness), Affection, Hygiene.
 */
data class PetCareState(
    val careEnabled: Boolean = false,
    val food: Float = 1f,
    val energy: Float = 1f,
    val happiness: Float = 1f,
    val affection: Float = 1f,
    val hygiene: Float = 1f,
    val updatedAtMs: Long = 0,
) {
    fun advance(nowMs: Long): PetCareState {
        val previous = updatedAtMs.coerceAtLeast(0)
        val now = nowMs.coerceAtLeast(0).coerceAtLeast(previous)
        if (!careEnabled) {
            return copy(
                food = 1f, energy = 1f, happiness = 1f, affection = 1f, hygiene = 1f,
                updatedAtMs = now,
            )
        }
        val hours = if (previous == 0L) 0f else
            ((now.toDouble() - previous.toDouble()).coerceIn(0.0, DAY_MS) / HOUR_MS).toFloat()
        return copy(
            food = (meter(food) - hours * .08f).coerceAtLeast(.1f),
            energy = (meter(energy) - hours * .04f).coerceAtLeast(.1f),
            happiness = (meter(happiness) - hours * .05f).coerceAtLeast(.1f),
            affection = (meter(affection) - hours * .06f).coerceAtLeast(.1f),
            hygiene = (meter(hygiene) - hours * .03f).coerceAtLeast(.1f),
            updatedAtMs = now,
        )
    }

    fun feed(nowMs: Long): PetCareState = advance(nowMs).let {
        if (!it.careEnabled) it else it.copy(
            food = (it.food + .35f).coerceAtMost(1f),
            happiness = (it.happiness + .08f).coerceAtMost(1f),
            affection = (it.affection + .04f).coerceAtMost(1f),
        )
    }

    fun play(nowMs: Long): PetCareState = advance(nowMs).let {
        if (!it.careEnabled) it else it.copy(
            happiness = (it.happiness + .30f).coerceAtMost(1f),
            affection = (it.affection + .18f).coerceAtMost(1f),
            energy = (it.energy - .08f).coerceAtLeast(.1f),
            hygiene = (it.hygiene - .04f).coerceAtLeast(.1f),
        )
    }

    fun rest(nowMs: Long): PetCareState = advance(nowMs).let {
        if (!it.careEnabled) it else it.copy(
            energy = (it.energy + .40f).coerceAtMost(1f),
            happiness = (it.happiness + .05f).coerceAtMost(1f),
        )
    }

    fun clean(nowMs: Long): PetCareState = advance(nowMs).let {
        if (!it.careEnabled) it else it.copy(
            hygiene = (it.hygiene + .45f).coerceAtMost(1f),
            happiness = (it.happiness + .06f).coerceAtMost(1f),
            affection = (it.affection + .05f).coerceAtMost(1f),
        )
    }

    fun talk(nowMs: Long): PetCareState = advance(nowMs).let {
        if (!it.careEnabled) it else it.copy(
            affection = (it.affection + .28f).coerceAtMost(1f),
            happiness = (it.happiness + .12f).coerceAtMost(1f),
            energy = (it.energy - .02f).coerceAtLeast(.1f),
        )
    }

    fun withCare(enabled: Boolean, nowMs: Long): PetCareState {
        if (enabled == careEnabled) return advance(nowMs)
        return PetCareState(careEnabled = enabled, updatedAtMs = maxOf(nowMs, updatedAtMs, 0L))
    }

    /** Lowest need label for UI prompts; null when care is off or everything is fine. */
    fun primaryNeed(): String? {
        if (!careEnabled) return null
        val ranked = listOf(
            "Food" to food,
            "Energy" to energy,
            "Mood" to happiness,
            "Affection" to affection,
            "Hygiene" to hygiene,
        ).minBy { it.second }
        return if (ranked.second < .45f) ranked.first else null
    }

    /**
     * Short in-character reaction. [formKey] is an [AvatarAnimal.key] or null/generated.
     * Pure string helper for UI — never diagnoses health.
     */
    fun reaction(formKey: String? = null): String {
        if (!careEnabled) return "Self-sufficient and ready whenever you are."
        val form = formKey?.lowercase()?.ifBlank { null }
        val need = primaryNeed()
        if (need != null) {
            return when (need) {
                "Food" -> formFlavor(form, hungry = true)
                "Energy" -> formFlavor(form, sleepy = true)
                "Mood" -> formFlavor(form, gloomy = true)
                "Affection" -> formFlavor(form, lonely = true)
                "Hygiene" -> formFlavor(form, dusty = true)
                else -> "Could use a little care."
            }
        }
        val glow = listOf(food, energy, happiness, affection, hygiene).average()
        return if (glow >= .85) formFlavor(form, radiant = true) else formFlavor(form, content = true)
    }

    /** Compact versioned preference payload; invalid payloads return a self-sufficient pet. */
    fun encode(): String {
        val clean = advance(updatedAtMs)
        return "2|${clean.careEnabled}|${clean.food}|${clean.energy}|${clean.happiness}|" +
            "${clean.affection}|${clean.hygiene}|${clean.updatedAtMs}"
    }

    companion object {
        private const val HOUR_MS = 3_600_000.0
        private const val DAY_MS = HOUR_MS * 24
        private fun meter(value: Float) = if (value.isFinite()) value.coerceIn(.1f, 1f) else 1f

        fun decode(value: String?): PetCareState {
            val parts = value?.split('|') ?: return PetCareState()
            return when (parts.firstOrNull()) {
                "2" -> decodeV2(parts)
                "1" -> decodeV1(parts)
                else -> PetCareState()
            }
        }

        private fun decodeV2(parts: List<String>): PetCareState {
            if (parts.size != 8) return PetCareState()
            val enabled = when (parts[1]) { "true" -> true; "false" -> false; else -> return PetCareState() }
            val food = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return PetCareState()
            val energy = parts[3].toFloatOrNull()?.takeIf { it.isFinite() } ?: return PetCareState()
            val happiness = parts[4].toFloatOrNull()?.takeIf { it.isFinite() } ?: return PetCareState()
            val affection = parts[5].toFloatOrNull()?.takeIf { it.isFinite() } ?: return PetCareState()
            val hygiene = parts[6].toFloatOrNull()?.takeIf { it.isFinite() } ?: return PetCareState()
            val updated = parts[7].toLongOrNull()?.takeIf { it >= 0 } ?: return PetCareState()
            return PetCareState(
                enabled, meter(food), meter(energy), meter(happiness),
                meter(affection), meter(hygiene), updated,
            ).advance(updated)
        }

        /** v1 had food/energy/happiness only; affection mirrors mood, hygiene starts full. */
        private fun decodeV1(parts: List<String>): PetCareState {
            if (parts.size != 6) return PetCareState()
            val enabled = when (parts[1]) { "true" -> true; "false" -> false; else -> return PetCareState() }
            val food = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: return PetCareState()
            val energy = parts[3].toFloatOrNull()?.takeIf { it.isFinite() } ?: return PetCareState()
            val happiness = parts[4].toFloatOrNull()?.takeIf { it.isFinite() } ?: return PetCareState()
            val updated = parts[5].toLongOrNull()?.takeIf { it >= 0 } ?: return PetCareState()
            val mood = meter(happiness)
            return PetCareState(
                enabled, meter(food), meter(energy), mood, mood, 1f, updated,
            ).advance(updated)
        }

        private fun formFlavor(
            form: String?,
            hungry: Boolean = false,
            sleepy: Boolean = false,
            gloomy: Boolean = false,
            lonely: Boolean = false,
            dusty: Boolean = false,
            radiant: Boolean = false,
            content: Boolean = false,
        ): String {
            val who = when (form) {
                "cat", "kitten", "kitty" -> "kitty"
                "dog", "puppy", "pup", "australian_shepherd" -> "pup"
                "rabbit" -> "bunny"
                "owl" -> "owl"
                "duck" -> "duck"
                "panda" -> "panda"
                "robot" -> "bot"
                "alien" -> "little alien"
                "fruit_fly" -> "fly"
                "raccoon" -> "bandit"
                "squirrel" -> "squirrel"
                "pig" -> "piglet"
                "clippy" -> "clippy"
                "wizard" -> "wizard"
                else -> "companion"
            }
            return when {
                hungry -> when (who) {
                    "kitty" -> "Paws the bowl — a snack would make this kitty purr."
                    "pup" -> "Tail thumps for a treat."
                    "fly" -> "Hungry wings want a crumb."
                    "bot" -> "Fuel cells low — a top-up would help."
                    else -> "Looking snacky — a little food would land well."
                }
                sleepy -> when (who) {
                    "owl" -> "Night eyes droop — rest sounds wise."
                    "kitty" -> "Soft blinks say nap time."
                    "bot" -> "Systems want a quiet recharge."
                    else -> "Energy is soft — a rest would feel good."
                }
                gloomy -> when (who) {
                    "duck" -> "A play splash would brighten the mood."
                    "panda" -> "Could use a gentle romp."
                    else -> "Mood could use a spark — play or a chat?"
                }
                lonely -> when (who) {
                    "clippy" -> "It looks like you could talk — and so could I."
                    "wizard" -> "A kind word would charm this wizard."
                    "kitty" -> "Head-bonk pending — say hi?"
                    else -> "Affection dips — talk or play together."
                }
                dusty -> when (who) {
                    "pig" -> "A tidy-up would feel fresh."
                    "raccoon" -> "Time to wash those little paws."
                    else -> "Hygiene could use a gentle clean."
                }
                radiant -> when (who) {
                    "kitty" -> "Glowing and content — this kitty feels loved."
                    "pup" -> "Bright eyes and a full heart."
                    "wizard" -> "Magic mood: well cared for."
                    else -> "Thriving — care is landing beautifully."
                }
                else -> when (who) {
                    "kitty" -> "Settled and soft — keep the gentle rhythm."
                    "fly" -> "Hovering happily nearby."
                    else -> "Balanced and present with you."
                }
            }
        }
    }
}

/** Android `ui/companion/PetHabitatWorld.HabitatAction`: what a care button asks the pet to do. */
enum class HabitatAction { FEED, PLAY, REST, READ, DRINK, CLEAN, TALK, NUDGE, RELEASE, EXPLORE }
