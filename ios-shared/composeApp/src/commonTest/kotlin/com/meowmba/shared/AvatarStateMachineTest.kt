package com.meowmba.shared

import com.meowmba.shared.companion.CatAnimationRegistry
import com.meowmba.shared.companion.CatAvatarStateMachine
import com.meowmba.shared.companion.CompanionEmotion
import com.meowmba.shared.companion.CompanionStateCore
import com.meowmba.shared.companion.DriveReaction
import com.meowmba.shared.companion.InteractionState
import com.meowmba.shared.companion.MouthMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarStateMachineTest {

    @Test
    fun idlePoseIsNeutral() {
        val pose = CatAvatarStateMachine.resolvePose(CompanionStateCore())
        assertEquals(1.0f, pose.eyeOpen)
        assertEquals(1.0f, pose.earPerk)
        assertEquals(MouthMode.IDLE, pose.mouth)
        assertFalse(pose.dimmed)
        assertFalse(pose.micOffMark)
        assertFalse(pose.cameraOffMark)
    }

    @Test
    fun listeningPosesAlert() {
        val pose = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(interactionState = InteractionState.LISTENING)
        )
        assertTrue(pose.eyeOpen > 1.0f)
        assertTrue(pose.earPerk > 1.0f)
        assertTrue(pose.glowPulse)
        assertEquals(0xFF58A6FF, pose.glowColorHex)
    }

    @Test
    fun thinkingLooksUp() {
        val pose = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(interactionState = InteractionState.THINKING)
        )
        assertTrue(pose.pupilGazeUp > 0f)
        assertEquals(0xFFD29922, pose.glowColorHex)
    }

    @Test
    fun speakingMouthMode() {
        val pose = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(interactionState = InteractionState.SPEAKING)
        )
        assertEquals(MouthMode.SPEAKING, pose.mouth)
        assertTrue(pose.whiskerFlutter)
    }

    @Test
    fun privacyMarksOverrideExpression() {
        val pose = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(isMicEnabled = false, isCameraEnabled = false, isMuted = true)
        )
        assertTrue(pose.micOffMark)
        assertTrue(pose.cameraOffMark)
        assertTrue(pose.dimmed)
        assertEquals(MouthMode.MUTED, pose.mouth)
        assertTrue(pose.eyeOpen < 0.5f)  // camera privacy closes the eyes
    }

    @Test
    fun errorStateTurnsHaloRed() {
        val pose = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(errorMessage = "provider unreachable")
        )
        assertEquals(0xFFF85149, pose.glowColorHex)
    }

    @Test
    fun driveReactionsAreAdditive() {
        val lean = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(driveReaction = DriveReaction.LEAN_LEFT)
        )
        assertEquals(-1f, lean.leanX)
        assertTrue(lean.eyeOpen > 1.0f)

        val speed = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(driveReaction = DriveReaction.SPEED)
        )
        assertTrue(speed.sparkle > 0f)
        assertTrue(speed.tailWag > 0f)

        val lurch = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(driveReaction = DriveReaction.LURCH)
        )
        assertTrue(lurch.bodyBounce < 0f)
    }

    @Test
    fun happyEmotionUsesHappyEyesWhenCameraOn() {
        val pose = CatAvatarStateMachine.resolvePose(
            CompanionStateCore(emotion = CompanionEmotion.HAPPY)
        )
        assertTrue(pose.happyEyes)
        assertTrue(pose.earDroopDeg > 0f)
    }

    @Test
    fun registryResolvesKnownAndFallsBack() {
        val blink = CatAnimationRegistry.spec("idle.blink")
        assertEquals(100, blink.durationMs)
        assertTrue(blink.minDelayMs > 0)

        val fallback = CatAnimationRegistry.spec("no.such.clip")
        assertEquals(1000, fallback.durationMs)

        assertTrue("speech.talk_cycle" in CatAnimationRegistry.registeredNames())
    }
}
