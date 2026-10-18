package me.ghoststat.sdk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Sends one batch; returns the HTTP status. Throws IOException when offline or timed out. */
fun interface Transport {
    @Throws(IOException::class)
    fun send(body: ByteArray, url: String): Int
}

/** The default: HttpURLConnection, no cookies (a fresh connection without a CookieHandler), 20 s timeouts. */
class HttpTransport(private val timeoutMs: Int = 20_000) : Transport {
    override fun send(body: ByteArray, url: String): Int {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val status = c.responseCode
            try { (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } } catch (_: IOException) {}
            return status
        } finally {
            c.disconnect()
        }
    }
}
