package partner.inty.voicecalldemo

import ai.sxwl.android.inty.voicecall.CallType
import ai.sxwl.android.inty.voicecall.VoiceCallConnectionState
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LiveChatVoiceDemoViewModel(
    private val session: IntyVoiceCallDemoSession,
) : ViewModel() {
    private val repository = session.repository

    private val _uiState = MutableStateFlow(VoiceCallDemoUiState())
    val uiState: StateFlow<VoiceCallDemoUiState> = _uiState.asStateFlow()

    private val _audioOut = Channel<ByteArray>(Channel.BUFFERED)
    val audioResponsePlayback = _audioOut.receiveAsFlow()

    private val sendQueue = Channel<ByteArray>(capacity = 32)
    private var sendJob: Job? = null
    private var packetJob: Job? = null

    init {
        sendJob =
            viewModelScope.launch(Dispatchers.IO) {
                for (chunk in sendQueue) {
                    repository.sendVoicePcm(chunk)
                }
            }
        viewModelScope.launch {
            repository.connectionState().collect { s ->
                _uiState.update { it.copy(connectionState = s) }
            }
        }
    }

    fun startLiveChat(
        wssBase: String,
        agentId: String,
        token: String,
        speechLanguageCode: String? = null,
        responseLanguageName: String? = null,
        agentStartsConversation: Boolean = true,
    ) {
        packetJob?.cancel()
        packetJob =
            viewModelScope.launch(Dispatchers.IO) {
                repository
                    .liveChatPackets(
                        wssBase,
                        agentId,
                        token,
                        speechLanguageCode,
                        responseLanguageName,
                        agentStartsConversation = agentStartsConversation,
                    )
                    .catch { e ->
                        _uiState.update {
                            it.copy(
                                sessionErrorMessage = e.message,
                                connectionState = VoiceCallConnectionState.ERROR,
                            )
                        }
                    }
                    .collect { packet ->
                        when (packet.typeEnum) {
                            CallType.AUDIO_RESPONSE -> {
                                val raw = Base64.decode(packet.data.orEmpty(), Base64.NO_WRAP)
                                _audioOut.trySend(raw)
                            }
                            CallType.SESSION_INFO ->
                                _uiState.update {
                                    it.copy(remainingSeconds = packet.remainingDuration.toLong())
                                }
                            CallType.STATUS ->
                                packet.statusEnum?.let { st ->
                                    _uiState.update { it.copy(callStatus = st) }
                                }
                            CallType.ERROR ->
                                _uiState.update {
                                    it.copy(
                                        sessionErrorMessage = packet.message,
                                        connectionState = VoiceCallConnectionState.ERROR,
                                    )
                                }
                            else -> {}
                        }
                    }
            }
    }

    fun enqueueMicrophonePcm(chunk: ByteArray) {
        viewModelScope.launch { sendQueue.send(chunk) }
    }

    fun interruptSpeaking() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.sendActivityStart()
            repository.sendActivityEnd()
        }
    }

    override fun onCleared() {
        packetJob?.cancel()
        sendJob?.cancel()
        sendQueue.close()
        _audioOut.close()
        CoroutineScope(Dispatchers.IO).launch { session.dispose() }
        super.onCleared()
    }

    companion object {
        fun factory(session: IntyVoiceCallDemoSession): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LiveChatVoiceDemoViewModel(session) as T
                }
            }
    }
}
