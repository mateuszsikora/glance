package com.glance.update

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Bounded HTTP fetches for the update manifest and APK.
 *
 * `HttpURLConnection` follows redirects only within the same protocol, and a self-hosted updater
 * may well redirect to object storage, so redirects are followed explicitly with a hop limit.
 */
internal object UpdateDownloader {

    fun fetchManifest(url: String): String? {
        return runCatching {
            open(url).use { connection ->
                connection.inputStream.use { input ->
                    readBoundedText(input, MAX_MANIFEST_BYTES)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to fetch update manifest from $url", error)
        }.getOrNull()
    }

    /** Downloads to [target] and returns the lowercase hex SHA-256 of what was written. */
    fun downloadApk(url: String, target: File): String? {
        return runCatching {
            open(url).use { connection ->
                val digest = MessageDigest.getInstance("SHA-256")
                target.outputStream().use { fileOut ->
                    DigestOutputStream(fileOut, digest).use { out ->
                        connection.inputStream.use { input ->
                            copyBounded(input, out, MAX_APK_BYTES)
                        }
                    }
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to download update from $url", error)
            target.delete()
        }.getOrNull()
    }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            when (val status = connection.responseCode) {
                in 200..299 -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) throw IOException("Redirect without Location")
                    val next = URL(current, location)
                    if (!isSupportedProtocol(next)) {
                        throw IOException("Unsupported redirect target: ${next.protocol}")
                    }
                    current = next
                }
                else -> {
                    connection.disconnect()
                    throw IOException("Unexpected HTTP status $status")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun isSupportedProtocol(url: URL): Boolean {
        return url.protocol.lowercase(Locale.US) in setOf("http", "https")
    }

    private fun readBoundedText(input: InputStream, limit: Long): String {
        val buffer = java.io.ByteArrayOutputStream()
        copyBounded(input, buffer, limit)
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) throw IOException("Response exceeds $limit bytes")
            output.write(buffer, 0, read)
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private const val TAG = "UpdateDownloader"
    private const val MAX_MANIFEST_BYTES = 64L * 1024L
    private const val MAX_APK_BYTES = 256L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_BYTES = 64 * 1024
}
