package com.meowmba.shared

import com.meowmba.shared.agent.SelfStatusParser
import com.meowmba.shared.agent.SelfStatusParser.Question
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelfStatusParserTest {
    @Test
    fun recognizesModelQuestions() {
        listOf(
            "what model are you using",
            "What model are you using?",
            "which model are you running",
            "what's your model",
            "hey, what model is this?",
            "what model just answered",
        ).forEach { assertEquals(Question.MODEL, SelfStatusParser.parse(it)) }
    }

    @Test
    fun recognizesRuntimeLocationQuestions() {
        listOf(
            "are you running locally",
            "are you local or cloud",
            "what are you running on",
            "are you on device",
        ).forEach { assertEquals(Question.WHERE, SelfStatusParser.parse(it)) }
    }

    @Test
    fun leavesUnrelatedQuestionsAlone() {
        listOf(
            "what model of car should I buy",
            "download a new model",
            "what's the weather",
            "are you there",
            "who are you",
            "what can you do",
        ).forEach { assertNull(SelfStatusParser.parse(it)) }
    }
}