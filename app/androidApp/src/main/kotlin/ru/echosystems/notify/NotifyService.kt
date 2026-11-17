package ru.echosystems.notify

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

fun showNotification(context: android.content.Context, title: String, body: String, style: String = "basic", id: Int = (System.nanoTime() and 0x7fffffff).toInt() + 2): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(NotificationChannel("messages", "Уведомления", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("connection", "Соединение", NotificationManager.IMPORTANCE_LOW))
    }
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, if (id == 1) "connection" else "messages") else Notification.Builder(context)
    builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
        .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .setAutoCancel(id != 1).setOngoing(id == 1)
    if (style == "long") builder.setStyle(Notification.BigTextStyle().bigText(body))
    if (style == "progress") builder.setProgress(100, 0, true)
    val result = builder.build()
    if (id != 1) runCatching { manager.notify(id, result) }
    return result
}

class NotifyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) { install(WebSockets) { pingIntervalMillis = 20000 } }
    private var connection: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, showNotification(this, "Notify", "Служба доставки активна", id = 1))
        connection?.cancel()
        val url = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val recipient = intent.getStringExtra("recipient") ?: return START_NOT_STICKY
        val key = intent.getStringExtra("key") ?: return START_NOT_STICKY
        connection = scope.launch {
            var retry = 1000L
            while (isActive) {
                try {
                    client.webSocket(urlString = url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/') + "/api/v1/stream/$recipient", request = { header("Authorization", "Bearer $key") }) {
                        retry = 1000L
                        for (frame in incoming) if (frame is Frame.Text) {
                            val event = Json.parseToJsonElement(frame.readText()).jsonObject
                            showNotification(this@NotifyService, event.getValue("title").jsonPrimitive.content,
                                event.getValue("body").jsonPrimitive.content, event["style"]?.jsonPrimitive?.content ?: "basic")
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Network changes trigger bounded reconnect backoff. */ }
                delay(retry); retry = (retry * 2).coerceAtMost(30000)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); client.close(); super.onDestroy() }
}
