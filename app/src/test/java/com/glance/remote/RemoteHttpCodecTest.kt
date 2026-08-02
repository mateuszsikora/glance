package com.glance.remote

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHttpCodecTest {
    @Test
    fun parsesBoundedFormRequest() {
        val raw = (
            "POST /save?ignored=true HTTP/1.1\r\n" +
                "Host: 192.168.1.2:8080\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 7\r\n\r\n" +
                "a=b%20c"
            ).toByteArray(StandardCharsets.US_ASCII)

        val request = RemoteHttpCodec.readRequest(
            BufferedInputStream(ByteArrayInputStream(raw))
        )

        assertEquals("POST", request.method)
        assertEquals("/save", request.path)
        assertEquals("192.168.1.2:8080", request.headers["host"])
        assertEquals("a=b%20c", request.body.toString(StandardCharsets.US_ASCII))
    }

    @Test(expected = RemoteHttpException::class)
    fun rejectsPostWithoutContentLength() {
        val raw = "POST /save HTTP/1.1\r\nHost: tablet\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)

        RemoteHttpCodec.readRequest(BufferedInputStream(ByteArrayInputStream(raw)))
    }

    @Test
    fun writesSecurityHeadersAndExactLength() {
        val bytes = ByteArrayOutputStream()
        RemoteHttpCodec.writeResponse(
            BufferedOutputStream(bytes),
            RemoteHttpResponse.html(200, "zażółć")
        )

        val response = bytes.toString(StandardCharsets.UTF_8.name())
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("Cache-Control: no-store\r\n"))
        assertTrue(response.contains("Content-Security-Policy:"))
        assertTrue(response.contains("Content-Length: 10\r\n"))
        assertTrue(response.endsWith("\r\n\r\nzażółć"))
    }
}
