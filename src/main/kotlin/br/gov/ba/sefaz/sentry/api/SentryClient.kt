package br.gov.ba.sefaz.sentry.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SentryFrame(
    val function: String,
    val module: String,
    val filename: String,
    val lineNo: Int,
    val inApp: Boolean,
)

/** Linha generica exibida na lista (issue, trace ou log). */
data class SentryRow(
    val label: String,        // texto na lista (ja com data formatada)
    val detailHtml: String,   // html do painel de detalhe
    val permalink: String,    // link "abrir no Sentry"
    val issueId: String?,     // != null => habilita stacktrace
)

object SentryFormat {
    private val OUT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    fun date(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            OUT.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
        } catch (e: Exception) {
            iso
        }
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

class SentryApiException(message: String) : RuntimeException(message)

/**
 * Cliente REST minimo do Sentry (compativel com self-hosted).
 * Base: https://<host>/api/0  | Auth: Bearer <token>
 * Usa endpoints org-level para suportar varios projetos de uma vez.
 */
class SentryClient(baseUrl: String, private val token: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val idCache = HashMap<String, String>()

    private fun get(path: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/api/0$path"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(40))
            .GET()
            .build()
        val response: HttpResponse<String> =
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            val body = response.body().orEmpty()
            throw SentryApiException("HTTP ${response.statusCode()} em $path: ${body.take(300)}")
        }
        return response.body()
    }

    /** Valida URL/token/projeto(s); retorna o nome dos projetos validados. */
    fun testConnection(organization: String, projects: List<String>): String =
        projects.joinToString(", ") { slug ->
            val obj = JsonParser.parseString(get("/projects/$organization/$slug/")).asJsonObject
            obj.str("name").ifBlank { obj.str("slug") }
        }

    fun projectNumericId(organization: String, project: String): String =
        idCache.getOrPut("$organization/$project") {
            JsonParser.parseString(get("/projects/$organization/$project/")).asJsonObject.str("id")
        }

    private fun projectIds(organization: String, projects: List<String>): List<String> =
        projects.map { projectNumericId(organization, it) }

    private fun StringBuilder.projectParams(ids: List<String>): StringBuilder {
        ids.forEach { append("&project=").append(enc(it)) }
        return this
    }

    /** ERROS (issues) de um ou mais projetos. sort: date | freq | new | user */
    fun listIssues(
        organization: String,
        projects: List<String>,
        query: String,
        statsPeriod: String,
        limit: Int,
        sort: String,
    ): List<SentryRow> {
        val sb = StringBuilder("/organizations/$organization/issues/")
            .append("?query=").append(enc(query))
            .append("&statsPeriod=").append(enc(statsPeriod))
            .append("&limit=").append(limit)
            .append("&sort=").append(enc(sort))
            .projectParams(projectIds(organization, projects))
        val arr = JsonParser.parseString(get(sb.toString())).asJsonArray
        return arr.map { it.asJsonObject.toIssueRow() }
    }

    /** TRACES (transactions) via API Discover/events. */
    fun listTraces(
        organization: String,
        projects: List<String>,
        query: String,
        statsPeriod: String,
        limit: Int,
    ): List<SentryRow> =
        events(
            organization, projects, "transactions",
            listOf("transaction", "transaction.duration", "timestamp", "trace"),
            query, statsPeriod, limit,
        ).map { o ->
            val t = o.str("transaction").ifBlank { "(transaction)" }
            val dur = o.num("transaction.duration")
            val ts = SentryFormat.date(o.str("timestamp"))
            SentryRow(
                label = "$t  (${dur}ms)  $ts",
                detailHtml = html(
                    "<h3>${SentryFormat.escape(t)}</h3>duracao: ${dur}ms<br/>" +
                        "trace: <code>${SentryFormat.escape(o.str("trace"))}</code><br/>quando: $ts"
                ),
                permalink = "",
                issueId = null,
            )
        }

    /** LOGS via API Discover/events (best-effort; nomes de campo variam por versao). */
    fun listLogs(
        organization: String,
        projects: List<String>,
        query: String,
        statsPeriod: String,
        limit: Int,
    ): List<SentryRow> =
        events(
            organization, projects, "ourlogs",
            listOf("timestamp", "message", "severity"),
            query, statsPeriod, limit,
        ).map { o ->
            val msg = o.str("message").ifBlank { o.str("body") }.ifBlank { "(log)" }
            val sev = o.str("severity")
            val ts = SentryFormat.date(o.str("timestamp"))
            SentryRow(
                label = "[${sev.ifBlank { "log" }}] $msg  $ts",
                detailHtml = html("<h3>${SentryFormat.escape(msg)}</h3>severidade: ${SentryFormat.escape(sev)}<br/>quando: $ts"),
                permalink = "",
                issueId = null,
            )
        }

    /** Frames do stacktrace do ultimo evento do issue (ordenados: ponto do erro primeiro). */
    fun latestEventFrames(issueId: String): List<SentryFrame> {
        val event = JsonParser.parseString(get("/issues/$issueId/events/latest/")).asJsonObject
        val out = mutableListOf<SentryFrame>()
        event.arr("entries")?.forEach { e ->
            val eo = e.asJsonObject
            if (eo.str("type") == "exception") {
                eo.obj("data")?.arr("values")?.forEach { v -> collectFrames(v.asJsonObject, out) }
            }
        }
        if (out.isEmpty()) {
            event.obj("exception")?.arr("values")?.forEach { v -> collectFrames(v.asJsonObject, out) }
        }
        return out
    }

    private fun events(
        organization: String,
        projects: List<String>,
        dataset: String,
        fields: List<String>,
        query: String,
        statsPeriod: String,
        limit: Int,
    ): List<JsonObject> {
        val sb = StringBuilder("/organizations/$organization/events/")
            .append("?dataset=").append(enc(dataset))
            .append("&statsPeriod=").append(enc(statsPeriod))
            .append("&per_page=").append(limit)
            .append("&sort=-timestamp")
            .projectParams(projectIds(organization, projects))
        fields.forEach { sb.append("&field=").append(enc(it)) }
        if (query.isNotBlank()) sb.append("&query=").append(enc(query))
        val data = JsonParser.parseString(get(sb.toString())).asJsonObject.arr("data") ?: JsonArray()
        return data.map { it.asJsonObject }
    }

    private fun collectFrames(value: JsonObject, out: MutableList<SentryFrame>) {
        val frames = value.obj("stacktrace")?.arr("frames") ?: return
        frames.reversed().forEach { f ->
            val fo = f.asJsonObject
            out.add(
                SentryFrame(
                    function = fo.str("function"),
                    module = fo.str("module"),
                    filename = fo.str("filename"),
                    lineNo = fo.optInt("lineNo"),
                    inApp = fo.get("inApp").orNull()?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false,
                )
            )
        }
    }

    private fun JsonObject.toIssueRow(): SentryRow {
        val title = str("title")
        val ts = SentryFormat.date(str("lastSeen"))
        val proj = obj("project")?.str("slug") ?: ""
        val detail = buildString {
            append("<h3>${SentryFormat.escape(title)}</h3>")
            append("<b>${SentryFormat.escape(str("shortId"))}</b> &middot; projeto: ${SentryFormat.escape(proj)} &middot; nivel: ${SentryFormat.escape(str("level"))}<br/>")
            append("eventos: ${SentryFormat.escape(str("count"))} &middot; usuarios: ${optInt("userCount")}<br/>")
            append("culprit: <code>${SentryFormat.escape(str("culprit"))}</code><br/>")
            append("ultimo evento: $ts<br/><br/>")
            val link = str("permalink")
            if (link.isNotBlank()) append("<a href='${SentryFormat.escape(link)}'>Abrir no Sentry &rarr;</a>")
        }
        val projTag = if (proj.isNotBlank()) "$proj " else ""
        return SentryRow(
            label = "$projTag[${str("level")}] ${str("shortId")}  $title  (${str("count")})  $ts",
            detailHtml = html(detail),
            permalink = str("permalink"),
            issueId = str("id"),
        )
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

        private fun html(body: String) =
            "<html><body style='font-family:sans-serif; font-size:11px; margin:6px'>$body</body></html>"

        private fun JsonElement?.orNull(): JsonElement? =
            if (this == null || this.isJsonNull) null else this

        private fun JsonObject.str(key: String): String =
            get(key).orNull()?.asString ?: ""

        private fun JsonObject.optInt(key: String): Int =
            get(key).orNull()?.let { runCatching { it.asInt }.getOrDefault(0) } ?: 0

        private fun JsonObject.num(key: String): Long =
            get(key).orNull()?.let { runCatching { it.asDouble.toLong() }.getOrDefault(0L) } ?: 0L

        private fun JsonObject.arr(key: String): JsonArray? =
            get(key).orNull()?.takeIf { it.isJsonArray }?.asJsonArray

        private fun JsonObject.obj(key: String): JsonObject? =
            get(key).orNull()?.takeIf { it.isJsonObject }?.asJsonObject
    }
}
