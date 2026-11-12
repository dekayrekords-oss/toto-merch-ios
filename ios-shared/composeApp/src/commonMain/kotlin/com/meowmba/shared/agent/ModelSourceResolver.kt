package com.meowmba.shared.agent

import com.meowmba.shared.core.HttpUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a human-facing Hugging Face/GitHub page into one compatible model
 * file. Ported from `core/agent/ModelSourceResolver.kt`; org.json replaced
 * with kotlinx.serialization, java.net.URI with [HttpUrl]. The HTTP client
 * is injectable for tests (mock engine).
 */
object ModelSourceResolver {
    data class Resolved(val url: String, val fileName: String, val sizeBytes: Long? = null)
    private val extensions = listOf(".litertlm", ".task", ".cact")

    fun isDirect(input: String): Boolean = runCatching {
        val path = HttpUrl.parse(input)?.path?.lowercase() ?: return@runCatching false
        extensions.any { path.endsWith(it) }
    }.getOrDefault(false)

    suspend fun resolve(
        input: String,
        preferredName: String = "",
        client: HttpClient = HttpClient(),
    ): Result<Resolved> = runCatching {
        val raw = input.trim()
        val uri = HttpUrl.parse(raw)
            ?: throw IllegalArgumentException("Enter an http or https address")
        require(uri.scheme == "https" || uri.scheme == "http") { "Enter an http or https address" }
        if (isDirect(raw)) {
            val name = preferredName.ifBlank { uri.path.substringAfterLast('/') }
            require(ModelCatalog.isValidModelFileName(name)) { "Unsupported model file type" }
            val direct = if (uri.host.equals("github.com", true) && uri.path.contains("/blob/")) {
                raw.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/")
            } else raw
            return@runCatching Resolved(direct, name)
        }
        when (uri.host?.lowercase()) {
            "huggingface.co", "www.huggingface.co" -> resolveHuggingFace(uri, preferredName, client)
            "github.com", "www.github.com" -> resolveGitHub(uri, preferredName, client)
            else -> error("Use a direct model file, Hugging Face page, or GitHub repository")
        }
    }

    private suspend fun resolveHuggingFace(uri: HttpUrl, preferredName: String, client: HttpClient): Resolved {
        val p = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        val offset = if (p.firstOrNull() == "models") 1 else 0
        require(p.size >= offset + 2) { "Hugging Face address must include owner and model" }
        val repo = p.drop(offset).take(2).joinToString("/")
        val response = client.get("https://huggingface.co/api/models/$repo")
        check(response.status.isSuccess()) { "Hugging Face model page was not found" }
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val siblings = root["siblings"]?.jsonArray ?: kotlinx.serialization.json.buildJsonArray { }
        val candidates = siblings.mapNotNull { el ->
            val o = (el as? kotlinx.serialization.json.JsonObject) ?: return@mapNotNull null
            val name = o["rfilename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!ModelCatalog.isValidModelFileName(name.substringAfterLast('/'))) return@mapNotNull null
            val lfsSize = (o["lfs"] as? kotlinx.serialization.json.JsonObject)
                ?.get("size")?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
            Triple(name, lfsSize, score(name))
        }.filter { it.second == null || it.second!! <= MAX_MODEL_BYTES }
        val chosen = choose(candidates, preferredName) ?: error("No Android-compatible .task, .litertlm, or .cact model was found")
        val fileName = preferredName.ifBlank { chosen.first.substringAfterLast('/') }
        return Resolved("https://huggingface.co/$repo/resolve/main/${chosen.first}", fileName, chosen.second)
    }

    private suspend fun resolveGitHub(uri: HttpUrl, preferredName: String, client: HttpClient): Resolved {
        val p = uri.path.trim('/').split('/')
        require(p.size >= 2) { "GitHub address must include owner and repository" }
        val repo = p.take(2).joinToString("/")
        val release = client.get("https://api.github.com/repos/$repo/releases/latest")
        if (release.status.isSuccess()) {
            val root = Json.parseToJsonElement(release.bodyAsText()).jsonObject
            val assets = root["assets"]?.jsonArray ?: kotlinx.serialization.json.buildJsonArray { }
            val candidates = assets.mapNotNull { el ->
                val o = (el as? kotlinx.serialization.json.JsonObject) ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!ModelCatalog.isValidModelFileName(name)) return@mapNotNull null
                val size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
                val url = o["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                (Triple(name, size, score(name)) to url)
            }.filter { it.first.second == null || it.first.second!! <= MAX_MODEL_BYTES }
            val picked = candidates.sortedBy { it.first.third }.firstOrNull {
                preferredName.isBlank() || it.first.first == preferredName
            }
            if (picked != null) return Resolved(picked.second, preferredName.ifBlank { picked.first.first }, picked.first.second)
        }
        for (branch in listOf("main", "master")) {
            val tree = client.get("https://api.github.com/repos/$repo/git/trees/$branch?recursive=1")
            if (!tree.status.isSuccess()) continue
            val nodes = runCatching { Json.parseToJsonElement(tree.bodyAsText()).jsonObject["tree"]?.jsonArray }
                .getOrNull() ?: continue
            val candidates = nodes.mapNotNull { el ->
                val o = (el as? kotlinx.serialization.json.JsonObject) ?: return@mapNotNull null
                if (o["type"]?.jsonPrimitive?.contentOrNull != "blob") return@mapNotNull null
                val name = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!ModelCatalog.isValidModelFileName(name.substringAfterLast('/'))) return@mapNotNull null
                val size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
                Triple(name, size, score(name))
            }.filter { it.second == null || it.second!! <= MAX_MODEL_BYTES }
            val chosen = choose(candidates, preferredName) ?: continue
            val fileName = preferredName.ifBlank { chosen.first.substringAfterLast('/') }
            return Resolved("https://raw.githubusercontent.com/$repo/$branch/${chosen.first}", fileName, chosen.second)
        }
        error("No compatible model file was found in this GitHub repository or its latest release")
    }

    private fun choose(items: List<Triple<String, Long?, Int>>, preferred: String) =
        items.filter { preferred.isBlank() || it.first.substringAfterLast('/') == preferred }.minByOrNull { it.third }

    private fun score(name: String): Int = when {
        name.endsWith(".litertlm", true) -> 0
        name.endsWith(".task", true) -> 1
        else -> 2
    }

    private const val MAX_MODEL_BYTES = 11L * 1024 * 1024 * 1024
}
