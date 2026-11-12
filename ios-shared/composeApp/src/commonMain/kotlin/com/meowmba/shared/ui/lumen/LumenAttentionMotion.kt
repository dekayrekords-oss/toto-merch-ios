package com.meowmba.shared.ui.lumen

import kotlin.math.hypot
import kotlin.random.Random
import com.meowmba.shared.behavior.BehaviorBias

internal data class LumenAttentionPoint(val x: Float = 0f, val y: Float = 0f)

/**
 * Error-adaptive acquisition, not prediction: a fast person is acquired quickly,
 * but a held/missing sample never invents more movement. Tiny detector noise keeps
 * the softer resting response. The same bound applies at 30, 60 and 120 Hz.
 */
internal class LumenGazeMotion {
    private val x = LumenSpring()
    private val y = LumenSpring()

    fun step(targetX: Float, targetY: Float, dt: Float, tracking: Boolean): LumenAttentionPoint {
        val tx = targetX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val ty = targetY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val error = hypot(tx - x.value, ty - y.value)
        val acquire = ((error - 0.035f) / 0.55f).coerceIn(0f, 1f)
        val speed = if (tracking) 19f + acquire * acquire * (3f - 2f * acquire) * 20f else 19f
        return LumenAttentionPoint(
            x.step(tx, dt, speed, MAX_VELOCITY, stopAtTarget = true).coerceIn(-1f, 1f),
            y.step(ty, dt, speed, MAX_VELOCITY, stopAtTarget = true).coerceIn(-1f, 1f),
        )
    }

    companion object { const val MAX_VELOCITY = 8f }
}

/**
 * A small seeded score of glances, holds and recovery, rather than a sine orbit
 * or per-frame random walk. Only active idle time advances the score; attention,
 * speech and authored performances get a quiet centered hand-back. Driving
 * reduces the stage travel while allowing this same score to keep running.
 */
internal class LumenIdleAttention(seed: Int) {
    /** Posture can hold its position after the eyes look back at the viewer. */
    var bodyTarget = LumenAttentionPoint(); private set
    private val random = Random(seed)
    private val order = mutableListOf(0, 1, 2, 3)
    private var orderIndex = order.size
    private var lastFamily = -1
    private var elapsed = 0f
    private var duration = 2.7f
    private var family = -1
    private var side = 1f
    private var strength = 1f
    private var enabled = false

    /**
     * [bias] is read as ratios against the resting bias (see [NervousSystem.REST_BIAS]),
     * so a calm character keeps the authored score and only a real departure from
     * rest reweights it. Passing null reproduces the pre-drives behaviour exactly.
     */
    fun step(dt: Float, allowed: Boolean, bias: BehaviorBias? = null): LumenAttentionPoint {
        if (!allowed) {
            enabled = false
            bodyTarget = LumenAttentionPoint()
            return LumenAttentionPoint()
        }
        if (!enabled) {
            enabled = true
            elapsed = 0f
            duration = 2.7f
            family = -1
        }
        elapsed += dt.coerceIn(0f, 0.05f)
        if (elapsed >= duration) {
            elapsed -= duration
            if (orderIndex >= order.size) {
                if (bias == null) {
                    order.shuffle(random)
                    if (order.first() == lastFamily) {
                        val first = order[0]; order[0] = order[1]; order[1] = first
                    }
                } else {
                    drawWeighted(bias)
                }
                orderIndex = 0
            }
            family = order[orderIndex++]
            lastFamily = family
            side = if (random.nextBoolean()) 1f else -1f
            strength = (0.82f + random.nextFloat() * 0.18f) *
                (bias?.glanceGain?.div(REST_GLANCE_GAIN) ?: 1f).coerceIn(DRIVE_GAIN_FLOOR, DRIVE_GAIN_CEILING)
            duration = when (family) {
                0 -> 4.5f + random.nextFloat() * 2.5f // Unhurried center rest.
                1 -> 4.0f + random.nextFloat() * 1.7f // Look, inspect nearby, recover.
                2 -> 4.4f + random.nextFloat() * 2.0f // Vertical curiosity.
                else -> 4.8f + random.nextFloat() * 1.7f // Low glance then look back.
            }
        }
        val point = when (family) {
            1 -> when {
                elapsed < 1.25f -> LumenAttentionPoint(side * 0.72f, -0.10f)
                elapsed < 2.45f -> LumenAttentionPoint(side * 0.48f, 0.08f)
                else -> LumenAttentionPoint()
            }
            2 -> when {
                elapsed < 1.7f -> LumenAttentionPoint(side * 0.19f, -0.53f)
                elapsed < 2.6f -> LumenAttentionPoint(side * 0.32f, -0.31f)
                else -> LumenAttentionPoint()
            }
            3 -> when {
                elapsed < 1.65f -> LumenAttentionPoint(side * 0.53f, 0.32f)
                elapsed < 2.55f -> LumenAttentionPoint(-side * 0.17f, -0.03f)
                else -> LumenAttentionPoint()
            }
            else -> LumenAttentionPoint()
        }
        val body = when (family) {
            1 -> when {
                elapsed < 2.45f -> LumenAttentionPoint(side * .90f, -.12f)
                elapsed < 3.25f -> LumenAttentionPoint(side * .72f, .04f)
                else -> LumenAttentionPoint()
            }
            2 -> when {
                elapsed < 2.6f -> LumenAttentionPoint(side * .32f, -.88f)
                elapsed < 3.45f -> LumenAttentionPoint(side * .26f, -.62f)
                else -> LumenAttentionPoint()
            }
            3 -> when {
                elapsed < 1.65f -> LumenAttentionPoint(side * .72f, .58f)
                elapsed < 3.2f -> LumenAttentionPoint(side * .45f, .40f)
                else -> LumenAttentionPoint()
            }
            else -> LumenAttentionPoint()
        }
        bodyTarget = LumenAttentionPoint(body.x * strength, body.y * strength)
        return LumenAttentionPoint(point.x * strength, point.y * strength)
    }

    /**
     * Draw the next four families without replacement, weighted by the drives:
     * a curious character inspects, a cautious or tired one rests. Equal weights
     * reduce to the uniform shuffle this replaced.
     */
    private fun drawWeighted(bias: BehaviorBias) {
        val remaining = mutableListOf(0, 1, 2, 3)
        val picked = ArrayList<Int>(4)
        while (remaining.size > 1) {
            var total = 0f
            for (f in remaining) total += familyWeight(f, bias)
            var r = if (total > 0f) random.nextFloat() * total else 0f
            var chosen = remaining.last()
            for (f in remaining) {
                r -= familyWeight(f, bias)
                if (r <= 0f) { chosen = f; break }
            }
            remaining.remove(chosen)
            picked.add(chosen)
        }
        picked.add(remaining.first())
        order.clear()
        order.addAll(picked)
        // Do not open a new round on the family that just finished.
        if (order.first() == lastFamily && order.size > 1) {
            val first = order[0]; order[0] = order[1]; order[1] = first
        }
    }

    private fun familyWeight(family: Int, bias: BehaviorBias): Float = when (family) {
        0 -> bias.restWeight / REST_WEIGHTS[0]
        1 -> bias.inspectWeight / REST_WEIGHTS[1]
        2 -> bias.verticalWeight / REST_WEIGHTS[2]
        else -> bias.inspectWeight / REST_WEIGHTS[3]
    }.coerceAtLeast(0.01f)

    private companion object {
        val REST_WEIGHTS = floatArrayOf(
            REST_BIAS.restWeight, REST_BIAS.inspectWeight,
            REST_BIAS.verticalWeight, REST_BIAS.inspectWeight,
        )
        val REST_GLANCE_GAIN = REST_BIAS.glanceGain
    }
}

/**
 * Whole-character position, not iris offset. Gaze acquires first; this slower
 * inertial channel follows observed attention or the idle score's held posture.
 * It consumes the existing active clock and never starts camera/model work.
 */
internal class LumenBodyMotion {
    private val x = LumenSpring()
    private val y = LumenSpring()

    fun step(target: LumenAttentionPoint, dt: Float, tracking: Boolean): LumenAttentionPoint {
        val tx = target.x.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val ty = target.y.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
        val error = hypot(tx - x.value, ty - y.value)
        val speed = if (tracking) 8.5f + (error / .7f).coerceIn(0f, 1f) * 2.5f else 3.8f
        val limit = if (tracking) 2.4f else 1.1f
        return LumenAttentionPoint(
            x.step(tx, dt, speed, limit, stopAtTarget = true),
            y.step(ty, dt, speed, limit, stopAtTarget = true),
        )
    }
}
