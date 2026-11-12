package com.meowmba.shared.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloads on-device model bundles into the app's PRIVATE storage
 * (`filesDir/models`) — the only location the app can both write to and load
 * from. Ported from `core/agent/ModelDownloader.kt`; `java.io.File` moved
 * behind the [ModelFile] expect/actual, byte engine injectable for tests.
 *
 * Downloads stream to a `.part` file and atomically rename on success, so a
 * failed attempt never leaves a corrupt "installed" model.
 */
class ModelDownloader(
    private val modelsDir: ModelFile,
    private val hfTokenProvider: () -> String? = { null },
    private val engine: suspend (url: String, dest: ModelFile, token: String?, onProgress: (Long, Long?) -> Unit) -> Unit =
        Companion::downloadWithKtor,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) {
    data class DownloadProgress(
        val received: Long = 0,
        val total: Long? = null,
        val done: Boolean = false,
        val error: String? = null
    ) {
        val fraction: Float?
            get() = total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
    }

    init {
        modelsDir.mkdirs()
    }

    /** The installed file for a catalog option, or null when absent/empty. */
    fun installedFile(option: LocalModelOption): ModelFile? =
        ModelFile(modelsDir.path + "/" + option.fileName).takeIf { it.isFile() && it.length() > 0 }

    fun installedFile(option: ImageModelOption): ModelFile? =
        ModelFile(modelsDir.path + "/" + option.fileName).takeIf { it.isFile() && it.length() > 0 }

    /** An installed CHAT model. Needle must never reach MediaPipe at startup. */
    fun firstInstalledOption(): LocalModelOption? =
        options.firstOrNull { it.runtime.canActivate && installedFile(it) != null }

    /** Every model bundle actually present in the models dir — catalog or custom. */
    fun listInstalled(): List<ModelFile> =
        modelsDir.listFiles()
            .filter { it.isFile() && it.length() > 0 &&
                (it.name.endsWith(".task") || it.name.endsWith(".litertlm") || it.name.endsWith(".cact")) }
            .sortedBy { it.name }

    private val options get() = ModelCatalog.options

    fun destinationFor(fileName: String): ModelFile {
        require(ModelDownloadPolicy.validFileName(fileName)) { "Use a simple model filename without folders or traversal." }
        return ModelFile(modelsDir.path + "/" + fileName)
    }

    fun delete(option: LocalModelOption): Boolean =
        destinationFor(option.fileName).delete()

    fun delete(option: ImageModelOption): Boolean =
        destinationFor(option.fileName).delete()

    /**
     * Download [url] to [fileName] in the models directory.
     * Throws on HTTP/network errors (caller maps to user-facing state).
     */
    suspend fun download(
        url: String,
        fileName: String,
        onProgress: (DownloadProgress) -> Unit
    ): ModelFile = withContext(dispatcher) {
        val safeUrl = ModelDownloadPolicy.validateUrl(url)
        val finalFile = destinationFor(fileName)
        val partFile = destinationFor("$fileName.part")
        if (partFile.exists()) partFile.delete()

        val token = ModelDownloadPolicy.tokenForUrl(safeUrl, hfTokenProvider())
        try {
            engine(safeUrl, partFile, token) { received, total ->
                onProgress(DownloadProgress(received, total))
            }
            check(partFile.isFile() && partFile.length() > 0) { "Download produced an empty file" }
            if (finalFile.exists()) finalFile.delete()
            check(partFile.renameTo(finalFile)) { "Could not finalize download" }
            onProgress(DownloadProgress(finalFile.length(), finalFile.length(), done = true))
            finalFile
        } catch (e: Exception) {
            partFile.delete()
            throw e
        }
    }

    companion object {
        private val client = HttpClient {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                // Model files are large: no overall request timeout, just
                // a stall guard on the socket.
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = 120_000
            }
        }

        /**
         * Follow at most eight redirects as fresh requests. Hugging Face serves
         * large files from signed CDN URLs; those requests must not carry the
         * user's Hub token. CleartextPolicy applies again to every redirect.
         */
        suspend fun downloadWithKtor(
            url: String,
            dest: ModelFile,
            token: String?,
            onProgress: (Long, Long?) -> Unit
        ) = downloadWithClient(client, url, dest, token, onProgress)

        /** Injected client must disable automatic redirects, as the production client does. */
        internal suspend fun downloadWithClient(
            downloadClient: HttpClient,
            url: String,
            dest: ModelFile,
            token: String?,
            onProgress: (Long, Long?) -> Unit,
        ) {
            var currentUrl = ModelDownloadPolicy.validateUrl(url)
            // A custom server cannot obtain Hub credentials by redirecting there.
            val hubToken = ModelDownloadPolicy.tokenForUrl(currentUrl, token)
            repeat(9) { hop ->
                currentCoroutineContext().ensureActive()
                val redirect = downloadClient.prepareGet(currentUrl) {
                    ModelDownloadPolicy.tokenForUrl(currentUrl, hubToken)?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }.execute { response ->
                    if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                        check(hop < 8) { "The model download redirected too many times." }
                        val location = response.headers[HttpHeaders.Location]
                            ?: throw IllegalStateException("The model server returned a redirect without an address.")
                        return@execute ModelDownloadPolicy.redirectTarget(currentUrl, location)
                    }
                    if (!response.status.isSuccess()) {
                        val hint = if ((response.status.value == 401 || response.status.value == 403) &&
                            ModelDownloadPolicy.tokenForUrl(currentUrl, "origin-check") != null) {
                            " (license-gated: add your Hugging Face token in Settings)"
                        } else ""
                        throw IllegalStateException("HTTP ${response.status.value}$hint")
                    }
                    val total = response.contentLength()
                    check(!response.headers[HttpHeaders.ContentType].orEmpty().contains("text/html", true)) {
                        "The download returned a web page, not a model. Check the model link or sign-in requirements."
                    }
                    val channel = response.bodyAsChannel()
                    dest.openWrite().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read < 0) break
                            if (read > 0) {
                                out.write(buffer, 0, read)
                                received += read
                                onProgress(received, total)
                            }
                        }
                        check(received > 0 && (total == null || total <= 0 || received == total)) {
                            "Model download was incomplete. Retry the download when online."
                        }
                    }
                    null
                }
                if (redirect == null) return
                currentUrl = redirect
            }
        }
    }
}
