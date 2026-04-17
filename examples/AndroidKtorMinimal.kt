// 示例：在客户 Android App 中连接 Inty Live Chat WebSocket。
// 依赖：已按 INTY_LIVE_CHAT_PARTNER_DELIVERY.md「Android：接入 inty_voice_call 模块」引入 :inty_voice_call，并提供 Ktor OkHttp + WebSockets + ContentNegotiation + kotlinx.serialization。
// 将本文件内容合并到你的 Activity / ViewModel / Repository，勿直接作为独立模块编译。

package partner.example.intyvoice

import ai.sxwl.android.inty.voicecall.CallPacket
import ai.sxwl.android.inty.voicecall.IntyVoiceCallClient
import ai.sxwl.android.inty.voicecall.VoiceCallWebSocketDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json

/**
 * @param wssBase 与 INTY_BASE 同主机，scheme 为 wss，无尾斜杠，例如 "wss://inty-dev.example.com"
 * @param agentId 运营方提供的 agent_id
 * @param token 服务账户 JWT（与 HTTP Bearer 相同字符串）
 */
class IntyLiveChatMinimalSession(
    wssBase: String,
    agentId: String,
    token: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val httpClient =
        HttpClient(OkHttp) {
            install(WebSockets)
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            defaultRequest { header("Authorization", "Bearer $token") }
        }

    private val dataSource = VoiceCallWebSocketDataSource(httpClient)
    private val client = IntyVoiceCallClient(dataSource)

    // 仅使用 Header 鉴权时，URL 不要带 ?token=，减少泄漏面；语言参数可选。
    private val wsUrl: String =
        buildString {
            append(wssBase.trimEnd('/'))
            append("/api/v1/live-chat/")
            append(agentId)
        }

    fun start(onPacket: (CallPacket) -> Unit) {
        client
            .packets(wsUrl)
            .onEach { onPacket(it) }
            .launchIn(scope)
    }

    /** PCM 采样率须与 GET /api/v1/live-chat/status 的 send_sample_rate 一致（SDK 方法名含 16k 为历史命名）。 */
    suspend fun sendPcmChunk(pcm: ByteArray) {
        client.sendVoicePcm16kBase64(pcm)
    }

    suspend fun close() {
        client.close()
        httpClient.close()
    }
}

// 使用示例（伪代码，需在协程作用域内调用）：
// val session = IntyLiveChatMinimalSession("wss://...", agentId, token)
// session.start { packet -> /* 处理 session_info / audio_response / transcript / error 等 */ }
// scope.launch { session.sendPcmChunk(pcmBytes) }
