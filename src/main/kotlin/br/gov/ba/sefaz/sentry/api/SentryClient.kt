package br.gov.ba.sefaz.sentry.api

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

data class SentryIssue(
    val id: String,
    val shortId: String,
    val title: String,
    val culprit: String,
    val level: String,
    val count: String,
    val userCount: Int,
    val lastSeen: String,
    val permalink: String,
)

data class SentryFrame(
    val function: String,
    val module: String,
    val filename: String,
    val lineNo: Int,
    val inApp: Boolean,
)

class SentryApiException(message: String) : RuntimeException(message)

/**
 * Cliente REST minimo do Sentry (compativel com self-hosted).
 * Base: https://<host>/api/0  | Auth: Bearer <token>
 */
class SentryClient(baseUrl: String, private val token: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

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

    /** Valida URL/token/projeto; retorna o nome do projeto se ok. */
    fun testConnection(organization: String, project: String): String {
        val obj = JsonParser.parseString(get("/projects/$organization/$project/")).asJsonObject
        return obj.str("name").ifBlank { obj.str("slug") }
    }

    fun listIssues(
        organization: String,
        project: String,
        query: String,
        statsPeriod: String,
        limit: Int,
    ): List<SentryIssue> {
        val path = "/projects/$organization/$project/issues/" +
            "?query=${enc(query)}&statsPeriod=${enc(statsPeriod)}&limit=$limit"
        val arr = JsonParser.parseString(get(path)).asJsonArray
        return arr.map { it.asJsonObject.toIssue() }
    }

    /** JSON do ultimo evento do issue. */
    fun latestEvent(issueId: String): JsonObject =
        JsonParser.parseString(get("/issues/$issueId/events/latest/")).asJsonObject

    /** Frames do stacktrace do ultimo evento (ordenados: ponto do erro primeiro). */
    fun latestEventFrames(issueId: String): List<SentryFrame> {
        val event = latestEvent(issueId)
        val out = mutableListOf<SentryFrame>()
        // formato "entries" (API moderna)
        event.arr("entries")?.forEach { e ->
            val eo = e.asJsonObject
            if (eo.str("type") == "exception") {
                eo.obj("data")?.arr("values")?.forEach { v -> collectFrames(v.asJsonObject, out) }
            }
        }
        // fallback: formato "exception"
        if (out.isEmpty()) {
            event.obj("exception")?.arr("values")?.forEach { v -> collectFrames(v.asJsonObject, out) }
        }
        return out
    }

    private fun collectFrames(value: JsonObject, out: MutableList<SentryFrame>) {
        val frames = value.obj("stacktrace")?.arr("frames") ?: return
        // Sentry entrega oldest-first; invertendo, o frame do erro fica no topo
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

    private fun JsonObject.toIssue() = SentryIssue(
        id = str("id"),
        shortId = str("shortId"),
        title = str("title"),
        culprit = str("culprit"),
        level = str("level"),
        count = str("count"),
        userCount = optInt("userCount"),
        lastSeen = str("lastSeen"),
        permalink = str("permalink"),
    )

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

        private fun JsonElement?.orNull(): JsonElement? =
            if (this == null || this.isJsonNull) null else this

        private fun JsonObject.str(key: String): String =
            get(key).orNull()?.asString ?: ""

        private fun JsonObject.optInt(key: String): Int =
            get(key).orNull()?.let { runCatching { it.asInt }.getOrDefault(0) } ?: 0

        private fun JsonObject.arr(key: String): com.google.gson.JsonArray? =
            get(key).orNull()?.takeIf { it.isJsonArray }?.asJsonArray

        private fun JsonObject.obj(key: String): JsonObject? =
            get(key).orNull()?.takeIf { it.isJsonObject }?.asJsonObject
    }
}
