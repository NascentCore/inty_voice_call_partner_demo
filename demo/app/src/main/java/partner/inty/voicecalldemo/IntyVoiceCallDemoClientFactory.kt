package partner.inty.voicecalldemo

import ai.sxwl.android.inty.voicecall.IntyVoiceCallClient
import ai.sxwl.android.inty.voicecall.VoiceCallWebSocketDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private val intyLiveChatJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
        coerceInputValues = true
    }

data class IntyVoiceCallDemoSession(
    val repository: LiveChatVoiceDemoRepository,
    /** 与 WebSocket 共用，可用于 `GET .../live-chat/status` 等 HTTP 调用 */
    val httpClient: HttpClient,
    private val voiceCallClient: IntyVoiceCallClient,
) {
    suspend fun dispose() {
        voiceCallClient.close()
        httpClient.close()
    }
}

fun createIntyVoiceCallDemoSession(bearerToken: String): IntyVoiceCallDemoSession {
    val httpClient =
        HttpClient(OkHttp) {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(intyLiveChatJson)
            }
            install(ContentNegotiation) { json(intyLiveChatJson) }
            defaultRequest { header("Authorization", "Bearer $bearerToken") }
        }
    val voiceCallClient = IntyVoiceCallClient(VoiceCallWebSocketDataSource(httpClient))
    val repository = LiveChatVoiceDemoRepository(voiceCallClient)
    return IntyVoiceCallDemoSession(repository, httpClient, voiceCallClient)
}
