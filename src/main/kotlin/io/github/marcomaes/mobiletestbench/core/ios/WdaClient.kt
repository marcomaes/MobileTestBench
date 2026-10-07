package io.github.marcomaes.mobiletestbench.core.ios

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.Size
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal WebDriverAgent client: just what the viewer and inspector need.
 * WDA must already be running for the simulator (via Appium, mobilecli or xcodebuild).
 */
class WdaClient(baseUrl: String) {

    private val base = baseUrl.trimEnd('/')
    private val gson = Gson()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    @Volatile private var sessionId: String? = null

    fun isReachable(): Boolean = try {
        request("GET", "/status", null, Duration.ofSeconds(3)).first in 200..299
    } catch (e: Exception) {
        false
    }

    /** Window size in points. */
    fun windowSize(): Size = withSession { id ->
        val value = call("GET", "/session/$id/window/size").asJsonObject
        Size(value["width"].asDouble.toInt(), value["height"].asDouble.toInt())
    }

    /** XML page source of the foreground app. */
    fun source(): String = call("GET", "/source?format=xml", timeout = Duration.ofSeconds(60)).asString

    fun tap(x: Int, y: Int) = withSession { id ->
        call("POST", "/session/$id/actions", WdaPayloads.tap(x, y))
        Unit
    }

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long) = withSession { id ->
        call("POST", "/session/$id/actions", WdaPayloads.swipe(fromX, fromY, toX, toY, durationMs))
        Unit
    }

    fun longPress(x: Int, y: Int, durationMs: Long) = withSession { id ->
        call("POST", "/session/$id/actions", WdaPayloads.longPress(x, y, durationMs), timeout = holdTimeout(durationMs))
        Unit
    }

    fun doubleTap(x: Int, y: Int) = withSession { id ->
        call("POST", "/session/$id/actions", WdaPayloads.doubleTap(x, y))
        Unit
    }

    fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long) = withSession { id ->
        call("POST", "/session/$id/actions", WdaPayloads.dragWithHold(fromX, fromY, toX, toY, holdMs, durationMs), timeout = holdTimeout(holdMs + durationMs))
        Unit
    }

    private fun holdTimeout(ms: Long): Duration = Duration.ofMillis(ms) + Duration.ofSeconds(15)

    /** name: home, volumeUp, volumeDown */
    fun pressButton(name: String) = withSession { id ->
        call("POST", "/session/$id/wda/pressButton", mapOf("name" to name))
        Unit
    }

    fun typeText(text: String) = withSession { id ->
        call("POST", "/session/$id/wda/keys", mapOf("value" to listOf(text)))
        Unit
    }

    fun close() {
        val id = sessionId ?: return
        sessionId = null
        runCatching { request("DELETE", "/session/$id", null, Duration.ofSeconds(3)) }
    }

    private fun <T> withSession(block: (String) -> T): T {
        val id = sessionId ?: createSession()
        return try {
            block(id)
        } catch (e: WdaException) {
            if (!e.isInvalidSession) throw e
            block(createSession())
        }
    }

    @Synchronized
    private fun createSession(): String {
        val body = mapOf("capabilities" to mapOf("alwaysMatch" to emptyMap<String, Any>(), "firstMatch" to listOf(emptyMap<String, Any>())))
        val (status, text) = request("POST", "/session", gson.toJson(body), Duration.ofSeconds(30))
        if (status !in 200..299) throw WdaException("Could not create WDA session: HTTP $status $text", false)
        val json = JsonParser.parseString(text).asJsonObject
        val id = json["sessionId"]?.takeUnless { it.isJsonNull }?.asString
            ?: json["value"]?.asJsonObject?.get("sessionId")?.asString
            ?: throw WdaException("WDA did not return a session id: $text", false)
        sessionId = id
        return id
    }

    private fun call(method: String, path: String, body: Any? = null, timeout: Duration = Duration.ofSeconds(15)): JsonElement {
        val (status, text) = request(method, path, body?.let { if (it is String) it else gson.toJson(it) }, timeout)
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()
        val value = (json as? JsonObject)?.get("value")
        val error = (value as? JsonObject)?.get("error")?.asString
        if (status !in 200..299 || error != null) {
            val message = (value as? JsonObject)?.get("message")?.asString ?: text
            throw WdaException("WDA $method $path failed: HTTP $status ${error ?: ""} $message".trim(),
                error == "invalid session id" || status == 404 && path.startsWith("/session/"))
        }
        return value ?: JsonObject()
    }

    private fun request(method: String, path: String, body: String?, timeout: Duration): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
            .header("Content-Type", "application/json")
        val publisher = if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody()
        builder.method(method, publisher)
        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.ConnectException) {
            throw IOException("WebDriverAgent is not reachable at $base. Start WDA for this simulator first.", e)
        }
        return response.statusCode() to response.body()
    }
}

class WdaException(message: String, val isInvalidSession: Boolean) : IOException(message)

/** W3C action payloads, separated for unit testing. */
object WdaPayloads {
    fun tap(x: Int, y: Int): Map<String, Any> = pointer(
        listOf(
            mapOf("type" to "pointerMove", "duration" to 0, "x" to x, "y" to y),
            mapOf("type" to "pointerDown", "button" to 0),
            mapOf("type" to "pause", "duration" to 60),
            mapOf("type" to "pointerUp", "button" to 0),
        )
    )

    fun longPress(x: Int, y: Int, durationMs: Long): Map<String, Any> = pointer(
        listOf(
            mapOf("type" to "pointerMove", "duration" to 0, "x" to x, "y" to y),
            mapOf("type" to "pointerDown", "button" to 0),
            mapOf("type" to "pause", "duration" to durationMs),
            mapOf("type" to "pointerUp", "button" to 0),
        )
    )

    /** Two touches in one action list, so the gap between them is exact instead of two HTTP calls apart. */
    fun doubleTap(x: Int, y: Int): Map<String, Any> = pointer(
        listOf(
            mapOf("type" to "pointerMove", "duration" to 0, "x" to x, "y" to y),
            mapOf("type" to "pointerDown", "button" to 0),
            mapOf("type" to "pause", "duration" to 40),
            mapOf("type" to "pointerUp", "button" to 0),
            mapOf("type" to "pause", "duration" to 60),
            mapOf("type" to "pointerDown", "button" to 0),
            mapOf("type" to "pause", "duration" to 40),
            mapOf("type" to "pointerUp", "button" to 0),
        )
    )

    fun dragWithHold(fromX: Int, fromY: Int, toX: Int, toY: Int, holdMs: Long, durationMs: Long): Map<String, Any> = pointer(
        listOf(
            mapOf("type" to "pointerMove", "duration" to 0, "x" to fromX, "y" to fromY),
            mapOf("type" to "pointerDown", "button" to 0),
            mapOf("type" to "pause", "duration" to holdMs),
            mapOf("type" to "pointerMove", "duration" to durationMs.coerceAtLeast(50), "x" to toX, "y" to toY),
            mapOf("type" to "pointerUp", "button" to 0),
        )
    )

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Map<String, Any> = pointer(
        listOf(
            mapOf("type" to "pointerMove", "duration" to 0, "x" to fromX, "y" to fromY),
            mapOf("type" to "pointerDown", "button" to 0),
            mapOf("type" to "pause", "duration" to 50),
            mapOf("type" to "pointerMove", "duration" to durationMs.coerceAtLeast(50), "x" to toX, "y" to toY),
            mapOf("type" to "pointerUp", "button" to 0),
        )
    )

    private fun pointer(actions: List<Map<String, Any>>): Map<String, Any> = mapOf(
        "actions" to listOf(
            mapOf(
                "type" to "pointer",
                "id" to "finger1",
                "parameters" to mapOf("pointerType" to "touch"),
                "actions" to actions,
            )
        )
    )
}
