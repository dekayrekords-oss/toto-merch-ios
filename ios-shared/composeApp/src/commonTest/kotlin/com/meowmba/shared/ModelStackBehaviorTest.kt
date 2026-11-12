package com.meowmba.shared

import com.meowmba.shared.agent.ModelDownloader
import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.ModelResponse
import com.meowmba.shared.agent.OmniRoute
import com.meowmba.shared.agent.deleteRecursively
import com.meowmba.shared.agent.openWrite
import com.meowmba.shared.agent.use
import com.meowmba.shared.core.PlatformTemp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Downloader engine seam + Omniroute fallback behavior. */
class ModelStackBehaviorTest {

    @Test
    fun downloaderStreamsAndRenames() = runTest {
        val modelsDir = ModelFile(PlatformTemp.createDirectory("meow-dl"))
        val chunks = mutableListOf<ModelDownloader.DownloadProgress>()
        val downloader = ModelDownloader(
            modelsDir = modelsDir,
            engine = { url, dest, _, onProgress ->
                assertEquals("https://huggingface.co/x/y/resolve/main/model.task", url)
                dest.openWrite().use { out ->
                    val bytes = "0123456789".repeat(10).encodeToByteArray()
                    out.write(bytes, 0, bytes.size)
                    onProgress(bytes.size.toLong(), 1000L)
                }
            }
        )
        val file = downloader.download(
            "https://huggingface.co/x/y/resolve/main/model.task", "model.task",
        ) { chunks += it }
        assertTrue(file.isFile())
        assertTrue(file.length() == 100L)
        assertTrue(chunks.any { it.done })
        assertTrue(chunks.any { it.total == 1000L })
        assertTrue(modelsDir.listFiles().any { it.name == "model.task" })
        modelsDir.deleteRecursively()
    }

    @Test
    fun downloaderCleansPartOnFailure() = runTest {
        val modelsDir = ModelFile(PlatformTemp.createDirectory("meow-dl2"))
        val downloader = ModelDownloader(
            modelsDir = modelsDir,
            engine = { _, dest, _, _ ->
                dest.openWrite().use { it.write("partial".encodeToByteArray(), 0, 7) }
                throw IllegalStateException("network died")
            }
        )
        try {
            downloader.download("https://huggingface.co/x/y", "fail.task") { }
            error("should have thrown")
        } catch (expected: IllegalStateException) {
            assertEquals("network died", expected.message)
        }
        // Partial must be cleaned even though the engine wrote bytes first.
        assertTrue(modelsDir.listFiles().none { it.name.endsWith(".part") }, "part file left behind")
        modelsDir.deleteRecursively()
    }

    private class FakeProvider(private val behavior: suspend (String) -> ModelResponse) : com.meowmba.shared.agent.ModelProvider {
        override val name: String = "fake"
        override suspend fun generate(prompt: String): ModelResponse = behavior(prompt)
        override fun generateStreaming(prompt: String): Flow<ModelResponse> = flow { emit(generate(prompt)) }
    }

    private fun route(id: String, provider: com.meowmba.shared.agent.ModelProvider, tags: Set<OmniRoute.RouteTag>, priority: Int = 50) =
        OmniRoute.Route(id, id, provider, tags, priority)

    @Test
    fun omniRouteFallsThroughOnError() = runTest {
        val bad = FakeProvider { ModelResponse.Error("boom") }
        val good = FakeProvider { ModelResponse.Complete("hello from good") }
        val route = OmniRoute(
            routes = listOf(route("bad", bad, setOf(OmniRoute.RouteTag.CHAT), 10), route("good", good, setOf(OmniRoute.RouteTag.CHAT), 20)),
        )
        val response = route.generate("tell me a story")
        assertEquals("hello from good", (response as ModelResponse.Complete).text)
        assertEquals("good", route.lastRoute.value)
    }

    @Test
    fun omniRouteClassifiesFastAndCoding() = runTest {
        val route = OmniRoute(routes = emptyList())
        assertEquals(OmniRoute.RouteTag.FAST, route.classify("hi there"))
        assertEquals(OmniRoute.RouteTag.CODING, route.classify("write a function in kotlin"))
        assertEquals(OmniRoute.RouteTag.REASONING, route.classify("explain why the sky is blue step by step"))
    }

    @Test
    fun omniRouteEveryRouteFailed() = runTest {
        val bad = FakeProvider { ModelResponse.Error("nope") }
        val route = OmniRoute(routes = listOf(route("a", bad, setOf(OmniRoute.RouteTag.CHAT))))
        val response = route.generate("hello")
        val err = response as? ModelResponse.Error ?: error("expected Error")
        assertTrue(err.message.contains("Every route failed"))
    }
}
