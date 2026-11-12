package com.meowmba.shared

import com.meowmba.shared.voice.SpeechQuiet
import com.meowmba.shared.voice.VoiceTurnGate
import com.meowmba.shared.voice.VoiceTurnGate.Decision.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lines from the Android Fold's chat on 2026-09-29 that were sent to the LLM before the gate. */
class VoiceTurnGateTest {
    private fun call(t: String, inConversation: Boolean = false, asked: Boolean = false) =
        VoiceTurnGate.decide(t, addressed = false, inConversation = inConversation, meowAskedQuestion = asked)

    @Test fun clickTagsCutOffMidTagAreNoise() {
        assertTrue(SpeechQuiet.isOnlyNonSpeech("(clicking) (clicking) (clicking) ("))
        assertEquals(DROP_NOISE, call("(clicking) (clicking) (cli"))
        assertEquals(DROP_NOISE, VoiceTurnGate.decide("[typing]", addressed = true, inConversation = true, meowAskedQuestion = false))
    }

    @Test fun reactionsInACallDoNotCallTheModel() {
        for (t in listOf("Oh no.", "Ooh.", "Great.", "No, no.", "Okay.", "Thank you.", "Nice dude. Really, yeah. Good."))
            assertEquals(DROP_REACTION, call(t, inConversation = true), t)
        assertEquals(ACCEPT, call("No.", inConversation = true, asked = true))
    }

    @Test fun aTappedMicIsAlwaysAddressed() {
        assertEquals(ACCEPT, VoiceTurnGate.decide("Thank you.", addressed = true, inConversation = false, meowAskedQuestion = false))
        assertEquals(ACCEPT, VoiceTurnGate.decide("I'm back home", addressed = true, inConversation = false, meowAskedQuestion = false))
    }

    @Test fun overheardTalkAfterASilenceIsIgnored() {
        for (t in listOf("mail to their physical mail.", "other will lead it by at least", "Two more", "Oh the cat was almost using its cat wheel"))
            assertEquals(DROP_AMBIENT, call(t), t)
    }

    @Test fun realRequestsGetThrough() {
        for (t in listOf("Play the last video on YouTube.", "What time does McDonald's first breakfast?", "Are you listening?", "I need to text Sam back tonight"))
            assertEquals(ACCEPT, call(t), t)
        assertEquals(ACCEPT, call("I said it feels cold outside", inConversation = true))
    }
}
