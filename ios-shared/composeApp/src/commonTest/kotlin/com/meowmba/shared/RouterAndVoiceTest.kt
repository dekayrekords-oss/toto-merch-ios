package com.meowmba.shared

import com.meowmba.shared.agent.runtime.AgentRouter
import com.meowmba.shared.agent.runtime.AvatarFormRequest
import com.meowmba.shared.agent.runtime.AvatarInventionRequest
import com.meowmba.shared.agent.runtime.CleanupSpeech
import com.meowmba.shared.agent.runtime.GreetingAnswers
import com.meowmba.shared.agent.runtime.ClockAnswers
import com.meowmba.shared.agent.runtime.RobotMotion
import com.meowmba.shared.agent.runtime.TurnContext
import com.meowmba.shared.voice.WakePhraseMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterAndVoiceTest {

    @Test
    fun wakePhraseMatchesWithRemainder() {
        val m = WakePhraseMatcher.match("hey meow open spotify")
        assertNotNull(m)
        assertEquals("hey meow", m.phrase)
        assertEquals("open spotify", m.remainder)
        assertNull(WakePhraseMatcher.match("I am meowing at the moon"))
        assertNull(WakePhraseMatcher.match(""))
    }

    @Test
    fun formAndInventionRequests() {
        assertEquals("dog", AvatarFormRequest.parse("be a dog"))
        assertEquals("none", AvatarFormRequest.parse("go back to normal"))
        assertNull(AvatarFormRequest.parse("be happy"))
        assertEquals("fishing", AvatarInventionRequest.parse("let's go fishing")?.activity)
        assertNotNull(AvatarInventionRequest.parse("invent an animation of you juggling"))
    }

    @Test
    fun routerRoutesCoreIntents() {
        fun tool(text: String): String? = AgentRouter.route(text)?.toolId

        assertEquals("avatar.form", tool("become a cat"))
        assertEquals("avatar.invent", tool("invent an animation of you juggling"))
        assertEquals("brain.models", tool("list your models"))
        assertEquals("file.write", tool("create a file called notes.txt"))
        assertEquals("file.read", tool("read the file report.pdf"))
        assertEquals("file.delete", tool("delete file old.png"))
        assertEquals("project.create", tool("create a new project called house"))
        assertEquals("memory.save", tool("start a project called garage"))
        assertEquals("memory.search", tool("search my memory for tea"))
        assertEquals("web.search", tool("search the web for cats"))
        assertEquals("calendar.create_event", tool("add an event called dentist"))
        assertEquals("phone.launch_app", tool("open spotify"))
        assertEquals("phone.take_photo", tool("take a picture"))
        assertEquals("phone.compose_sms", tool("text mom saying I'm late"))
        assertEquals("phone.dial_call", tool("call the pharmacy"))
        assertEquals("phone.set_alarm", tool("set an alarm for 7am"))
        assertEquals("phone.set_timer", tool("set a timer for 10 minutes"))
        assertEquals("phone.cleanup", tool("clean up my screenshots"))
        assertEquals("phone.open_url", tool("open the website example.com"))
        assertEquals("email.send", tool("email someone@example.com that says hi"))
        assertEquals("browser.navigate", tool("navigate to example.com"))
        assertEquals("media.generate_image", tool("generate an image of a sunset"))
        assertEquals("avatar.animate", tool("do a trick"))
        assertEquals("robot.move", tool("spin around"))
        assertEquals("phone_control", AgentRouter.route("tap the login button")?.agentId)
        // Deep-link wins over web search
        assertEquals("phone.open_url", tool("search the beatles on spotify"))
        // Nothing matches plain conversation
        assertNull(AgentRouter.route("how was your day"))
    }

    @Test
    fun cleanupSpeechParsesKinds() {
        assertEquals("photos", CleanupSpeech.kindOf("clean up my screenshots and photos"))
        assertEquals("videos", CleanupSpeech.kindOf("delete old videos"))
        assertEquals("all", CleanupSpeech.kindOf("clean up the phone"))
        assertTrue(CleanupSpeech.isConfirm("yes do it"))
    }

    @Test
    fun greetingsRotateAndGate() {
        GreetingAnswers.resetVariantCounter(0)
        assertTrue(GreetingAnswers.isGreeting(WakePhraseMatcher.normalize("hey meow what's up")))
        assertTrue(GreetingAnswers.isGreeting("what's up"))
        // A bare carrier word is a wake word, not a check-in; stripping it
        // leaves nothing, so it is NOT treated as a greeting by itself.
        assertTrue(!GreetingAnswers.isGreeting("yo"))
        assertTrue(!GreetingAnswers.isGreeting("what's up with my calendar today"))
        assertTrue(GreetingAnswers.reply("amy lee").contains("Amy"))
    }

    @Test
    fun clockAnswersAreDeterministic() {
        val now = 1_700_000_000_000L
        assertEquals("It's " + com.meowmba.shared.memory.DateText.timeLine(now) + ".",
            ClockAnswers.answer("what time is it", now))
        assertEquals("Today is " + com.meowmba.shared.memory.DateText.weekdayMonthDay(now) + ".",
            ClockAnswers.answer("what day is it", now))
        assertTrue(!ClockAnswers.isClockQuestion("what time is it in new york"))
    }

    @Test
    fun robotMotionPhraseResolution() {
        assertEquals(RobotMotion.SPIN, RobotMotion.fromPhrase("spin around", includeFaceOwned = false))
        assertEquals(RobotMotion.HEADLIGHT_OFF, RobotMotion.fromPhrase("turn off the headlights"))
        assertEquals(RobotMotion.NOD, RobotMotion.fromPhrase("nod your head"))
        assertNull(RobotMotion.fromPhrase("nod your head", includeFaceOwned = false))
        // "spin" is motor-owned -- no face can spin -- so the router's fast path
        // still reaches it. Only the face-owned motions drop out above.
        assertEquals(RobotMotion.SPIN, RobotMotion.fromPhrase("spin", includeFaceOwned = false))
    }

    @Test
    fun turnContextScopesAndBinds() {
        val ctx = TurnContext()
        ctx.scope(1)
        ctx.add("User", "hello")
        ctx.add("Meow", "hi there")
        val history = ctx.history()
        assertNotNull(history)
        assertTrue("User: hello" in history)
        assertTrue(TurnContext.dependent("yes do it"))
        assertTrue(!TurnContext.dependent("what is the weather"))
    }
}
