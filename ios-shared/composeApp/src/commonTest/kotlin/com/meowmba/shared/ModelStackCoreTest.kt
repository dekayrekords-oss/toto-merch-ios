package com.meowmba.shared

import com.meowmba.shared.agent.HuggingFaceLocalModels
import com.meowmba.shared.agent.ModelCatalog
import com.meowmba.shared.agent.ModelDownloadPolicy
import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.PhoneModelCandidate
import com.meowmba.shared.agent.PhoneModelDevice
import com.meowmba.shared.agent.PhoneModelDiscovery
import com.meowmba.shared.agent.PhoneModelFormat
import com.meowmba.shared.agent.PhoneModelSort
import com.meowmba.shared.agent.PhoneModelSpeed
import com.meowmba.shared.core.HttpUrl
import com.meowmba.shared.core.IsoDates
import com.meowmba.shared.security.CleartextPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Catalog, URL/policy, ISO dates, and Hub metadata parsing. */
class ModelStackCoreTest {

    // -- catalog ------------------------------------------------------------

    @Test
    fun catalogEntriesAreConsistent() {
        assertTrue(ModelCatalog.options.isNotEmpty())
        ModelCatalog.options.forEach {
            assertTrue(ModelCatalog.isValidModelFileName(it.fileName), "bad file name ${it.fileName}")
            assertTrue(it.downloadUrl.startsWith("https://huggingface.co/"), it.id)
            assertTrue(it.minRamGb > 0)
        }
        assertEquals("Gemma 4 E2B IT", ModelCatalog.options.first { it.id == "gemma-4-e2b" }.displayName)
        assertNotNull(ModelCatalog.optionForFile("qwen2.5-0.5b-instruct-q8.task"))
        assertNull(ModelCatalog.optionForFile("whatever.gguf"))
    }

    @Test
    fun isValidModelFileNameRejectsTraversal() {
        assertTrue(ModelCatalog.isValidModelFileName("model.task"))
        assertFalse(ModelCatalog.isValidModelFileName("../evil.task"))
        assertFalse(ModelCatalog.isValidModelFileName("a/b.task"))
        assertFalse(ModelCatalog.isValidModelFileName("model.gguf"))
    }

    // -- HttpUrl ------------------------------------------------------------

    @Test
    fun httpUrlParsesAndNormalizes() {
        val url = assertNotNull(HttpUrl.parse("https://huggingface.co/api/models/org/repo?full=true&limit=8"))
        assertEquals("https", url.scheme)
        assertEquals("huggingface.co", url.host)
        assertEquals("/api/models/org/repo", url.path)
        assertEquals(mapOf("full" to "true", "limit" to "8"), url.queryParams())
        assertNull(HttpUrl.parse("not a url"))
        assertNull(HttpUrl.parse("file:///tmp/x"))
    }

    @Test
    fun httpUrlResolvesRelative() {
        val base = assertNotNull(HttpUrl.parse("https://huggingface.co/api/models/org/repo"))
        assertEquals("https://huggingface.co/api/models/org/other", base.resolve("/api/models/org/other")?.toString())
        assertEquals("https://huggingface.co/api/models/org/x", base.resolve("x")?.toString())
        assertEquals("https://huggingface.co/api/models", base.resolve("..")?.toString())
    }

    @Test
    fun normalizePathCollapses() {
        assertEquals("/a/b", HttpUrl.normalizePath("/a/./b"))
        assertEquals("/a", HttpUrl.normalizePath("/a/b/.."))
        assertEquals("/", HttpUrl.normalizePath("/.."))
    }

    // -- CleartextPolicy ----------------------------------------------------

    @Test
    fun cleartextPolicyAllowsPrivateHttp() {
        assertNull(CleartextPolicy.denyReason("http://192.168.1.5:11434"))
        assertNull(CleartextPolicy.denyReason("http://localhost:8080"))
        assertNull(CleartextPolicy.denyReason("http://10.0.0.3"))
        assertNull(CleartextPolicy.denyReason("http://my-robot.local"))
        assertNull(CleartextPolicy.denyReason("https://api.openai.com"))
        assertNotNull(CleartextPolicy.denyReason("http://example.com"))
        assertNotNull(CleartextPolicy.denyReason("ftp://x"))
    }

    // -- IsoDates -----------------------------------------------------------

    @Test
    fun isoDatesParseUtcAndOffsets() {
        assertEquals(0L, IsoDates.parse("1970-01-01T00:00:00Z"))
        assertEquals(1_000L, IsoDates.parse("1970-01-01T00:00:01Z"))
        assertEquals(0L, IsoDates.parse("1970-01-01T00:00:00+00:00"))
        // 2026-07-12T03:04:05Z
        val utc = assertNotNull(IsoDates.parse("2026-07-12T03:04:05Z"))
        // +02:00 -> one hour before UTC
        assertEquals(utc - 3_600_000L, IsoDates.parse("2026-07-12T04:04:05+02:00"))
        assertNull(IsoDates.parse("2026-13-01T00:00:00Z"))
        assertNull(IsoDates.parse("not a date"))
        assertEquals(utc / 1000 * 1000 + 0, IsoDates.parse("2026-07-12T03:04:05.000Z"))
    }

    // -- ModelDownloadPolicy -------------------------------------------------

    @Test
    fun tokenOnlyForHubOrigin() {
        assertEquals("tok", ModelDownloadPolicy.tokenForUrl("https://huggingface.co/x/y", "tok"))
        assertNull(ModelDownloadPolicy.tokenForUrl("https://cdn.hf.co/x", "tok"))
        assertNull(ModelDownloadPolicy.tokenForUrl("http://huggingface.co/x", "tok"))
        assertNull(ModelDownloadPolicy.tokenForUrl("https://huggingface.co/x", " bad token "))
    }

    @Test
    fun validateUrlRejectsBadInput() {
        ModelDownloadPolicy.validateUrl("https://huggingface.co/org/repo/resolve/main/model.task")
        assertFailsWith<IllegalArgumentException> { ModelDownloadPolicy.validateUrl("not a url") }
        assertFailsWith<IllegalArgumentException> { ModelDownloadPolicy.validateUrl("http://example.com/x") }
        assertFailsWith<IllegalArgumentException> { ModelDownloadPolicy.validateUrl("https://user:pass@host/x") }
    }

    @Test
    fun redirectRulesEnforceHttps() {
        assertEquals(
            "https://cdn.example.com/file",
            ModelDownloadPolicy.redirectTarget("https://huggingface.co/x/y", "https://cdn.example.com/file"),
        )
        assertFailsWith<IllegalArgumentException> {
            ModelDownloadPolicy.redirectTarget("https://huggingface.co/x/y", "http://evil.example.com/file")
        }
    }

    // -- PhoneModelDiscovery -------------------------------------------------

    private val richDevice = PhoneModelDevice(
        ramBytes = 12L * 1_073_741_824, freeBytes = 40L * 1_073_741_824,
        arm64 = true, lowRam = false,
    )

    @Test
    fun assessmentAcceptsFit() {
        val a = PhoneModelDiscovery.assess(richDevice, 2L * 1_073_741_824, 3_000_000_000L)
        assertTrue(a.eligible, a.reason)
        assertEquals(PhoneModelSpeed.BALANCED, a.speed)
    }

    @Test
    fun assessmentRejectsOversize() {
        val a = PhoneModelDiscovery.assess(richDevice, 20L * 1_073_741_824, null)
        assertFalse(a.eligible)
        assertTrue(a.reason.contains("11 GiB"))
    }

    @Test
    fun filterAndSortWorks() {
        val models = listOf(
            candidate("repo/a", size = 4_000_000_000, likes = 5),
            candidate("repo/b", size = 1_000_000_000, likes = 9),
            candidate("repo/c", size = 500_000_000, likes = 1),
        )
        val bySize = PhoneModelDiscovery.filterAndSort(models, sort = PhoneModelSort.SIZE)
        assertEquals(listOf("repo/c", "repo/b", "repo/a"), bySize.map { it.repository })
        val byLikes = PhoneModelDiscovery.filterAndSort(models, sort = PhoneModelSort.LIKES)
        assertEquals(listOf("repo/b", "repo/a", "repo/c"), byLikes.map { it.repository })
    }

    @Test
    fun localFileNameIsStable() {
        val a = PhoneModelDiscovery.localFileName("org/repo", "abc123", "model.task")
        val b = PhoneModelDiscovery.localFileName("org/repo", "abc123", "model.task")
        assertEquals(a, b)
        assertTrue(a.endsWith(".task"))
    }

    @Test
    fun formatBytesWorks() {
        assertEquals("1.00 GiB", PhoneModelDiscovery.formatBytes(1_073_741_824))
        assertEquals("512.0 MiB", PhoneModelDiscovery.formatBytes(536_870_912))
    }

    // -- HuggingFaceLocalModels metadata parse --------------------------------

    @Test
    fun parseRepositoryBuildsCandidates() {
        val body = """{
            "id":"litert-community/Qwen2.5-0.5B-Instruct",
            "sha":"0123456789abcdef0123456789abcdef01234567",
            "createdAt":"2026-01-01T00:00:00Z",
            "lastModified":"2026-06-01T00:00:00Z",
            "downloads":12345,"likes":67,
            "gated":false,
            "library_name":"litert-lm","pipeline_tag":"text-generation",
            "safetensors":{"total":494000000},
            "siblings":[
              {"rfilename":"Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task","lfs":{"size":640000000,"sha256":"${"a".repeat(64)}"},"size":640000000},
              {"rfilename":"tokenizer.json","size":100},
              {"rfilename":"README.md","size":500}
            ],
            "tags":["chat","text-generation","license:apache-2.0"],
            "cardData":{"license":"apache-2.0"}
        }"""
        val models = HuggingFaceLocalModels.parseRepository(body, richDevice)
        assertEquals(1, models.size)
        val model = models.first()
        assertEquals("litert-community/Qwen2.5-0.5B-Instruct", model.repository)
        assertTrue(model.assessment.eligible, model.assessment.reason)
        assertEquals(PhoneModelFormat.MEDIAPIPE_TASK, model.format)
        assertEquals(494_000_000, model.parameters)
        assertEquals("Q8", model.quantization)
        assertFalse(model.gated)
        assertTrue(model.downloads == 12345L)
        assertNotNull(model.createdAtMs)
        assertEquals("a".repeat(64), model.sha256)
    }

    @Test
    fun parseRepositorySkipsAuxFilesAndNonChat() {
        val body = """{
            "id":"org/embeddings-model","sha":"${"0".repeat(40)}","createdAt":"2026-01-01T00:00:00Z",
            "lastModified":"2026-01-01T00:00:00Z","downloads":1,"likes":1,"gated":false,
            "library_name":"sentence-transformers","pipeline_tag":"feature-extraction",
            "siblings":[{"rfilename":"model.task","lfs":{"size":1000000},"size":1000000}],
            "tags":["sentence-transformers"]
        }"""
        assertEquals(0, HuggingFaceLocalModels.parseRepository(body, richDevice).size)
    }

    @Test
    fun isSafeApiUrlEnforcesOrigin() {
        assertTrue(HuggingFaceLocalModels.isSafeApiUrl("https://huggingface.co/api/models"))
        assertTrue(HuggingFaceLocalModels.isSafeApiUrl("https://huggingface.co/api/models/org/repo?blobs=true"))
        assertFalse(HuggingFaceLocalModels.isSafeApiUrl("https://evil.com/api/models"))
        assertFalse(HuggingFaceLocalModels.isSafeApiUrl("http://huggingface.co/api/models"))
        assertFalse(HuggingFaceLocalModels.isSafeApiUrl("https://huggingface.co/../api/models"))
    }

    private fun candidate(repository: String, size: Long, likes: Long) =
        com.meowmba.shared.agent.PhoneModelCandidate(
            id = "hf:$repository", repository = repository, revision = "main", remoteFile = "m.task",
            fileName = "m.task", downloadUrl = "https://huggingface.co/$repository", sizeBytes = size,
            parameters = null, quantization = null, createdAtMs = null, modifiedAtMs = null,
            downloads = null, likes = likes, license = null, gated = false,
            format = com.meowmba.shared.agent.PhoneModelFormat.MEDIAPIPE_TASK,
            assessment = PhoneModelDiscovery.assess(richDevice, size, null),
        )
}
