package me.ghoststat.sdk

/**
 * Crash details (SDK 1.2, for the App Insights crash list): why the app stopped and where in the code.
 *
 * What is sent: the kind of stop, Android's exit reason and status, the exception's class name (never its message),
 * the signal of a native crash, and the stack of the thread that crashed (class.method, file, line; for native code
 * the library, the offset in it and its build ID). What is never sent: exception messages, an abort message, other
 * threads, registers, memory, open files or the log — Android's tombstone contains all of those, and the SDK reads
 * only the fields above out of it.
 *
 * Everything here is plain Kotlin (no Android types), so it is tested on the JVM with real Android output.
 */
data class CrashFrame(
    /** Java: "com.example.Foo.bar"; native: the function name when Android knew it. */
    val symbol: String? = null,
    /** Java: the source file ("Foo.kt", or "SourceFile" after R8). */
    val file: String? = null,
    val line: Int? = null,
    /** Native: the library or file name, e.g. "libfoo.so", "base.apk" (no directory). */
    val image: String? = null,
    /** Native: the offset in [image] (rel_pc), for symbolication with the unstripped library. */
    val offset: Long? = null,
    val buildId: String? = null,
    /** Native: the offset into [symbol]. */
    val symbolOffset: Long? = null,
    /** The app's own code, not Android's or Java's. */
    val inApp: Boolean = false,
)

data class CrashReport(
    /** "crash", "hang" (an ANR) or "killed" (stopped by Android while on screen: memory, CPU, failed start). */
    val kind: String,
    val atMs: Long,
    /** The version that crashed (read on the next launch, possibly after an update). */
    val appVersion: String? = null,
    val appBuild: String? = null,
    /** ApplicationExitInfo: reason, status (the signal for a native crash), importance, memory. */
    val exitReason: Int? = null,
    val exitStatus: Int? = null,
    val importance: Int? = null,
    val pssKb: Long? = null,
    val rssKb: Long? = null,
    /** Java: the class of the exception that reached the top, e.g. "java.lang.IllegalStateException". */
    val exceptionName: String? = null,
    /** Java: the classes of its causes, outermost first (the last is the root cause). */
    val causes: List<String> = emptyList(),
    /** Native: "SIGSEGV", "SEGV_MAPERR", and the fault address when there was one. */
    val signalName: String? = null,
    val signalCode: String? = null,
    val faultAddress: String? = null,
    /** ANR: what Android waited for, e.g. "Input dispatching timed out · waited 5001ms". */
    val detail: String? = null,
    /** The thread the frames are from: "main" for an ANR, the crashing thread otherwise. */
    val thread: String? = null,
    val frames: List<CrashFrame> = emptyList(),
    val framesTruncated: Boolean = false,
) {
    internal fun write(j: Json) {
        j.field("kind", kind); j.field("at", atMs)
        j.field("crash_version", appVersion); j.field("crash_build", appBuild)
        j.field("exit_reason", exitReason); j.field("exit_status", exitStatus); j.field("importance", importance)
        j.field("pss_kb", pssKb); j.field("rss_kb", rssKb)
        j.field("exception_name", exceptionName)
        if (causes.isNotEmpty()) j.fieldArr("causes") { causes.forEach { value(it) } }
        j.field("signal_name", signalName); j.field("signal_code", signalCode); j.field("fault_address", faultAddress)
        j.field("detail", detail); j.field("thread", thread)
        if (frames.isNotEmpty()) j.fieldArr("frames") {
            for (f in frames) item {
                field("symbol", f.symbol); field("file", f.file); field("line", f.line); field("image", f.image)
                field("offset", f.offset); field("build_id", f.buildId); field("symbol_offset", f.symbolOffset)
                if (f.inApp) field("in_app", 1)
            }
        }
        if (framesTruncated) j.field("frames_truncated", 1)
    }

    companion object {
        const val MAX_FRAMES = 64
        const val MAX_CAUSES = 5
    }
}

internal object CrashFrames {
    private val systemPrefixes = listOf(
        "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.", "dalvik.",
        "libcore.", "org.apache.", "org.json.", "org.xml.", "org.w3c.", "com.google.android.", "com.google.protobuf.",
    )

    /** A Java frame is the app's own unless its class is Java's, Kotlin's or Android's. */
    fun javaInApp(className: String): Boolean = systemPrefixes.none { className.startsWith(it) }

    /** At most [CrashReport.MAX_FRAMES]; whether some were left out. */
    fun <T> cap(list: List<T>): Pair<List<T>, Boolean> =
        if (list.size > CrashReport.MAX_FRAMES) list.take(CrashReport.MAX_FRAMES) to true else list to false
}

/** An uncaught Java/Kotlin exception, reduced to class names and frames. The message is never read. */
internal object ThrowableReport {
    fun from(t: Throwable, atMs: Long, version: String?, build: String?, threadName: String?): CrashReport {
        val chain = ArrayList<Throwable>()
        var cur: Throwable? = t
        while (cur != null && chain.size < CrashReport.MAX_CAUSES + 1 && chain.none { it === cur }) { chain.add(cur); cur = cur.cause }
        val root = chain.last()
        // The root cause's stack points at the line that went wrong; the outer one when the root has none.
        val source = if (root.stackTrace.isNotEmpty()) root else t
        val (frames, cut) = CrashFrames.cap(source.stackTrace.map { e ->
            CrashFrame(symbol = "${e.className}.${e.methodName}", file = e.fileName, line = e.lineNumber.takeIf { it >= 0 },
                inApp = CrashFrames.javaInApp(e.className))
        })
        return CrashReport(kind = "crash", atMs = atMs, appVersion = version, appBuild = build,
            exceptionName = t.javaClass.name, causes = chain.drop(1).map { it.javaClass.name },
            thread = threadName?.let { if (it == "main") it else "background" }, frames = frames, framesTruncated = cut)
    }
}

/** The text Android keeps for an ANR (ApplicationExitInfo trace, Android 11+): the main thread's Java stack. */
internal object AnrTrace {
    private val frameLine = Regex("""^\s+at (\S+?)\((.*)\)\s*$""")

    fun mainThreadFrames(text: String): Pair<List<CrashFrame>, Boolean> {
        val lines = text.lineSequence().iterator()
        var inMain = false
        val out = ArrayList<CrashFrame>()
        while (lines.hasNext()) {
            val l = lines.next()
            if (!inMain) { if (l.startsWith("\"main\" ")) inMain = true; continue }
            if (l.isBlank() || l.startsWith("\"")) break                // the next thread
            val m = frameLine.find(l) ?: continue                       // "| …", "- locked …", "native: #00 …"
            val sym = m.groupValues[1]
            val where = m.groupValues[2]
            val cls = sym.substringBeforeLast('.', sym)
            val (file, line) = when {
                where == "Native method" -> null to null
                ':' in where -> where.substringBeforeLast(':') to where.substringAfterLast(':').toIntOrNull()
                else -> where to null
            }
            out.add(CrashFrame(symbol = sym, file = file, line = line?.takeIf { it >= 0 }, inApp = CrashFrames.javaInApp(cls)))
        }
        return CrashFrames.cap(out)
    }

    /** "bg anr: Input dispatching timed out (b1f9597 me.app/.Main is not responding. Waited 5001ms for …)." (also
     *  "user request after error: …", seen when the person closed the frozen app) →
     *  "Input dispatching timed out · waited 5001ms". Only Android's own words; at most 160 characters. */
    fun detail(description: String?): String? {
        val d = description?.trim() ?: return null
        if (d.isEmpty()) return null
        // Android puts a label first: "bg anr:", "anr:", "user request after error:" — keep what follows the last one.
        val head = d.substringBefore('(').substringAfterLast(':').trim().trimEnd('.')
        val waited = Regex("""[Ww]aited (\d+)\s*ms""").find(d)?.groupValues?.get(1)
        return (if (waited != null) "$head · waited ${waited}ms" else head).take(160).ifEmpty { null }
    }
}

/**
 * Android's tombstone for a native crash (ApplicationExitInfo trace, Android 12+): a protobuf (AOSP
 * debuggerd/proto/tombstone.proto). Read with a minimal decoder; only these fields are used:
 *   Tombstone  5 pid · 6 tid · 10 signal_info · 16 threads (map<uint32, Thread>: entry 1 key, 2 value)
 *   Signal     1 number · 2 name · 4 code_name · 8 has_fault_address · 9 fault_address
 *   Thread     1 id · 2 name · 4 current_backtrace
 *   Frame      1 rel_pc · 4 function_name · 5 function_offset · 6 file_name · 8 build_id
 * (Field numbers checked against a real tombstone from an Android 16 emulator.) The tombstone also holds the log,
 * memory maps, registers, open files and the abort message: none of that is read.
 */
internal object Tombstone {
    class Parsed(val signalNumber: Int?, val signalName: String?, val signalCode: String?, val faultAddress: Long?,
                 val threadName: String?, val frames: List<CrashFrame>, val truncated: Boolean,
                 /** The crashing thread is the app's main thread (its id is the process id). */
                 val isMainThread: Boolean)

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var r = 0L
            while (true) {
                require(pos < end) { "truncated varint" }
                val x = b[pos++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
                require(shift < 70) { "bad varint" }
            }
        }
        /** (field, wire type) or null at the end. */
        fun tag(): Pair<Int, Int>? = if (!more()) null else varint().let { (it ushr 3).toInt() to (it and 7).toInt() }
        fun bytes(): Reader { val n = varint().toInt(); require(n >= 0 && pos + n <= end) { "bad length" }; val r = Reader(b, pos, pos + n); pos += n; return r }
        fun string(): String { val r = bytes(); return String(b, r.pos, r.end - r.pos, Charsets.UTF_8) }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw IllegalArgumentException("wire type $wire")
            }
            require(pos <= end) { "truncated" }
        }
    }

    fun parse(bytes: ByteArray): Parsed? = try { parseOrThrow(bytes) } catch (_: RuntimeException) { null }

    private fun parseOrThrow(bytes: ByteArray): Parsed {
        val r = Reader(bytes, 0, bytes.size)
        var tid: Long? = null
        var pid: Long? = null
        var sigNo: Int? = null; var sigName: String? = null; var sigCode: String? = null; var fault: Long? = null; var hasFault = false
        val threads = HashMap<Long, Reader>()
        while (true) {
            val (f, w) = r.tag() ?: break
            when {
                f == 5 && w == 0 -> pid = r.varint()
                f == 6 && w == 0 -> tid = r.varint()
                f == 10 && w == 2 -> {
                    val s = r.bytes()
                    while (true) {
                        val (sf, sw) = s.tag() ?: break
                        when {
                            sf == 1 && sw == 0 -> sigNo = s.varint().toInt()
                            sf == 2 && sw == 2 -> sigName = s.string()
                            sf == 4 && sw == 2 -> sigCode = s.string()
                            sf == 8 && sw == 0 -> hasFault = s.varint() != 0L
                            sf == 9 && sw == 0 -> fault = s.varint()
                            else -> s.skip(sw)
                        }
                    }
                }
                f == 16 && w == 2 -> {
                    val e = r.bytes()
                    var key: Long? = null; var value: Reader? = null
                    while (true) {
                        val (ef, ew) = e.tag() ?: break
                        when {
                            ef == 1 && ew == 0 -> key = e.varint()
                            ef == 2 && ew == 2 -> value = e.bytes()
                            else -> e.skip(ew)
                        }
                    }
                    if (key != null && value != null) threads[key] = value
                }
                else -> r.skip(w)
            }
        }
        val t = tid?.let { threads[it] }
        var name: String? = null
        val frames = ArrayList<CrashFrame>()
        var dropped = false
        if (t != null) {
            while (true) {
                val (f, w) = t.tag() ?: break
                when {
                    f == 2 && w == 2 -> name = t.string()
                    f == 4 && w == 2 -> frame(t.bytes())?.let { if (frames.size < CrashReport.MAX_FRAMES) frames.add(it) else dropped = true }
                    else -> t.skip(w)
                }
            }
        }
        return Parsed(sigNo, sigName, sigCode, if (hasFault) fault else null, name, frames, dropped, tid != null && tid == pid)
    }

    /** One frame; null for the runtime's own interpreter frames (libart.so), which say nothing about the app. */
    private fun frame(fr: Reader): CrashFrame? {
        var relPc: Long? = null; var fn: String? = null; var fnOff: Long? = null; var file: String? = null; var build: String? = null
        while (true) {
            val (f, w) = fr.tag() ?: break
            when {
                f == 1 && w == 0 -> relPc = fr.varint()
                f == 4 && w == 2 -> fn = fr.string()
                f == 5 && w == 0 -> fnOff = fr.varint()
                f == 6 && w == 2 -> file = fr.string()
                f == 8 && w == 2 -> build = fr.string()
                else -> fr.skip(w)
            }
        }
        val path = file ?: ""
        if (path.endsWith("/libart.so")) return null
        val image = path.substringAfterLast('/').ifEmpty { null }
        return CrashFrame(symbol = fn?.ifEmpty { null }?.take(300), image = image, offset = relPc, buildId = build?.ifEmpty { null },
            symbolOffset = fnOff, inApp = path.startsWith("/data/app/") || path.startsWith("/data/data/"))
    }
}

/**
 * The crash report the uncaught-exception handler leaves for the next launch, as a small text file (one field per
 * line, tab-separated), so it can be read back without a JSON parser. Only what [CrashReport] holds.
 */
internal object CrashFile {
    private fun clean(s: String?): String = (s ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun encode(r: CrashReport): String = buildString {
        append("gscrash\t1\n")
        append("at\t").append(r.atMs).append('\n')
        r.appVersion?.let { append("version\t").append(clean(it)).append('\n') }
        r.appBuild?.let { append("build\t").append(clean(it)).append('\n') }
        r.exceptionName?.let { append("exception\t").append(clean(it)).append('\n') }
        if (r.causes.isNotEmpty()) append("causes\t").append(r.causes.joinToString("\t") { clean(it) }).append('\n')
        r.thread?.let { append("thread\t").append(clean(it)).append('\n') }
        if (r.framesTruncated) append("truncated\t1\n")
        for (f in r.frames) append("frame\t").append(clean(f.symbol)).append('\t').append(clean(f.file)).append('\t')
            .append(f.line?.toString() ?: "").append('\t').append(if (f.inApp) "1" else "0").append('\n')
    }

    fun decode(text: String): CrashReport? {
        val lines = text.lines()
        if (lines.firstOrNull()?.startsWith("gscrash\t") != true) return null
        var at: Long? = null; var version: String? = null; var build: String? = null; var exception: String? = null
        var causes = emptyList<String>(); var thread: String? = null; var truncated = false
        val frames = ArrayList<CrashFrame>()
        for (l in lines.drop(1)) {
            val p = l.split('\t')
            when (p[0]) {
                "at" -> at = p.getOrNull(1)?.toLongOrNull()
                "version" -> version = p.getOrNull(1)?.ifEmpty { null }
                "build" -> build = p.getOrNull(1)?.ifEmpty { null }
                "exception" -> exception = p.getOrNull(1)?.ifEmpty { null }
                "causes" -> causes = p.drop(1).filter { it.isNotEmpty() }.take(CrashReport.MAX_CAUSES)
                "thread" -> thread = p.getOrNull(1)?.ifEmpty { null }
                "truncated" -> truncated = true
                "frame" -> if (frames.size < CrashReport.MAX_FRAMES && p.size >= 5)
                    frames.add(CrashFrame(symbol = p[1].ifEmpty { null }, file = p[2].ifEmpty { null }, line = p[3].toIntOrNull(), inApp = p[4] == "1"))
            }
        }
        return CrashReport(kind = "crash", atMs = at ?: return null, appVersion = version, appBuild = build, exceptionName = exception,
            causes = causes, thread = thread, frames = frames, framesTruncated = truncated)
    }
}
