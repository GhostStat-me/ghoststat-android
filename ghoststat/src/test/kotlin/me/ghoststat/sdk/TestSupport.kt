package me.ghoststat.sdk

import java.io.IOException

/** Records every batch; answers with the statuses (or offline) it is given, then 200. */
class MockTransport(vararg replies: Reply) : Transport {
    sealed class Reply { data class Status(val code: Int) : Reply(); object Offline : Reply() }
    val bodies = ArrayList<String>()
    val urls = ArrayList<String>()
    private val replies = ArrayDeque(replies.toList())

    override fun send(body: ByteArray, url: String): Int {
        bodies.add(String(body, Charsets.UTF_8)); urls.add(url)
        return when (val r = replies.removeFirstOrNull() ?: Reply.Status(200)) {
            is Reply.Status -> r.code
            Reply.Offline -> throw IOException("offline")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun items(): List<Map<String, Any?>> = bodies.flatMap { (MiniJson.parse(it) as Map<String, Any?>)["batch"] as List<Map<String, Any?>> }
}

/** A clock the test moves by hand. */
class TestClock(var now: Long = 1_790_000_000_000L) { fun advance(ms: Long) { now += ms } }

/** A launch history the test controls. */
class MockHistory(var stored: String? = null, var installedAgo: Long? = 60_000) : LaunchHistory {
    var reads = 0
    override fun lastVersion(): String? { reads++; return stored }
    override fun remember(version: String) { stored = version }
    override fun installedAgoMs(nowMs: Long): Long? = installedAgo
}

/** Just enough JSON parsing to read what the SDK sends (numbers come back as Long or Double). */
object MiniJson {
    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "trailing data at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            ws()
            val c = s[i]
            if (c == '{') return obj()
            if (c == '[') return arr()
            if (c == '"') return str()
            if (s.startsWith("true", i)) { i += 4; return true }
            if (s.startsWith("false", i)) { i += 5; return false }
            if (s.startsWith("null", i)) { i += 4; return null }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            require(t.isNotEmpty()) { "unexpected '$c' at $st" }
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i++] == ':')
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i++] == '}')
                return m
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i++] == ']')
                return l
            }
        }

        private fun str(): String {
            require(s[i++] == '"')
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                if (c == '"') return b.toString()
                if (c != '\\') { b.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            }
        }
    }
}
