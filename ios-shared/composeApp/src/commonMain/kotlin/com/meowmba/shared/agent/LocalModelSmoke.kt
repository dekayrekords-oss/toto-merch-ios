package com.meowmba.shared.agent

import com.meowmba.shared.core.MemoryTextStorage
import com.meowmba.shared.memory.LongTermMemoryStore
import com.meowmba.shared.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Explicit DEBUG launch harness; never reads or changes the user's memories/chat. */
object LocalModelSmoke {
    fun run(report: (String) -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val disk = MemoryTextStorage()
                LongTermMemoryStore(disk).remember("My cat is named Pixel.")
                val restored = LongTermMemoryStore(disk)
                val engine = AssistantEngine(ProviderConfig(kind = ProviderKind.ON_DEVICE))
                val text = StringBuilder()
                engine.stream("What is my cat's name? Reply with its name only.", memories = restored.candidates()).collect {
                    when (it) {
                        is ModelResponse.Partial -> text.append(it.text)
                        is ModelResponse.Error -> text.append("ERROR: ").append(it.message)
                        else -> Unit
                    }
                }
                report(text.toString())
            } catch (e: Exception) { report("ERROR: " + e.message) }
        }
    }
}
