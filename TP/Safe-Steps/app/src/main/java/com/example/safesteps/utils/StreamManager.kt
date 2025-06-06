package com.example.safesteps.utils

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import com.example.safesteps.CaregiverStreamActivity
import com.example.safesteps.SafeStepsApplication
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference


class StreamManager private constructor() {

    companion object {
        private const val TAG = "StreamManager"
        private const val AGORA_APP_ID = "your_key"

        // Estado das solicitações de acesso à câmera
        const val REQUEST_STATUS_PENDING = "pending"
        const val REQUEST_STATUS_ACCEPTED = "accepted"
        const val REQUEST_STATUS_REJECTED = "rejected"
        const val REQUEST_STATUS_ENDED = "ended"

        // Singleton thread-safe com lazy initialization
        @Volatile
        private var instance: StreamManager? = null

        fun getInstance(): StreamManager {
            return instance ?: synchronized(this) {
                instance ?: StreamManager().also { instance = it }
            }
        }
    }

    private var rtcEngine: RtcEngine? = null
    private var channelId: String? = null
    private val isBroadcasting = AtomicBoolean(false)
    private val isViewing = AtomicBoolean(false)
    private var caregiverActivityRef: WeakReference<CaregiverStreamActivity>? = null
    private val database: FirebaseDatabase by lazy { SafeStepsApplication.instance.database }
    private val isEngineInitializedFlag = AtomicBoolean(false)

    // Handler para eventos do Agora RTC
    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.d(TAG, "Utilizador entrou: $uid após $elapsed ms")
            caregiverActivityRef?.get()?.runOnUiThread {
                caregiverActivityRef?.get()?.onRemoteUserJoined(uid)
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.d(TAG, "Utilizador saiu: $uid, razão: $reason")
        }

        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.d(TAG, "Entrou no canal: $channel com uid: $uid após $elapsed ms")

            if (isViewing.get()) {
                Log.d(TAG, "Modo de visualização ativo, esperando transmissor...")
            } else if (isBroadcasting.get()) {
                Log.d(TAG, "Modo de transmissão ativo, enviando vídeo...")
            }
        }

        override fun onError(err: Int) {
            Log.e(TAG, "Erro Agora: $err")
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            Log.d(TAG, "Estado da conexão alterado: $state, razão: $reason")
        }

        override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            Log.d(TAG, "Estado do vídeo remoto alterado: uid=$uid, estado=$state, razão=$reason")
        }
    }


    // Verifica se o Agora Engine está inicializado
    fun isEngineInitialized(): Boolean {
        return rtcEngine != null && isEngineInitializedFlag.get()
    }

    // Inicializa o Agora RTC Engine
    @Synchronized
    fun initializeAgoraEngine(context: Context): Boolean {
        if (isEngineInitialized()) {
            Log.d(TAG, "Engine já inicializado, reutilizando instância")
            return true
        }

        try {
            Log.d(TAG, "Inicializando Agora Engine...")
            rtcEngine = RtcEngine.create(context.applicationContext, AGORA_APP_ID, rtcEventHandler)

            // Configuração inicial do vídeo
            rtcEngine?.apply {
                enableVideo()
                val config = VideoEncoderConfiguration().apply {
                    dimensions = VideoEncoderConfiguration.VD_640x360
                    frameRate = 15
                    bitrate = VideoEncoderConfiguration.STANDARD_BITRATE
                }
                setVideoEncoderConfiguration(config)

                // Usar modo de cenário de comunicação para reduzir uso de recursos
                setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)

                // Configurações adicionais para optimizar performance
                setParameters("{\"che.video.lowBitRateStreamParameter\":{\"width\":320,\"height\":180,\"frameRate\":15,\"bitRate\":140}}")
                setParameters("{\"rtc.log_filter\":65535}")
                setParameters("{\"che.audio.enable.aec\":false}")
                setParameters("{\"che.audio.enable.agc\":false}")
            }

            isEngineInitializedFlag.set(true)
            Log.d(TAG, "Engine Agora inicializado com sucesso")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar o Agora Engine", e)
            rtcEngine = null
            isEngineInitializedFlag.set(false)
            return false
        }
    }


    fun setCaregiverActivity(activity: CaregiverStreamActivity?) {
        this.caregiverActivityRef = if (activity != null) WeakReference(activity) else null
        Log.d(TAG, "Referência para CaregiverStreamActivity ${if (activity != null) "definida" else "removida"}")
    }

    // Envia solicitação de acesso à câmera (cuidador -> cuidado)
    fun requestCameraAccess(caregiverId: String, caredId: String, caregiverName: String, caredName: String) {
        try {
            // Gera um ID reduzido seguro para o canal (16 caracteres sem hífens)
            val shortRequestId = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .take(16)

            // Monta canal com limite de tamanho seguro
            channelId = "ch_${caregiverId.take(8)}_${caredId.take(8)}_$shortRequestId"
            Log.d(TAG, "Canal criado: $channelId")

            // Prepara dados da solicitação
            val requestRef = database.reference
                .child("stream_requests")
                .child(caredId)

            val requestData = hashMapOf(
                "requestId" to shortRequestId,
                "caregiverId" to caregiverId,
                "caregiverName" to caregiverName,
                "caredId" to caredId,
                "caredName" to caredName,
                "channelId" to channelId,
                "status" to REQUEST_STATUS_PENDING,
                "timestamp" to System.currentTimeMillis()
            )

            // Envia para o Firebase
            requestRef.setValue(requestData)
                .addOnSuccessListener {
                    Log.d(TAG, "Solicitação de acesso à câmera enviada")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Erro ao enviar solicitação de acesso à câmera", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao solicitar acesso à câmera", e)
        }
    }

    // Aceita solicitação de acesso à câmera (cuidado)
    fun acceptCameraRequest(caregiverId: String, caredId: String) {
        try {
            val requestRef = database.reference.child("stream_requests").child(caredId)

            requestRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        try {
                            channelId = snapshot.child("channelId").getValue(String::class.java)
                            Log.d(TAG, "Aceitar solicitação: Canal obtido: $channelId")

                            // Atualizar status para aceito
                            requestRef.child("status").setValue(REQUEST_STATUS_ACCEPTED)
                                .addOnSuccessListener {
                                    Log.d(TAG, "Solicitação de acesso à câmera aceita")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Erro ao aceitar solicitação de acesso à câmera", e)
                                }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exceção ao processar aceitação", e)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Erro ao buscar solicitação de acesso à câmera", error.toException())
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao aceitar solicitação", e)
        }
    }

    // Rejeita solicitação de acesso à câmera (cuidado)
    fun rejectCameraRequest(caredId: String) {
        try {
            val requestRef = database.reference.child("stream_requests").child(caredId)

            requestRef.child("status").setValue(REQUEST_STATUS_REJECTED)
                .addOnSuccessListener {
                    Log.d(TAG, "Solicitação de acesso à câmera rejeitada")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Erro ao rejeitar solicitação de acesso à câmera", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao rejeitar solicitação", e)
        }
    }

    // Encerra transmissão de ambos os lados
    fun endStream(caredId: String) {
        try {
            val requestRef = database.reference.child("stream_requests").child(caredId)

            requestRef.child("status").setValue(REQUEST_STATUS_ENDED)
                .addOnSuccessListener {
                    Log.d(TAG, "Transmissão encerrada")
                    // Limpar os recursos
                    leaveChannel()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Erro ao encerrar transmissão", e)
                    leaveChannel()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao encerrar transmissão", e)
            leaveChannel()
        }
    }

    // Inicia a transmissão de vídeo (cuidado)
    fun startBroadcasting(): Boolean {
        if (!isEngineInitialized()) {
            Log.e(TAG, "Não é possível iniciar a transmissão: engine não inicializado")
            return false
        }

        if (channelId.isNullOrEmpty()) {
            Log.e(TAG, "Não é possível iniciar a transmissão: canal não inicializado")
            return false
        }

        try {
            Log.d(TAG, "Iniciando transmissão no canal: $channelId")

            // Verificar se já está transmitindo
            if (isBroadcasting.get()) {
                Log.d(TAG, "Já está transmitindo, retornando true")
                return true
            }

            rtcEngine?.apply {
                // Definir perfil de canal para transmissão ao vivo
                setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
                setClientRole(Constants.CLIENT_ROLE_BROADCASTER)

                // Configurações de vídeo e áudio
                enableVideo()
                enableAudio()

                switchCamera()

                startPreview()

                // Token temporário nulo
                val result = joinChannel(null, channelId, "", 0)
                if (result != 0) {
                    Log.e(TAG, "Falha ao entrar no canal ($result): canal inválido ou outro erro")
                    stopPreview()
                    return false
                }
            }

            isBroadcasting.set(true)
            Log.d(TAG, "Transmissão iniciada com sucesso")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao iniciar transmissão", e)
            try {
                rtcEngine?.stopPreview()
            } catch (e2: Exception) {
                Log.e(TAG, "Erro ao parar preview após falha", e2)
            }
            return false
        }
    }

    // Inicia a visualização do vídeo (cuidador)
    fun startViewing(): Boolean {
        if (!isEngineInitialized()) {
            Log.e(TAG, "Não é possível iniciar a visualização: engine não inicializado")
            return false
        }

        if (channelId.isNullOrEmpty()) {
            Log.e(TAG, "Não é possível iniciar a visualização: canal não inicializado")
            return false
        }

        if (isViewing.get()) {
            Log.d(TAG, "Já está a visualizar")
            return true
        }

        try {
            Log.d(TAG, "Iniciando visualização no canal: $channelId")

            rtcEngine?.apply {
                setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
                setClientRole(Constants.CLIENT_ROLE_AUDIENCE)

                enableAudio()
                enableVideo()

                setParameters("{\"che.video.lowBitRateStreamParameter\":{\"width\":320,\"height\":180,\"frameRate\":15,\"bitRate\":140}}")

                // Token temporário nulo
                val result = joinChannel(null, channelId, "", 0)
                if (result != 0) {
                    Log.e(TAG, "Falha ao entrar no canal ($result): canal inválido ou outro erro")
                    return false
                }
            }

            isViewing.set(true)
            Log.d(TAG, "Visualização iniciada com sucesso")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao iniciar visualização", e)
            return false
        }
    }

    // Configura vídeo local (para o transmissor)
    fun setupLocalVideo() {
        if (!isEngineInitialized()) {
            Log.e(TAG, "Não é possível configurar vídeo local: engine não inicializado")
            return
        }

        try {
            val surfaceView = SurfaceView(SafeStepsApplication.instance.applicationContext)
            val videoCanvas = VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0)

            rtcEngine?.setupLocalVideo(videoCanvas)

            rtcEngine?.enableLocalVideo(true)

            Log.d(TAG, "Vídeo local configurado corretamente")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar vídeo local", e)
        }
    }

    // Configura vídeo remoto (para o visualizador)
    fun setupRemoteVideo(surfaceView: SurfaceView, uid: Int) {
        if (!isEngineInitialized()) {
            Log.e(TAG, "Não é possível configurar vídeo remoto: engine não inicializado")
            return
        }

        try {
            val videoCanvas = VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid)
            rtcEngine?.setupRemoteVideo(videoCanvas)
            Log.d(TAG, "Vídeo remoto configurado para uid: $uid")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar vídeo remoto", e)
        }
    }

    // Sai do canal de transmissão e limpa recursos
    @Synchronized
    fun leaveChannel() {
        try {
            if (rtcEngine != null) {
                if (isBroadcasting.get()) {
                    try {
                        rtcEngine?.stopPreview()
                        Log.d(TAG, "Preview da câmera parada")
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao parar preview", e)
                    }
                }

                try {
                    rtcEngine?.leaveChannel()
                    Log.d(TAG, "Saiu do canal de transmissão")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao sair do canal", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao deixar canal", e)
        } finally {
            isBroadcasting.set(false)
            isViewing.set(false)
            channelId = null
        }
    }

    // Verifica solicitações pendentes de acesso à câmera
    fun checkPendingRequest(caredId: String, callback: (Boolean, Map<String, Any>?) -> Unit) {
        try {
            val requestRef = database.reference.child("stream_requests").child(caredId)

            requestRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        if (snapshot.exists()) {
                            val status = snapshot.child("status").getValue(String::class.java)

                            when (status) {
                                REQUEST_STATUS_PENDING, REQUEST_STATUS_ACCEPTED -> {
                                    val requestData = mutableMapOf<String, Any>()
                                    requestData["caregiverId"] = snapshot.child("caregiverId").getValue(String::class.java) ?: ""
                                    requestData["caregiverName"] = snapshot.child("caregiverName").getValue(String::class.java) ?: ""
                                    requestData["channelId"] = snapshot.child("channelId").getValue(String::class.java) ?: ""

                                    channelId = snapshot.child("channelId").getValue(String::class.java)
                                    Log.d(TAG, "Solicitação ${if (status == REQUEST_STATUS_PENDING) "pendente" else "aceita"} encontrada, canal: $channelId")

                                    callback(true, requestData)
                                }
                                else -> callback(false, null)
                            }
                        } else {
                            callback(false, null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao processar dados da solicitação", e)
                        callback(false, null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Erro ao verificar solicitação pendente", error.toException())
                    callback(false, null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao verificar solicitação pendente", e)
            callback(false, null)
        }
    }

    // Verifica se a câmera está atualmente sendo transmitida
    fun isCurrentlyStreaming(caredId: String, callback: (Boolean, String?) -> Unit) {
        try {
            val requestRef = database.reference.child("stream_requests").child(caredId)

            requestRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        if (snapshot.exists()) {
                            val status = snapshot.child("status").getValue(String::class.java)

                            if (status == REQUEST_STATUS_ACCEPTED) {
                                val caregiverName = snapshot.child("caregiverName").getValue(String::class.java)
                                channelId = snapshot.child("channelId").getValue(String::class.java)
                                Log.d(TAG, "Transmissão em andamento detectada, canal: $channelId")

                                callback(true, caregiverName)
                            } else {
                                callback(false, null)
                            }
                        } else {
                            callback(false, null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao processar status da transmissão", e)
                        callback(false, null)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Erro ao verificar status da transmissão", error.toException())
                    callback(false, null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao verificar status da transmissão", e)
            callback(false, null)
        }
    }

    // Liberta todos os recursos do RtcEngine
    @Synchronized
    fun destroy() {
        try {
            leaveChannel()

            caregiverActivityRef = null

            try {
                RtcEngine.destroy()
                rtcEngine = null
                isEngineInitializedFlag.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao destruir RtcEngine", e)
            }

            instance = null
            Log.d(TAG, "Recursos do StreamManager destruídos")
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao destruir recursos", e)
        }
    }
}