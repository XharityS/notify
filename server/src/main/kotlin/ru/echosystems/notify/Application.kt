package ru.echosystems.notify

import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080,
        host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val publishKey = requireNotNull(System.getenv("NOTIFY_PUBLISH_KEY")) { "Set NOTIFY_PUBLISH_KEY" }
    // Provision separate receive credentials per recipient: phone=secret,laptop=secret.
    val recipients = requireNotNull(System.getenv("NOTIFY_RECIPIENTS")) { "Set NOTIFY_RECIPIENTS" }
        .split(',').associate { val parts = it.split('=', limit = 2); require(parts.size == 2 && parts.all(String::isNotBlank)); parts[0] to parts[1] }
    require(publishKey.length >= 24 && recipients.values.all { it.length >= 24 }) { "Keys must contain at least 24 characters" }
    val clients = ConcurrentHashMap<String, Channel<String>>()
    fun authorized(actual: String?, expected: String) = MessageDigest.isEqual(
        (actual ?: "").toByteArray(), "Bearer $expected".toByteArray())
    install(WebSockets) { pingPeriod = 20.seconds; timeout = 30.seconds; maxFrameSize = 16384 }
    routing {
        get("/health") { call.respondText("ok") }
        post("/api/v1/notifications") {
            if (!authorized(call.request.headers[HttpHeaders.Authorization], publishKey)) {
                call.respond(HttpStatusCode.Unauthorized); return@post
            }
            val text = call.receiveChannel().let { channel ->
                val buffer = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(1024)
                while (true) {
                    val n = channel.readAvailable(bytes, 0, bytes.size)
                    if (n == -1) break
                    buffer.write(bytes, 0, n)
                    if (buffer.size() > 16384) { call.respond(HttpStatusCode.PayloadTooLarge); return@post }
                }
                buffer.toString("UTF-8")
            }
            val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            fun field(name: String) = (body?.get(name) as? JsonPrimitive)?.contentOrNull
            val recipient = field("recipient")
            val title = field("title")
            val message = field("body")
            val style = field("style") ?: "basic"
            if (recipient !in recipients || title.isNullOrBlank() || title.length > 200 || message == null || message.length > 4000 || style !in setOf("basic", "long", "progress")) {
                call.respond(HttpStatusCode.BadRequest, "Invalid recipient/title/body/style"); return@post
            }
            val event = buildJsonObject {
                put("id", java.util.UUID.randomUUID().toString()); put("title", title)
                put("body", message); put("style", style)
            }.toString()
            val queued = clients[recipient]?.trySend(event)?.isSuccess == true
            call.respondText("{\"queued\":$queued}", ContentType.Application.Json,
                if (queued) HttpStatusCode.Accepted else HttpStatusCode.Conflict)
        }
        webSocket("/api/v1/stream/{recipient}") {
            val recipient = call.parameters["recipient"]!!
            val key = recipients[recipient]
            if (key == null || !authorized(call.request.headers[HttpHeaders.Authorization], key)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized")); return@webSocket
            }
            val queue = Channel<String>(32)
            if (clients.putIfAbsent(recipient, queue) != null) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Already connected")); return@webSocket
            }
            val writer = launch { for (event in queue) send(Frame.Text(event)) }
            try { for (frame in incoming) { /* Delivery is server-to-client only. */ } }
            finally { clients.remove(recipient, queue); queue.close(); writer.cancel() }
        }
    }
}
