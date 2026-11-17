package ru.echosystems.notify

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme {
                var recipient by remember { mutableStateOf("phone") }
                var receiveKey by remember { mutableStateOf("") }
                var publishKey by remember { mutableStateOf("") }
                var title by remember { mutableStateOf("Notify") }
                var body by remember { mutableStateOf("Привет! Это уведомление.") }
                var style by remember { mutableStateOf("basic") }
                var status by remember { mutableStateOf("Не подключено") }
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val client = remember { HttpClient(CIO) { followRedirects = false } }
                DisposableEffect(Unit) { onDispose { client.close() } }
                fun valid(): Boolean = recipient.matches(Regex("[a-zA-Z0-9_-]{1,64}"))
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Notify", style = MaterialTheme.typography.headlineLarge)
                        Text("Удалённые и локальные уведомления")
                        Text(NotifyEndpoint.HTTPS, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(recipient, { recipient = it }, label = { Text("Получатель") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(receiveKey, { receiveKey = it }, label = { Text("Ключ получения") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (!valid() || receiveKey.length < 24) status = "Укажи ID и ключ от 24 символов"
                                else {
                                    val intent = Intent(this@MainActivity, NotifyService::class.java).putExtra("recipient", recipient).putExtra("key", receiveKey)
                                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                                    status = "Служба запущена; при обрыве соединение восстанавливается"
                                }
                            }) { Text("Подключить") }
                            OutlinedButton(onClick = { stopService(Intent(this@MainActivity, NotifyService::class.java)); status = "Отключено" }) { Text("Отключить") }
                        }
                        HorizontalDivider()
                        OutlinedTextField(title, { title = it.take(200) }, label = { Text("Заголовок") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(body, { body = it.take(4000) }, label = { Text("Текст") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("basic" to "Обычное", "long" to "Текст", "progress" to "Прогресс").forEach { (value, label) ->
                                FilterChip(selected = style == value, onClick = { style = value }, label = { Text(label) })
                            }
                        }
                        Button(enabled = title.isNotBlank(), onClick = { showNotification(this@MainActivity, title, body, style); status = "Локальное уведомление вызвано. Проверь разрешения Android." }) { Text("Показать локально") }
                        OutlinedButton(enabled = title.isNotBlank(), onClick = {
                            val alarm = getSystemService(android.app.AlarmManager::class.java)
                            val intent = Intent(this@MainActivity, LocalNotificationReceiver::class.java).putExtra("title", title).putExtra("body", body).putExtra("style", style)
                            val pending = android.app.PendingIntent.getBroadcast(this@MainActivity, (System.nanoTime() and 0x7fffffff).toInt(), intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                            alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60000, pending)
                            status = "Запланировано через минуту; Android может задержать вызов"
                        }) { Text("Локально через минуту") }
                        OutlinedTextField(publishKey, { publishKey = it }, label = { Text("Ключ отправки (для удалённых)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && title.isNotBlank(), onClick = {
                            if (!valid() || publishKey.length < 24) status = "Проверь ID и ключ отправки"
                            else scope.launch {
                                busy = true
                                try {
                                    val response = client.post(NotifyEndpoint.HTTPS + "/api/v1/notifications") {
                                        header(HttpHeaders.Authorization, "Bearer $publishKey")
                                        contentType(ContentType.Application.Json)
                                        setBody(buildJsonObject { put("recipient", recipient); put("title", title); put("body", body); put("style", style) }.toString())
                                    }
                                    status = when (response.status.value) { 202 -> "Передано в очередь соединения"; 409 -> "Получатель офлайн или очередь заполнена"; 401 -> "Неверный ключ отправки"; else -> "Ошибка: ${response.status.value}" }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { status = "Не удалось связаться с сервером" }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "Отправка…" else "Отправить через сервер") }
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text("Доставка работает, пока активна служба. После принудительной остановки открой приложение и подключись снова.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
