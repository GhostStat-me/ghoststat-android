package me.ghoststat.sdk

/** A tiny JSON writer — the SDK has no dependencies, and org.json is not on the plain JVM the core is tested on. */
internal class Json {
    private val sb = StringBuilder()
    /** Per open object/array: whether the next member needs a comma first. */
    private val needsComma = ArrayList<Boolean>()

    fun obj(block: Json.() -> Unit): Json { sb.append('{'); needsComma.add(false); block(); needsComma.removeAt(needsComma.size - 1); sb.append('}'); return this }
    fun arr(block: Json.() -> Unit): Json { sb.append('['); needsComma.add(false); block(); needsComma.removeAt(needsComma.size - 1); sb.append(']'); return this }

    private fun member() {
        if (needsComma.isNotEmpty()) {
            if (needsComma[needsComma.size - 1]) sb.append(',')
            needsComma[needsComma.size - 1] = true
        }
    }
    private fun key(k: String) { member(); string(k); sb.append(':') }

    fun field(k: String, v: String?) { if (v != null) { key(k); string(v) } }
    fun field(k: String, v: Long?) { if (v != null) { key(k); sb.append(v) } }
    fun field(k: String, v: Int?) { if (v != null) { key(k); sb.append(v) } }
    fun field(k: String, v: Double?) { if (v != null && v.isFinite()) { key(k); sb.append(num(v)) } }
    fun fieldObj(k: String, block: Json.() -> Unit) { key(k); obj(block) }
    fun fieldArr(k: String, block: Json.() -> Unit) { key(k); arr(block) }
    /** A string as the next element of the current array. */
    fun value(v: String) { member(); string(v) }
    /** An object as the next element of the current array. */
    fun item(block: Json.() -> Unit) { member(); obj(block) }

    private fun string(s: String) {
        sb.append('"')
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' || ch == ' ' || ch == ' ' -> sb.append("\\u").append(String.format("%04x", ch.code))
            else -> sb.append(ch)
        }
        sb.append('"')
    }

    private fun num(v: Double): String = if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    override fun toString(): String = sb.toString()
}
