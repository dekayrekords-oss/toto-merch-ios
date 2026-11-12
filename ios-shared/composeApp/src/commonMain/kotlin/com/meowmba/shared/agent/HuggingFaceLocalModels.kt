package com.meowmba.shared.agent

import com.meowmba.shared.bridge.DesktopBridgeProtocol.b64Decode
import com.meowmba.shared.bridge.DesktopBridgeProtocol.b64EncodeStd
import com.meowmba.shared.core.HttpUrl
import com.meowmba.shared.core.IsoDates
import com.meowmba.shared.voice.UrlCodec
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class PhoneModelSearchPage(
    val models: List<PhoneModelCandidate>,
    val nextCursor: String?,
    val scannedRepositories: Int,
    val notices: List<String>,
)

/** Small injectable response keeps metadata/error/security tests independent of Android. */
data class PhoneModelHttpResponse(val status: Int, val body: String, val link: String? = null)

/**
 * Explicit, read-only Hub browsing. Each page searches up to four indexes (eight
 * repositories each), fetches metadata for candidate repositories with four
 * concurrent requests, and stops after 45 seconds. It never downloads weights.
 * Ported from `core/agent/HuggingFaceLocalModels.kt` (java.time -> IsoDates,
 * Base64 URL -> DesktopBridgeProtocol, URI -> HttpUrl, org.json -> kotlinx).
 */
class HuggingFaceLocalModels(
    private val fetch: suspend (url: String, token: String?) -> PhoneModelHttpResponse = Companion::fetchHttp,
) {
    suspend fun search(
        query: String,
        device: PhoneModelDevice,
        token: String? = null,
        cursor: String? = null,
    ): Result<PhoneModelSearchPage> = withContext(Dispatchers.Default) {
        try {
            if (query.length > 200) throw CatalogException("Keep the model search under 200 characters.")
            val cleanToken = token?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanToken != null && (cleanToken.length > 512 || cleanToken.any { it.isWhitespace() || it.code < 32 }))
                throw CatalogException("The Hugging Face token has an invalid format.")
            val page = withTimeoutOrNull(45_000) { searchPage(query.trim(), device, cleanToken, cursor) }
                ?: throw CatalogException("Hugging Face search timed out. Try a narrower search.")
            Result.success(page)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Do not display server bodies or arbitrary transport exceptions: they
            // can contain request URLs, echoed headers, or other untrusted data.
            Result.failure(if (failure is CatalogException) failure
                else RuntimeException("Hugging Face search could not finish. Check your connection and retry."))
        }
    }

    private suspend fun searchPage(query: String, device: PhoneModelDevice, token: String?, cursor: String?): PhoneModelSearchPage = coroutineScope {
        val exactRepo = repositoryFromQuery(query)
        if (exactRepo != null && cursor == null) {
            val response = request(detailUrl(exactRepo), token)
            checkStatus(response.status)
            val metadata = Json.parseToJsonElement(response.body) as? JsonObject
                ?: throw CatalogException("Hugging Face returned invalid model metadata.")
            if (metadata.string("id") != exactRepo) throw CatalogException("Repository metadata did not match the requested model.")
            return@coroutineScope PhoneModelSearchPage(parseRepository(metadata, device), null, 1, standardNotices)
        }
        val urls = if (cursor == null) initialUrls(query) else decodeCursor(cursor, query)
        val indexResults = urls.map { url -> async {
            try {
                val response = request(url, token)
                checkStatus(response.status)
                val entries = (Json.parseToJsonElement(response.body) as? JsonArray)
                    ?: throw CatalogException("Hugging Face returned invalid model metadata.")
                IndexResult(entries.take(PAGE_SIZE).mapNotNull { it as? JsonObject }, nextLink(response.link, query), null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { IndexResult(emptyList(), null, error) }
        } }.awaitAll()
        if (indexResults.all { it.error != null }) {
            throw (indexResults.first().error as? CatalogException
                ?: CatalogException("Hugging Face search failed. Check your connection and retry."))
        }
        val repositories = indexResults.flatMap { it.entries }.mapNotNull { metadata ->
            val id = metadata.string("id")?.takeIf(::validRepository) ?: return@mapNotNull null
            if (metadata["siblings"].array().none { sibling ->
                (sibling as? JsonObject)?.string("rfilename")?.let(::possibleModelFile) == true
            }) return@mapNotNull null
            id
        }.distinct()
        val semaphore = Semaphore(4)
        val details = repositories.map { repository -> async {
            semaphore.withPermit {
                currentCoroutineContext().ensureActive()
                try {
                    val response = request(detailUrl(repository), token)
                    checkStatus(response.status)
                    val metadata = Json.parseToJsonElement(response.body) as? JsonObject
                        ?: throw CatalogException("Hugging Face returned invalid model metadata.")
                    require(metadata.string("id") == repository) { "Repository metadata did not match the requested model." }
                    DetailResult(parseRepository(metadata, device), false,
                        metadata["siblings"].array().count { (it as? JsonObject)?.string("rfilename")?.let(::possibleModelFile) == true } > MAX_FILES_PER_REPO)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { DetailResult(emptyList(), true, false) }
            }
        } }.awaitAll()
        val notices = standardNotices.toMutableList()
        val failedIndexes = indexResults.count { it.error != null }
        if (failedIndexes > 0) notices += "$failedIndexes search index request(s) failed; results are partial. Retry to refresh them."
        val failedDetails = details.count { it.failed }
        if (failedDetails > 0) notices += "Metadata for $failedDetails repositories was unavailable; those files are omitted."
        if (details.any { it.truncated }) notices += "A large repository was limited to $MAX_FILES_PER_REPO model files. Open its Hub page to inspect the rest."
        val nextUrls = indexResults.mapNotNull { it.nextUrl }.distinct()
        PhoneModelSearchPage(details.flatMap { it.models }.distinctBy { it.id },
            nextUrls.takeIf { it.isNotEmpty() }?.let { encodeCursor(it, query) },
            indexResults.flatMap { it.entries }.mapNotNull { it.string("id") }.distinct().size, notices)
    }

    private suspend fun request(url: String, token: String?): PhoneModelHttpResponse {
        require(isSafeApiUrl(url)) { "Invalid Hugging Face metadata address." }
        return fetch(url, token).also {
            require(it.body.encodeToByteArray().size <= MAX_METADATA_BYTES) { "Hugging Face metadata is too large." }
        }
    }

    private data class IndexResult(val entries: List<JsonObject>, val nextUrl: String?, val error: Exception?)
    private data class DetailResult(val models: List<PhoneModelCandidate>, val failed: Boolean, val truncated: Boolean)

    private class CatalogException(message: String) : Exception(message)

    companion object {
        private const val ORIGIN = "https://huggingface.co"
        private const val PAGE_SIZE = 8
        private const val MAX_FILES_PER_REPO = 64
        private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val repoPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}/[a-zA-Z0-9][a-zA-Z0-9._-]{0,95}")
        private val standardNotices = listOf(
            "Live, paginated Hub results; this is not an exhaustive or device-tested model list.",
            "Speed and RAM are estimates. Likes and downloads are community signals, not quality ratings.",
            "Newest on Hub is repository creation; release dates are shown only when explicitly supplied.",
        )
        private val client by lazy {
            HttpClient {
                followRedirects = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 15_000
                    requestTimeoutMillis = 15_000
                }
            }
        }

        /** No redirects: an API cursor or redirect can never forward a token to a CDN or custom host. */
        private suspend fun fetchHttp(url: String, token: String?): PhoneModelHttpResponse {
            require(isSafeApiUrl(url)) { "Invalid Hugging Face metadata address." }
            return client.prepareGet(url) {
                header(HttpHeaders.Accept, "application/json")
                if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
            }.execute { response ->
                val chunks = ArrayList<ByteArray>()
                var total = 0
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.readAvailable(buffer)
                    if (count < 0) break
                    check(total + count <= MAX_METADATA_BYTES) { "Hugging Face metadata is too large." }
                    if (count > 0) {
                        chunks.add(buffer.copyOfRange(0, count))
                        total += count
                    }
                }
                val body = ByteArray(total).also { out ->
                    var offset = 0
                    for (chunk in chunks) {
                        chunk.copyInto(out, offset)
                        offset += chunk.size
                    }
                }.decodeToString()
                PhoneModelHttpResponse(response.status.value, body, response.headers[HttpHeaders.Link])
            }
        }

        fun parseRepository(body: String, device: PhoneModelDevice): List<PhoneModelCandidate> {
            require(body.encodeToByteArray().size <= MAX_METADATA_BYTES) { "Hugging Face metadata is too large." }
            val root = json.parseToJsonElement(body) as? JsonObject ?: error("Expected repository metadata.")
            return parseRepository(root, device)
        }

        private fun parseRepository(root: JsonObject, device: PhoneModelDevice): List<PhoneModelCandidate> {
            val repository = root.string("id")?.takeIf(::validRepository) ?: return emptyList()
            if (root.string("disabled") == "true" || root.string("private") == "true") return emptyList()
            val sha = root.string("sha")?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) }
            val revision = sha ?: "main"
            val card = root["cardData"] as? JsonObject
            val config = root["config"] as? JsonObject
            val tags = root["tags"].array().mapNotNull { it.text() }
            val library = root.string("library_name") ?: card?.string("library_name")
            val pipeline = root.string("pipeline_tag") ?: card?.string("pipeline_tag")
            val context = "$repository ${tags.joinToString(" ")} ${library.orEmpty()}".lowercase()
            val knownNonChat = pipeline != null && pipeline !in setOf("text-generation", "text2text-generation", "image-text-to-text", "any-to-any", "conversational")
            val chatEvidence = !knownNonChat && (pipeline != null || library == "litert-lm" ||
                tags.any { it in setOf("chat", "text-generation", "conversational") } ||
                Regex("(?i)(instruct|gemma|qwen|llama|phi[-_0-9]|smollm|chat)").containsMatchIn(repository))
            if (!chatEvidence || context.contains("needle") || context.contains("function-router")) return emptyList()
            val metadataParameters = (root["safetensors"] as? JsonObject)?.positiveLong("total")
                ?: card?.positiveLong("parameters") ?: card?.positiveLong("num_parameters")
            val effective = Regex("(?i)(?:^|[-_])E([0-9]+(?:\\.[0-9]+)?)B(?:$|[-_])").find(repository)
                ?.groupValues?.get(1)?.toDoubleOrNull()?.times(1_000_000_000)?.toLong()
            val active = effective ?: Regex("(?i)(?:^|[-_])A([0-9]+(?:\\.[0-9]+)?)B(?:$|[-_])").find(repository)
                ?.groupValues?.get(1)?.toDoubleOrNull()?.times(1_000_000_000)?.toLong()
            val nameParameters = if (effective == null) parametersFromName(repository) else null
            val parameters = metadataParameters ?: nameParameters
            return root["siblings"].array().asSequence().mapNotNull { it as? JsonObject }
                .filter { it.string("rfilename")?.let(::possibleModelFile) == true }.take(MAX_FILES_PER_REPO)
                .mapNotNull { file ->
                    val path = file.string("rfilename")?.takeIf(::validFilePath) ?: return@mapNotNull null
                    val format = when (path.substringAfterLast('.').lowercase()) {
                        "litertlm" -> PhoneModelFormat.LITERT_LM
                        "task" -> PhoneModelFormat.MEDIAPIPE_TASK
                        "bin" -> if (context.contains("mediapipe") && !path.contains("pytorch", true) && !path.contains("adapter", true))
                            PhoneModelFormat.MEDIAPIPE_BIN else return@mapNotNull null
                        else -> return@mapNotNull null
                    }
                    val lfs = file["lfs"] as? JsonObject
                    val size = lfs?.positiveLong("size") ?: file.positiveLong("size")
                    val quantFromName = quantizationFromName(path)
                    val quantConfig = config?.get("quantization_config") as? JsonObject
                    val quantMetadata = card?.string("quantization")?.take(60)
                        ?: quantConfig?.positiveLong("bits")?.takeIf { it in 2..32 }?.let { "INT$it" }
                    val issue = when {
                        format == PhoneModelFormat.MEDIAPIPE_BIN ->
                            "Legacy binary format is not supported by this model browser. Use a complete .task or .litertlm bundle."
                        Regex("(?i)(?:^|[-_/.])(web|wasm|webgpu|intel|google|tensor|qualcomm|qnn|mediatek|exynos|npu|sm[0-9]+|qcs[0-9]+|mt[0-9]+)(?:$|[-_/.])").containsMatchIn(path) ->
                            "This variant targets web, desktop or a specific NPU; the app currently uses Android CPU/GPU."
                        Regex("(?i)(?:^|[-_/.])(adapter|lora|tokenizer|vision_encoder|audio_encoder)(?:$|[-_/.])").containsMatchIn(path) ->
                            "This is an auxiliary component, not a complete conversational model bundle."
                        sha == null -> "Immutable repository revision is missing; refresh metadata before downloading."
                        else -> null
                    }
                    val url = "$ORIGIN/$repository/resolve/$revision/" + path.split('/').joinToString("/") { encode(it) }
                    PhoneModelCandidate(
                        id = "hf:$repository@$revision/$path", repository = repository, revision = revision,
                        remoteFile = path, fileName = PhoneModelDiscovery.localFileName(repository, revision, path),
                        downloadUrl = url, sizeBytes = size, parameters = parameters,
                        quantization = quantFromName ?: quantMetadata,
                        createdAtMs = date(root.string("createdAt")), modifiedAtMs = date(root.string("lastModified")),
                        downloads = root.nonnegativeLong("downloads"), likes = root.nonnegativeLong("likes"),
                        license = card?.string("license") ?: tags.firstOrNull { it.startsWith("license:") }?.substringAfter(':'),
                        gated = root.string("gated") in setOf("true", "auto", "manual"), format = format,
                        assessment = PhoneModelDiscovery.assess(device, size, parameters ?: active, issue),
                        parametersSource = when { metadataParameters != null -> "Hub metadata (model level)"; nameParameters != null -> "Estimated from model name"; else -> null },
                        activeParameters = active,
                        quantizationSource = when { quantFromName != null -> "File name"; quantMetadata != null -> "Repository metadata"; else -> null },
                        releasedAtMs = date(card?.string("release_date")),
                        sha256 = lfs?.string("sha256")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) },
                        description = card?.string("description")?.take(500) ?: "${format.label} candidate from $repository. Inference on this phone is unverified.",
                    )
                }.toList()
        }

        fun parametersFromName(name: String): Long? = Regex("(?i)(?:^|[-_/\\s])([0-9]+(?:\\.[0-9]+)?)([bm])(?:$|[-_/.\\s])")
            .find(name)?.let { match ->
                (match.groupValues[1].toDoubleOrNull()?.times(if (match.groupValues[2].equals("b", true)) 1_000_000_000 else 1_000_000))
                    ?.takeIf { it > 0 && it < Long.MAX_VALUE }?.toLong()
            }

        fun quantizationFromName(name: String): String? = Regex("(?i)(?:^|[-_/.])(q[248](?:_(?:0|1|k(?:_[sml])?))?|int[248]|fp(?:16|32)|f(?:16|32)|bf16|afp4|sfp4)(?=$|[-_/.])")
            .find(name)?.groupValues?.get(1)?.uppercase()

        fun isSafeApiUrl(value: String): Boolean = runCatching {
            val uri = HttpUrl.parse(value)
            uri != null && uri.scheme == "https" && uri.host == "huggingface.co" && uri.port in setOf(null, 443) &&
                uri.rawUserInfo == null && uri.fragment == null && value.length <= 16_384 &&
                (uri.path == "/api/models" || (uri.path.startsWith("/api/models/") &&
                    validRepository(uri.path.removePrefix("/api/models/"))))
        }.getOrDefault(false)

        private fun validRepository(value: String): Boolean = value.matches(repoPattern) && !value.contains("..")
        private fun validFilePath(value: String): Boolean = value.length <= 512 && value.split('/').all {
            it.isNotBlank() && it != "." && it != ".." && !it.contains('\\') && it.none { char -> char.code < 32 }
        }
        private fun possibleModelFile(path: String): Boolean = path.substringAfterLast('.').lowercase() in setOf("task", "litertlm", "bin")
        private fun detailUrl(repository: String) = "$ORIGIN/api/models/$repository?blobs=true"
        private fun repositoryFromQuery(query: String): String? {
            if (validRepository(query)) return query
            return runCatching { HttpUrl.parse(query).takeIf { it != null && it.scheme == "https" && it.host == "huggingface.co" && it.rawUserInfo == null }
                ?.path?.trim('/')?.takeIf(::validRepository) }.getOrNull()
        }

        private fun initialUrls(query: String): List<String> {
            val common = "sort=downloads&direction=-1&limit=$PAGE_SIZE&full=true"
            val search = if (query.isBlank()) "" else "&search=${encode(query)}"
            return listOf("litert-lm", "mediapipe", "tflite").map { "$ORIGIN/api/models?filter=$it&$common$search" } +
                "$ORIGIN/api/models?$common&search=${encode(query.ifBlank { "litert" })}"
        }

        private fun encodeCursor(urls: List<String>, query: String): String =
            base64UrlEncode(JsonObject(mapOf("query" to JsonPrimitive(query), "urls" to JsonArray(urls.map(::JsonPrimitive)))).toString().encodeToByteArray())

        private fun decodeCursor(cursor: String, query: String): List<String> {
            require(cursor.length <= 32_768) { "Invalid search page. Start a new search." }
            val value = runCatching { json.parseToJsonElement(base64UrlDecode(cursor).decodeToString()) as? JsonObject }.getOrNull()
            require(value != null && value.string("query") == query) { "Search changed. Start a new search." }
            val urls = value["urls"].array().mapNotNull { it.text() }
            require(urls.size in 1..4 && urls.all { isSafeIndexUrl(it, query) }) { "Invalid search page. Start a new search." }
            return urls
        }

        private fun isSafeIndexUrl(url: String, query: String): Boolean = runCatching {
            val uri = HttpUrl.parse(url) ?: return false
            if (!isSafeApiUrl(url) || uri.path != "/api/models") return false
            val params = uri.queryParams()
            val expectedSearch = if (query.isBlank() && params["filter"] == null) "litert" else query
            params["search"].orEmpty() == expectedSearch && params["limit"]?.toIntOrNull()?.let { it in 1..PAGE_SIZE } == true &&
                params["full"] == "true" && (params["filter"] == null || params["filter"] in setOf("litert-lm", "mediapipe", "tflite"))
        }.getOrDefault(false)

        private fun nextLink(link: String?, query: String): String? = link?.split(',')?.firstNotNullOfOrNull { part ->
            if (!Regex("rel\\s*=\\s*\"?next\"?", RegexOption.IGNORE_CASE).containsMatchIn(part)) null
            else Regex("<([^>]+)>").find(part)?.groupValues?.get(1)?.takeIf { isSafeIndexUrl(it, query) }
        }

        private fun checkStatus(status: Int) {
            if (status in 200..299) return
            throw CatalogException(when (status) {
                401 -> "Hugging Face rejected the optional token. Remove it for public search or save a valid read token."
                403 -> "Hugging Face denied access. This model may require accepting its license on the Hub."
                404 -> "That Hugging Face repository was not found or is unavailable."
                429 -> "Hugging Face is rate limiting requests. Wait a moment and search again."
                in 300..399 -> "Hugging Face redirected its metadata request. Refresh and retry."
                else -> "Hugging Face returned HTTP $status. Try again later."
            })
        }

        private fun encode(value: String): String = UrlCodec.encodePath(value)
        private fun decode(value: String): String = UrlCodec.decode(value)
        private fun date(value: String?): Long? = IsoDates.parse(value)
        private fun base64UrlEncode(bytes: ByteArray): String =
            b64EncodeStd(bytes).trimEnd('=').replace('+', '-').replace('/', '_')
        private fun base64UrlDecode(value: String): ByteArray = b64Decode(value)

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
        private fun JsonObject.string(key: String): String? = this[key].text()
        private fun JsonObject.positiveLong(key: String): Long? = string(key)?.toLongOrNull()?.takeIf { it > 0 }
        private fun JsonObject.nonnegativeLong(key: String): Long? = string(key)?.toLongOrNull()?.takeIf { it >= 0 }
    }
}
