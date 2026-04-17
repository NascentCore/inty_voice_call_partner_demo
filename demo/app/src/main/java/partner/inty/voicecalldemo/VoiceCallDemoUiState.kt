package partner.inty.voicecalldemo

import ai.sxwl.android.inty.voicecall.CallStatus
import ai.sxwl.android.inty.voicecall.VoiceCallConnectionState

data class VoiceCallDemoUiState(
    val connectionState: VoiceCallConnectionState = VoiceCallConnectionState.DISCONNECTED,
    val callStatus: CallStatus? = null,
    val remainingSeconds: Long = 0L,
    val sessionErrorMessage: String? = null,
)
