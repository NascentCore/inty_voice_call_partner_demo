package ai.sxwl.android.inty.voicecalldemoapp

import ai.sxwl.android.inty.voicecall.CallPacket
import ai.sxwl.android.inty.voicecall.CallType
import ai.sxwl.android.inty.voicecall.VoiceCallConnectionState
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.sxwl.android.inty.voicecalldemoapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import partner.inty.voicecalldemo.IntyVoiceCallDemoSession
import partner.inty.voicecalldemo.createIntyVoiceCallDemoSession
import partner.inty.voicecalldemo.fetchLiveChatStatus
import kotlin.math.sqrt

/**
 * AI 语音通话主界面
 * 功能：WebSocket 连接、录音传输、语音播放、状态显示
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var session: IntyVoiceCallDemoSession? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordJob: Job? = null
    private var durationJob: Job? = null
    private var pcmSendJob: Job? = null
    private var pcmSendChannel: Channel<ByteArray>? = null
    private var audioPlayJob: Job? = null
    private var audioPlayChannel: Channel<AiAudioFrame>? = null
    private var voiceUpstreamStarted = false

    /** 来自 `GET .../live-chat/status` 的上行 PCM 采样率 */
    private var micSampleRate: Int = 16000

    /** 来自 status；下行包未带 `sample_rate` 时用于播放 */
    private var receiveSampleRateDefault: Int = 24000

    /** 当前 AudioTrack 采样率，避免每帧重复创建 */
    private var playbackSampleRate: Int = -1
    
    // 通话时间计数
    private var callDurationSeconds = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 配置参数
    private val wssBase = "wss://dev.inty.sxwl.ai"
    private val agentId = "d044af78-0059-4a37-a0a8-5401121b4b19"
    private val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3Nzg2NDMxNDcsInN1YiI6InVzZXItMDFLUDM5UFJNN1NTMERFRzVUVEVQNFA4OVEifQ.OnlEubSLT54bmwNcwNgg7uk2ajOhM4zSnQ64DVamjis"
    
    // 音频参数（上行 rate 以 status 为准，此处仅表示 PCM 16-bit 单声道）
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
    }

    /**
     * 初始化 UI 事件
     */
    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            connectToServer()
        }

        binding.btnDisconnect.setOnClickListener {
            disconnectFromServer()
        }

        binding.btnSend.setOnClickListener {
            sendTextMessage()
        }

        updateConnectionStatus("未连接")
        appendLog("应用启动，等待连接...")
    }

    /**
     * 检查并请求录音权限
     */
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要录音权限才能使用语音通话", Toast.LENGTH_LONG).show()
                showError("缺少录音权限，请在设置中授予")
            }
        }
    }

    /**
     * 连接到 AI 语音服务器
     */
    @SuppressLint("SetTextI18n")
    private fun connectToServer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            showError("请先授予录音权限")
            checkPermissions()
            return
        }

        try {
            appendLog("正在连接服务器...")
            updateConnectionStatus("连接中...")
            hideError()

            // 创建会话
            session = createIntyVoiceCallDemoSession(token)

            // 先拉 status 对齐采样率，再收 WebSocket，避免下行按错误 Hz 播放导致变调
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val s = session ?: return@launch
                    val status = fetchLiveChatStatus(s.httpClient, wssBaseToHttps(wssBase))
                    if (!status.enabled) {
                        withContext(Dispatchers.Main) {
                            showError("Live chat 未开启（status.enabled=false）")
                            appendLog("✗ Live chat 未开启")
                            resetUI()
                        }
                        s.repository.close()
                        session = null
                        return@launch
                    }
                    micSampleRate = status.sendSampleRate
                    receiveSampleRateDefault = status.receiveSampleRate
                    appendLog(
                        "status: 上行 ${micSampleRate}Hz, 下行默认 ${receiveSampleRateDefault}Hz",
                    )

                    s.repository
                        .liveChatPackets(
                            wssBase,
                            agentId,
                            token,
                        )
                        .catch { e ->
                            Log.e(TAG, "Packet collection error", e)
                            showError("数据接收错误: ${e.message}")
                        }
                        .collect { packet ->
                            handlePacket(packet)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "liveChatPackets collect failed", e)
                    showError("连接异常: ${e.message}")
                    appendLog("✗ 连接异常: ${e.message}")
                    withContext(Dispatchers.Main) {
                        resetUI()
                    }
                    session?.repository?.close()
                    session = null
                }
            }

            lifecycleScope.launch(Dispatchers.Main) {
                session?.repository?.connectionState()?.collect { state ->
                    when (state) {
                        VoiceCallConnectionState.CONNECTED -> {
                            updateConnectionStatus("已连接")
                            hideError()
                            appendLog("✓ 连接成功")
                            Toast.makeText(this@MainActivity, "连接成功", Toast.LENGTH_SHORT).show()

                            binding.btnConnect.isEnabled = false
                            binding.btnDisconnect.isEnabled = true

                            if (!voiceUpstreamStarted) {
                                voiceUpstreamStarted = true
                                startCall()
                            }
                        }
                        VoiceCallConnectionState.ERROR -> {
                            updateConnectionStatus("连接错误")
                            showError("连接失败，请检查网络或凭证")
                            appendLog("✗ 连接错误")
                            //resetUI()
                        }
                        VoiceCallConnectionState.DISCONNECTED -> {
                            updateConnectionStatus("已断开")
                            appendLog("连接已断开")
                            resetUI()
                        }
                        else -> {
                            updateConnectionStatus(state.name)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            showError("连接异常: ${e.message}")
            appendLog("✗ 连接异常: ${e.message}")
            resetUI()
        }
    }

    /**
     * 处理接收到的数据包
     */
    private fun handlePacket(packet: CallPacket) {
        // AI 下行音频帧很密：必须在单协程里串行 write AudioTrack，否则多线程 write 会导致崩溃或断链
        if (packet.typeEnum == CallType.AUDIO_RESPONSE) {
            val b64 = packet.data.orEmpty()
            if (b64.isNotEmpty()) {
                try {
                    val pcm = Base64.decode(b64, Base64.NO_WRAP)
                    val hz =
                        packet.sampleRate.takeIf { it > 0.0 }?.toInt()
                            ?: receiveSampleRateDefault
                    val r = audioPlayChannel?.trySend(AiAudioFrame(pcm, hz))
                    if (r?.isFailure == true) {
                        Log.w(TAG, "AI 播放队列已满，丢弃该音频块")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "audio_response Base64 解码失败", e)
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.Main) {
            when (packet.typeEnum) {
                CallType.SESSION_INFO -> {
                    appendLog("收到会话信息: ${packet.data.orEmpty()}")
                }
                CallType.STATUS -> {
                    packet.status?.let { status ->
                        updateCallStatus(status)
                        appendLog("状态变更: $status")
                    }
                }
                CallType.TRANSCRIPT -> {
                    val t = packet.text.orEmpty()
                    if (t.isNotEmpty()) {
                        appendLog("AI: $t")
                    }
                }
                CallType.USER_TRANSCRIPT -> {
                    val t = packet.text.orEmpty()
                    if (t.isNotEmpty()) {
                        appendLog("你: $t")
                    }
                }
                CallType.ERROR -> {
                    val errorMsg = packet.message ?: "未知错误"
                    showError("服务器错误: $errorMsg")
                    appendLog("✗ 错误: $errorMsg")
                    Log.e(TAG, "Server error: $errorMsg, code: ${packet.errorCode}")
                }
                CallType.END -> {
                    appendLog("通话已结束")
                    Toast.makeText(this@MainActivity, "通话已结束", Toast.LENGTH_SHORT).show()
                    disconnectFromServer()
                }
                else -> {
                    Log.d(TAG, "Unknown packet type: ${packet.type}")
                }
            }
        }
    }

    /**
     * 发送文本消息
     */
    private fun sendTextMessage() {
        val text = binding.editTextInput.text.toString().trim()
        
        // 检查输入是否为空
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入聊天内容后再发送", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 检查是否已连接
        if (session == null) {
            Toast.makeText(this, "请先连接到服务器", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 发送文本消息
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                session?.repository?.sendText(text)
                appendLog("发送文本: $text")
                
                // 清空输入框
                mainHandler.post {
                    binding.editTextInput.text.clear()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send text error", e)
                showError("发送失败: ${e.message}")
            }
        }
    }

    /**
     * 开始语音通话（录音 + 播放）
     */
    private fun startCall() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    session?.repository?.sendActivityStart()
                }
            }.onFailure {
                Log.w(TAG, "sendActivityStart 未成功（若服务端不要求可忽略）", it)
            }

            pcmSendChannel?.close()
            pcmSendJob?.cancel()
            audioPlayChannel?.close()
            audioPlayJob?.cancel()

            pcmSendChannel = Channel(capacity = 64)
            val outChannel = pcmSendChannel!!
            pcmSendJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    for (chunk in outChannel) {
                        try {
                            session?.repository?.sendVoicePcm(chunk)
                        } catch (e: Exception) {
                            Log.e(TAG, "无法发送录音", e)
                        }
                    }
                }

            ensureAudioTrack(receiveSampleRateDefault)

            audioPlayChannel = Channel(capacity = 128)
            val playCh = audioPlayChannel!!
            audioPlayJob =
                lifecycleScope.launch(Dispatchers.IO) {
                    for (frame in playCh) {
                        try {
                            ensureAudioTrack(frame.sampleRateHz)
                            val track = audioTrack
                            track?.write(
                                frame.pcm,
                                0,
                                frame.pcm.size,
                                AudioTrack.WRITE_BLOCKING,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "AudioTrack 写入失败", e)
                        }
                    }
                }

            startRecording()

            callDurationSeconds = 0
            durationJob =
                lifecycleScope.launch(Dispatchers.Main) {
                    while (true) {
                        delay(1000)
                        callDurationSeconds++
                        updateCallDuration(callDurationSeconds)
                    }
                }

            appendLog("开始语音通话...")
        }
    }

    /**
     * 开始录音并发送
     */
    private fun startRecording() {
        recordJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
//                if (ActivityCompat.checkSelfPermission(
//                        (this as Context),
//                        Manifest.permission.RECORD_AUDIO
//                    ) != PackageManager.PERMISSION_GRANTED
//                ) {
//                    // TODO: Consider calling
//                    //    ActivityCompat#requestPermissions
//                    // here to request the missing permissions, and then overriding
//                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
//                    //                                          int[] grantResults)
//                    // to handle the case where the user grants the permission. See the documentation
//                    // for ActivityCompat#requestPermissions for more details.
//                    return
//                }
                val minIn =
                    AudioRecord.getMinBufferSize(
                        micSampleRate,
                        channelConfig,
                        audioFormat,
                    )
                if (minIn <= 0) {
                    throw IllegalStateException("AudioRecord min buffer: $minIn")
                }
                val inBufferBytes = minIn * 2
                audioRecord =
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(micSampleRate)
                                .setChannelMask(channelConfig)
                                .setEncoding(audioFormat)
                                .build(),
                        )
                        .setBufferSizeInBytes(inBufferBytes)
                        .build()

                audioRecord?.startRecording()
                appendLog("开始录音 (${micSampleRate}Hz)")

                val frameBytes = (micSampleRate / 50) * 2
                val buffer = ByteArray(frameBytes)
                while (recordJob?.isActive == true) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // 计算音量
                        val volume = calculateVolume(buffer, read)
                        updateVolume(volume)

                        val ch = pcmSendChannel
                        if (ch != null) {
                            try {
                                val chunk =
                                    if (read == buffer.size) {
                                        buffer.copyOf()
                                    } else {
                                        buffer.copyOf(read)
                                    }
                                ch.send(chunk)
                            } catch (e: Exception) {
                                Log.e(TAG, "麦克风数据入队失败", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                showError("录音错误: ${e.message}")
            }
        }
    }

    /**
     * 计算音量 (0-100)
     */
    private fun calculateVolume(buffer: ByteArray, size: Int): Int {
        var sum = 0.0
        val count = size / 2 // 16-bit PCM
        for (i in 0 until count) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        // 映射到 0-100
        return (rms / 32768.0 * 100).coerceIn(0.0, 100.0).toInt()
    }

    /**
     * 断开连接
     */
    private fun disconnectFromServer() {
        appendLog("正在断开连接...")
        
        pcmSendJob?.cancel()
        pcmSendJob = null
        pcmSendChannel?.close()
        pcmSendChannel = null

        audioPlayJob?.cancel()
        audioPlayJob = null
        audioPlayChannel?.close()
        audioPlayChannel = null

        recordJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // 停止播放
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        playbackSampleRate = -1

        // 停止计时
        durationJob?.cancel()

        // 关闭会话
        lifecycleScope.launch(Dispatchers.IO) {
            session?.repository?.close()
            session = null
        }

        resetUI()
        Toast.makeText(this, "连接已断开", Toast.LENGTH_SHORT).show()
    }

    /**
     * 重置 UI 状态
     */
    private fun resetUI() {
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
        updateConnectionStatus("未连接")
        updateCallStatus("-")
        updateCallDuration(0)
        updateVolume(0)
        callDurationSeconds = 0
        voiceUpstreamStarted = false
    }

    /**
     * UI 更新辅助方法
     */
    private fun updateConnectionStatus(status: String) {
        mainHandler.post {
            binding.textConnectionStatus.text = "连接状态: $status"
        }
    }

    private fun updateCallStatus(status: String) {
        mainHandler.post {
            binding.textCallStatus.text = "通话状态: $status"
        }
    }

    private fun updateCallDuration(seconds: Int) {
        mainHandler.post {
            val minutes = seconds / 60
            val secs = seconds % 60
            binding.textCallDuration.text = String.format("通话时间: %02d:%02d", minutes, secs)
        }
    }

    private fun updateVolume(volume: Int) {
        mainHandler.post {
            binding.progressVolume.progress = volume
        }
    }

    private fun showError(message: String) {
        mainHandler.post {
            binding.textError.text = message
            binding.textError.visibility = View.VISIBLE
        }
    }

    private fun hideError() {
        mainHandler.post {
            binding.textError.visibility = View.GONE
        }
    }

    private fun appendLog(message: String) {
        mainHandler.post {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val logText = "[$timestamp] $message\n"
            binding.textLog.append(logText)
            
            // 限制日志长度
            if (binding.textLog.text.length > 5000) {
                binding.textLog.text = binding.textLog.text.substring(
                    binding.textLog.text.length - 5000
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromServer()
    }

    private fun wssBaseToHttps(wssBaseUrl: String): String {
        val t = wssBaseUrl.trim()
        return when {
            t.startsWith("wss://", ignoreCase = true) -> "https://" + t.drop(6)
            t.startsWith("ws://", ignoreCase = true) -> "http://" + t.drop(5)
            else -> t
        }
    }

    private fun ensureAudioTrack(sampleRateHz: Int) {
        if (sampleRateHz <= 0) return
        if (audioTrack != null && playbackSampleRate == sampleRateHz) return

        audioTrack?.let { t ->
            try {
                t.stop()
            } catch (_: IllegalStateException) {
            }
            t.release()
        }
        audioTrack = null

        val min =
            AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (min <= 0) {
            Log.e(TAG, "AudioTrack min buffer invalid: $min for rate=$sampleRateHz")
            return
        }

        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(min * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        track.play()
        audioTrack = track
        playbackSampleRate = sampleRateHz
    }

    private data class AiAudioFrame(val pcm: ByteArray, val sampleRateHz: Int)

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
