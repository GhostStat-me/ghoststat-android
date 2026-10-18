package me.ghoststat.sdk

import java.net.URI
import java.net.URLDecoder

/** How a tracker batches and retries. */
class Configuration(
    val siteKey: String,
    /** The API base, e.g. https://api.ghoststat.me. Batches go to <endpoint>/collect/batch. */
    val endpoint: String = "https://api.ghoststat.me",
    /** Flush (and, in the foreground, heartbeat) this often. 15 s, like the website's Live Online heartbeat. */
    val intervalMs: Long = 15_000,
    maxBatch: Int = 50,
    maxQueue: Int = 1000,
    maxAttempts: Int = 5,
    /** First retry delay; doubles each try, capped at a minute. */
    val retryBaseMs: Long = 1_000,
) {
    /** Items per request; the collector takes at most 50. */
    val maxBatch: Int = maxBatch.coerceIn(1, 50)
    /** Items kept while offline; the oldest go first beyond this. */
    val maxQueue: Int = maxOf(this.maxBatch, maxQueue)
    /** Tries per batch before it is dropped. */
    val maxAttempts: Int = maxOf(1, maxAttempts)

    val collectUrl: String get() = endpoint.trimEnd('/') + "/collect/batch"
}

/** A campaign carried in by a deep link or App Link: its `utm_*` query parameters. */
data class Campaign(
    val source: String? = null,
    val medium: String? = null,
    val name: String? = null,
    val term: String? = null,
    val content: String? = null,
) {
    companion object {
        /** The `utm_*` parameters of a link, or null when it has none. */
        fun fromUrl(url: String): Campaign? {
            val query = try { URI(url).rawQuery } catch (e: Exception) { url.substringAfter('?', "").substringBefore('#') } ?: return null
            val map = HashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val k = decode(pair.substringBefore('=')).lowercase()
                val v = decode(pair.substringAfter('=', "")).trim()
                if (k.startsWith("utm_") && v.isNotEmpty() && k !in map) map[k] = v.take(120)
            }
            val c = Campaign(map["utm_source"], map["utm_medium"], map["utm_campaign"], map["utm_term"], map["utm_content"])
            return if (c == Campaign()) null else c
        }

        /** Where a link came from, without its path or query: "https://example.com", or "myapp:" for a scheme. */
        fun source(url: String): String? {
            val u = try { URI(url) } catch (e: Exception) { return null }
            val scheme = u.scheme?.lowercase() ?: return null
            val host = u.host?.lowercase()
            return if (!host.isNullOrEmpty()) "$scheme://$host" else "$scheme:"
        }

        private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
    }
}

/** How this launch relates to the last one (App Insights: installs, updates, opens). */
data class LaunchInfo(val kind: Kind, val fromVersion: String? = null, val durationMs: Int? = null) {
    enum class Kind(val wire: String) { INSTALL("install"), UPDATE("update"), OPEN("open") }

    companion object {
        /** An app installed less than this long ago, with no remembered version, is a fresh install. */
        const val FRESH_INSTALL_WINDOW_MS: Long = 7L * 86_400_000

        /**
         * Install, update or open, from the running version, the remembered one and how long ago the app was
         * installed. Nothing remembered and installed long ago = the app was there before the SDK: an open, so
         * adding GhostStat does not show every existing user as a new install.
         */
        fun kind(current: String?, last: String?, installedAgoMs: Long?): Pair<Kind, String?> {
            if (last != null) return if (current != null && current != last) Kind.UPDATE to last else Kind.OPEN to null
            if (installedAgoMs != null && installedAgoMs >= FRESH_INSTALL_WINDOW_MS) return Kind.OPEN to null
            return Kind.INSTALL to null
        }
    }
}

/** One stability summary, reduced to counts (the details of each crash go in a [CrashReport]). */
data class DiagnosticSummary(
    val periodStartMs: Long? = null,
    val periodEndMs: Long? = null,
    /** The version the numbers are about (crashes are reported on the next launch, possibly after an update). */
    val appVersion: String? = null,
    val crashes: Int = 0,
    val hangs: Int = 0,
    val cpuExceptions: Int = 0,
    val diskWriteExceptions: Int = 0,
    val launchMedianMs: Int? = null,
    val resumeMedianMs: Int? = null,
)

/** The app that is sending. */
data class AppInfo(val packageName: String, val version: String?, val build: String?)

/** The device it runs on — the fields GhostStat shows, nothing that identifies the device. */
data class DeviceInfo(
    val osVersion: String,
    /** e.g. "Google Pixel 8". */
    val model: String,
    val deviceClass: DeviceClass,
    val screenWidthDp: Int? = null,
    val screenHeightDp: Int? = null,
    val density: Double? = null,
    val platform: String = "android",
) {
    enum class DeviceClass(val wire: String) { PHONE("phone"), TABLET("tablet"), WATCH("watch"), TV("tv"), DESKTOP("desktop") }
}

/** One thing waiting to be sent. */
data class QueuedItem(val sequence: Long, val kind: Kind, val timestampMs: Long, val data: ItemData) {
    enum class Kind(val wire: String) {
        /** A screen view, stored by GhostStat as a pageview under /screens/<name>. */
        SCREEN("screen"),
        /** A named event. */
        EVENT("event"),
        /** The foreground heartbeat: keeps "active now" fresh, writes no row. */
        PING("ping"),
        /** Once per process, when the app is first in the foreground: install, update or open. */
        APP_LAUNCH("app_launch"),
        /** Crash / ANR counts from Android's exit reasons, per app version. */
        APP_DIAGNOSTIC("app_diagnostic"),
        /** One crash, ANR or kill-while-on-screen with its reason and stack (SDK 1.2; see [CrashReport]). */
        APP_CRASH("app_crash"),
    }
}

/** The `data` of a batch item, in the collector's field names. */
data class ItemData(
    val name: String? = null,
    val props: Map<String, String>? = null,
    val value: Double? = null,
    /** For an event: the screen it happened on. */
    val screen: String? = null,
    /** For the first screen after a deep link: where the link came from. */
    val ref: String? = null,
    val campaign: Campaign? = null,
    val launch: LaunchInfo? = null,
    val diagnostic: DiagnosticSummary? = null,
    val crash: CrashReport? = null,
) {
    val isEmpty: Boolean get() = this == ItemData()

    internal fun write(j: Json) {
        j.field("name", name)
        props?.let { p -> j.fieldObj("props") { for ((k, v) in p.toSortedMap()) field(k, v) } }
        j.field("value", value)
        j.field("screen", screen)
        j.field("ref", ref)
        campaign?.let {
            j.field("utm_source", it.source); j.field("utm_medium", it.medium); j.field("utm_campaign", it.name)
            j.field("utm_term", it.term); j.field("utm_content", it.content)
        }
        launch?.let {
            j.field("kind", it.kind.wire); j.field("from_version", it.fromVersion); j.field("launch_ms", it.durationMs)
        }
        diagnostic?.let {
            j.field("period_start", it.periodStartMs); j.field("period_end", it.periodEndMs); j.field("app_version", it.appVersion)
            j.field("crashes", it.crashes); j.field("hangs", it.hangs); j.field("cpu_exceptions", it.cpuExceptions)
            j.field("disk_write_exceptions", it.diskWriteExceptions)
            j.field("launch_p50_ms", it.launchMedianMs); j.field("resume_p50_ms", it.resumeMedianMs)
        }
        crash?.write(j)
    }
}

/** POST /collect/batch, as GhostStat's collector reads it. */
internal object Envelope {
    fun encode(siteKey: String, app: AppInfo, device: DeviceInfo, launchId: String, items: List<QueuedItem>): String =
        Json().obj {
            field("site", siteKey)
            fieldObj("app") {
                field("bundle_id", app.packageName); field("version", app.version); field("build", app.build)
                field("platform", device.platform); field("os_version", device.osVersion); field("device_model", device.model)
                field("device_class", device.deviceClass.wire); field("launch_id", launchId)
                field("sw", device.screenWidthDp); field("sh", device.screenHeightDp); field("dpr", device.density)
            }
            fieldArr("batch") {
                for (it in items) item {
                    field("type", it.kind.wire)
                    field("ts", it.timestampMs)
                    if (!it.data.isEmpty) fieldObj("data") { it.data.write(this) }
                }
            }
        }.toString()
}
