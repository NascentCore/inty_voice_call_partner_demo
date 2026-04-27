package partner.inty.voicecalldemo

import ai.sxwl.android.inty.voicecall.CallPacket
import ai.sxwl.android.inty.voicecall.IntyVoiceCallClient
import ai.sxwl.android.inty.voicecall.IntyVoiceCallUrls
import ai.sxwl.android.inty.voicecall.VoiceCallConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class LiveChatVoiceDemoRepository(
    private val voiceCallClient: IntyVoiceCallClient,
) {
    fun liveChatPackets(
        wssBase: String,
        agentId: String,
        token: String,
        speechLanguageCode: String? = null,
        responseLanguageName: String? = null,
        agentStartsConversation: Boolean = true,
    ): Flow<CallPacket> {
        val url =
            IntyVoiceCallUrls.liveChatWebSocketUrl(
                wssBase,
                agentId,
                token,
                speechLanguageCode = speechLanguageCode,
                responseLanguageName = responseLanguageName,
                agentStartsConversation = agentStartsConversation,
            )
        return voiceCallClient.packets(url)
    }

    suspend fun sendVoicePcm(audio: ByteArray) {
        voiceCallClient.sendVoicePcm16kBase64(audio)
    }

    suspend fun sendText(text: String) {
        voiceCallClient.sendPacket(CallPacket(type = "text", data = text))
    }

    suspend fun sendActivityStart() {
        voiceCallClient.sendActivityStart()
    }

    suspend fun sendActivityEnd() {
        voiceCallClient.sendActivityEnd()
    }

    suspend fun close() {
        voiceCallClient.close()
    }

    fun connectionState(): StateFlow<VoiceCallConnectionState> = voiceCallClient.connectionState()
}
