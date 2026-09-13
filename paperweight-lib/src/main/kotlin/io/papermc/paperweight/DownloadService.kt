/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight

import io.papermc.paperweight.util.*
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.*
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.utils.DateUtils
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.HttpStatus
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class DownloadService : BuildService<DownloadService.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        val projectPath: DirectoryProperty

        /**
         * Maximum total number of parallel HTTP connections.
         * Configurable via the Gradle property `paperweight.download.maxConnections` (default: 24).
         */
        val maxConnections: Property<Int>

        /**
         * Maximum number of parallel HTTP connections per route.
         * Configurable via the Gradle property `paperweight.download.maxConnectionsPerRoute` (default: 8).
         */
        val maxConnectionsPerRoute: Property<Int>

        /**
         * Number of high-level download retries (with exponential backoff) on top of the HTTP client's own retries.
         * Configurable via the Gradle property `paperweight.download.retries` (default: 2).
         */
        val retries: Property<Int>

        /**
         * Mirror rules used to rewrite download URLs. Each rule has the format `fromBase=toBase`, multiple rules
         * are separated by `,`. The first rule whose base matches the URL prefix is applied.
         *
         * Example (BMCLAPI mirrors for Mojang hosts):
         * `https://piston-meta.mojang.com=https://bmclapi2.bangbang93.com,https://piston-data.mojang.com=https://bmclapi2.bangbang93.com`
         *
         * Configurable via the Gradle property `paperweight.download.mirrors`.
         */
        val mirrorRules: ListProperty<String>
    }

    private companion object {
        val LOGGER: Logger = Logging.getLogger(DownloadService::class.java)
    }

    private val maxTotalConnections: Int get() = parameters.maxConnections.getOrElse(24)
    private val maxPerRoute: Int get() = parameters.maxConnectionsPerRoute.getOrElse(8).coerceAtMost(maxTotalConnections)
    private val maxRetries: Int get() = parameters.retries.getOrElse(2).coerceAtLeast(0)

    // Created lazily so the configurable parameters are finalized before the pool is built.
    private val httpClient: CloseableHttpClient by lazy {
        HttpClientBuilder.create()
            .setRetryStrategy(DefaultHttpRequestRetryStrategy(2, TimeValue.ofSeconds(1)))
            .useSystemProperties()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMinutes(5))
                    .setResponseTimeout(Timeout.ofMinutes(5))
                    .build()
            )
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .useSystemProperties()
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(30))
                            .build()
                    )
                    .setMaxConnTotal(maxTotalConnections)
                    .setMaxConnPerRoute(maxPerRoute)
                    .build()
            )
            .build()
    }

    fun download(source: Any, target: Any, hash: Hash? = null) {
        val url = source.convertToUrl()
        val file = target.convertToPath()
        download(url, file, hash)
    }

    private fun download(source: URL, target: Path, hash: Hash?) {
        val url = applyMirrors(source)

        if (url.protocol == "file") {
            downloadFileUrl(url, target)
            return
        }

        withRetry(url, target) {
            downloadWithEtag(url, target)
            if (hash != null) {
                verifyHash(url, target, hash)
            }
        }
    }

    private fun verifyHash(source: URL, target: Path, hash: Hash) {
        val dlHash = target.hashFile(hash.algorithm).asHexString().lowercase(Locale.ENGLISH)
        if (hash.value == "" || dlHash == hash.valueLower) {
            return
        }
        LOGGER.warn(
            "{} hash of downloaded file '{}' does not match what was expected! (expected: '{}', got: '{}')",
            hash.algorithm.name,
            target,
            hash.valueLower,
            dlHash
        )
        throw HashMismatchException(hash, target, source, dlHash)
    }

    /**
     * Applies the configured mirror rules to the given URL. The first matching rule wins.
     */
    private fun applyMirrors(source: URL): URL {
        val rules = parameters.mirrorRules.orNull
        if (rules.isNullOrEmpty()) {
            return source
        }
        var result = source.toString()
        for (rule in rules) {
            val idx = rule.indexOf('=')
            if (idx <= 0 || idx == rule.length - 1) {
                LOGGER.warn("Ignoring invalid mirror rule: '{}'", rule)
                continue
            }
            val from = rule.substring(0, idx)
            val to = rule.substring(idx + 1)
            if (result.startsWith(from)) {
                val rewritten = to + result.substring(from.length)
                LOGGER.lifecycle("Using mirror for '{}': {} -> {}", from, source, rewritten)
                return URI.create(rewritten).toURL()
            }
        }
        return source
    }

    /**
     * Runs the given download block with a configurable number of retries and exponential backoff.
     * Only transient failures (I/O errors, HTTP 429/5xx, hash mismatches) are retried;
     * other client errors (e.g. 404) fail fast.
     */
    private fun <T> withRetry(source: URL, target: Path, block: () -> T): T {
        var lastError: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (!isRetryable(e) || attempt == maxRetries) {
                    break
                }
                val backoffMs = minOf(15_000L, 1_000L shl attempt)
                LOGGER.warn(
                    "Download attempt {}/{} for '{}' failed ({}), retrying in {} ms",
                    attempt + 1,
                    maxRetries + 1,
                    source,
                    e.message ?: e.javaClass.simpleName,
                    backoffMs
                )
                try {
                    Thread.sleep(backoffMs)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw PaperweightException("Failed to download file '$target' from '$source' after ${maxRetries + 1} attempts.", lastError)
    }

    private fun isRetryable(e: Exception): Boolean = when (e) {
        is HashMismatchException -> true
        is HttpDownloadException -> e.statusCode == 429 || e.statusCode in 500..599
        is IOException -> true
        else -> false
    }

    private fun downloadFileUrl(source: URL, target: Path) {
        var path = source.toString().replace("file://", "")
        if (source.host == "project") {
            path = path.replace("project", parameters.projectPath.path.absolutePathString())
        }
        Path.of(path).copyTo(target, overwrite = true)
    }

    private fun downloadWithEtag(source: URL, target: Path) {
        target.parent.createDirectories()

        val etagDir = target.resolveSibling("etags")
        etagDir.createDirectories()

        val etagFile = etagDir.resolve(target.name + ".etag")
        val etag = if (etagFile.exists()) etagFile.readText() else null

        val time = if (target.exists()) target.getLastModifiedTime().toInstant() else Instant.EPOCH

        val httpGet = HttpGet(source.toString())

        if (target.exists()) {
            if (time != Instant.EPOCH) {
                val value = DateTimeFormatter.RFC_1123_DATE_TIME.format(time.atZone(ZoneOffset.UTC))
                httpGet.setHeader("If-Modified-Since", value)
            }
            if (etag != null) {
                httpGet.setHeader("If-None-Match", etag)
            }
        }

        httpClient.execute(httpGet) { response ->
            val code = response.code
            if (code !in 200..299 && code != HttpStatus.SC_NOT_MODIFIED) {
                throw HttpDownloadException(code, source.toString(), response.reasonPhrase)
            }

            val lastModified = handleResponse(response, target)
            saveEtag(response, lastModified, target, etagFile)
        }
    }

    private fun handleResponse(response: ClassicHttpResponse, target: Path): Instant {
        val lastModified = DateUtils.parseStandardDate(response, "Last-Modified") ?: Instant.EPOCH
        if (response.code == HttpStatus.SC_NOT_MODIFIED) {
            return lastModified
        }

        val entity = response.entity ?: return lastModified
        target.outputStream().use { output ->
            entity.content.use { input ->
                input.copyTo(output)
            }
        }

        return lastModified
    }

    private fun saveEtag(response: ClassicHttpResponse, lastModified: Instant, target: Path, etagFile: Path) {
        if (lastModified != Instant.EPOCH) {
            target.setLastModifiedTime(FileTime.from(lastModified))
        }

        val header = response.getFirstHeader("ETag") ?: return
        val etag = header.value

        etagFile.writeText(etag)
    }

    override fun close() {
        httpClient.close()
    }
}

/**
 * Thrown when a download fails with a non-2xx HTTP status code. The status code is kept so the
 * retry logic can distinguish transient failures (429/5xx) from permanent ones (e.g. 404).
 */
class HttpDownloadException(
    val statusCode: Int,
    url: String,
    reason: String?
) : PaperweightException("Download failed, HTTP code: $statusCode; URL: $url; Reason: $reason")

/**
 * Thrown when the downloaded file's hash does not match the expected value. Always retried once
 * more by [DownloadService] since it usually indicates a corrupted download.
 */
class HashMismatchException(
    hash: Hash,
    target: Path,
    source: URL,
    actual: String
) : PaperweightException(
    "${hash.algorithm.name} hash of downloaded file '$target' does not match what was expected! (expected: '${hash.valueLower}', got: '$actual') (source: $source)"
)
