package com.meowmba.shared

import com.meowmba.shared.voice.WakePhraseMatcher
import com.meowmba.shared.work.ProjectKind
import com.meowmba.shared.work.ProjectRequestParser
import com.meowmba.shared.work.ToolRequestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Ported from the original's `WorkSessionParserTest.kt` (9 tests, all ported).
 * Names are camelCase rather than the original's backticked sentences, matching
 * the rest of this suite; the bodies are unchanged apart from the assertion
 * library.
 *
 * `normalize` is the original's `WakePhraseMatcher.normalize`, already ported.
 */
class WorkSessionParserTest {

    private fun n(s: String) = WakePhraseMatcher.normalize(s)

    @Test
    fun startAProjectPicksKindAndName() {
        val website = ProjectRequestParser.parse(n("let's start a website project"))!!
        assertEquals(ProjectKind.WEBSITE, website.kind)
        assertEquals("Website", website.name)

        val photos = ProjectRequestParser.parse(n("start a photo project called vacation"))!!
        assertEquals(ProjectKind.PHOTO, photos.kind)
        assertEquals("Vacation", photos.name)

        val code = ProjectRequestParser.parse(n("begin a codebase called Meow app"))!!
        assertEquals(ProjectKind.CODE, code.kind)
        assertEquals("Meow App", code.name)

        val generic = ProjectRequestParser.parse(n("hey meow start a project"))!!
        assertEquals(ProjectKind.OTHER, generic.kind)
        assertEquals("Project", generic.name)
    }

    @Test
    fun createAndStartProjectBothCreateSavedWorkSessions() {
        assertEquals("Website", ProjectRequestParser.parse(n("create a project called Website"))!!.name)
        assertEquals(ProjectKind.WEBSITE, ProjectRequestParser.parse(n("create a new website project"))!!.kind)
        assertNull(ProjectRequestParser.parse(n("create a report for this project")))
        assertNull(ProjectRequestParser.parse(n("scaffold a project folder")))
        assertNull(ProjectRequestParser.parse(n("what is a project")))
        assertNull(ProjectRequestParser.parse(n("open spotify")))
    }

    @Test
    fun theAppsSpokenConfirmationCannotRecursivelyCreateProjects() {
        assertNull(ProjectRequestParser.parse(n("Started Website. Its plan, activity and files are together in Projects. I'm drafting a plan for you to review.")))
        assertNull(ProjectRequestParser.parse(n("Started Project 2")))
        assertNull(ProjectRequestParser.parse(n("Project setup couldn't finish. Any existing project record and files were kept.")))
    }

    @Test
    fun projectBugReportsNegationsAndProgressDiscussionAreNotCreationRequests() {
        listOf(
            "it keeps starting projects until i close the app",
            "do not start a project", "don't start a project", "stop making projects",
            "the project won't start", "restart the app to fix projects",
            "i already started a project", "beginning the project was difficult",
            "tell me how to start a project", "when should we begin our project",
            "you said start a project", "start working on this project",
        ).forEach { assertNull(ProjectRequestParser.parse(n(it)), it) }
    }

    @Test
    fun politeDirectNewProjectRequestsStillWork() {
        listOf("let's start a project", "let us begin a codebase", "i want to create a photo project",
            "could you please make a new website project", "new code project", "help me plan a project")
            .forEach { assertNotNull(ProjectRequestParser.parse(n(it)), it) }
    }

    @Test
    fun makeAToolAt7amIsAMusicPlayer() {
        val tool = ToolRequestParser.parse(n("make a tool automatic music player at 7 am"))!!
        assertEquals("play_music", tool.action)
        assertEquals("07:00", tool.timeOfDay)
        assertEquals("music", tool.launchTarget)
    }

    @Test
    fun makeAnAutomaticMusicPlayerAt7amWithoutSayingTool() {
        val tool = ToolRequestParser.parse(n("make an automatic music player at 7am"))!!
        assertEquals("play_music", tool.action)
        assertEquals("07:00", tool.timeOfDay)
    }

    @Test
    fun eveningAndNamedTimes() {
        assertEquals("19:00", ToolRequestParser.parseTime("remind me at 7 in the evening"))
        assertEquals("00:00", ToolRequestParser.parseTime("at midnight"))
        assertEquals("12:00", ToolRequestParser.parseTime("at noon"))
        assertEquals("07:30", ToolRequestParser.parseTime("at 7:30 am"))
    }

    @Test
    fun ordinaryChatIsNotATool() {
        assertNull(ToolRequestParser.parse(n("open the toolbox")))
        assertNull(ToolRequestParser.parse(n("how are you")))
        assertNotNull(ToolRequestParser.parse(n("create a tool called stretch at 3pm")))
    }

    /**
     * Port-only. The original formatted the time with the JVM-only
     * `"%02d:%02d".format(...)`; this port uses `padStart`. Every case in the
     * ported suite above uses a two-digit minute, so none of them can tell the
     * two implementations apart — a single-digit minute is the only input where
     * a `padStart` mistake is visible.
     */
    @Test
    fun singleDigitMinutesAreZeroPaddedNotLeftAlone() {
        assertEquals("07:05", ToolRequestParser.parseTime("at 7:05 am"))
        assertEquals("00:09", ToolRequestParser.parseTime("at 12:09 am"))
        assertEquals("19:00", ToolRequestParser.parseTime("at 7 pm"))
    }
}
