package com.example.safesteps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.safesteps.databinding.ActivityCaregiverStreamBinding
import com.example.safesteps.utils.StreamManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CaregiverStreamActivity : BaseActivity() {

    private lateinit var binding: ActivityCaregiverStreamBinding
    private lateinit var database: FirebaseDatabase
    private var caregiverId: String = ""
    private var caredId: String = ""
    private var caredName: String = ""
    private var caregiverName: String = ""

    // Obter instância do StreamManager de forma lazy
    private val streamManager by lazy { StreamManager.getInstance() }

    private var requestListener: ValueEventListener? = null
    private var remoteSurfaceView: SurfaceView? = null
    private var isStreamActive = false

    companion object {
        private const val TAG = "CaregiverStreamActivity"
        private const val PERMISSION_REQUEST_ID = 22
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaregiverStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance()

        // Inicializar o StreamManager apenas quando necessário
        streamManager.setCaregiverActivity(this)

        // Obter dados da Intent com valores padrão para evitar valores nulos
        with(intent) {
            caregiverId = getStringExtra("CAREGIVER_ID") ?: ""
            caredId = getStringExtra("CARED_ID") ?: ""
            caredName = getStringExtra("CARED_NAME") ?: "Cuidado"
            caregiverName = getStringExtra("CAREGIVER_NAME") ?: "Cuidador"
        }

        // Validar parâmetros críticos
        if (caregiverId.isEmpty() || caredId.isEmpty()) {
            Toast.makeText(this, "Parâmetros inválidos. Tente novamente.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvCaredName.text = caredName

        binding.btnEndStream.setOnClickListener {
            endStream()
        }

        if (checkAndRequestPermissions()) {
            requestCameraAccess()
        }

        setupAlertViews()
        setupRemoteView()
    }

    private fun setupRemoteView() {
        try {
            val container = findViewById<FrameLayout>(R.id.remote_video_view_container)
            container.post {
                container.removeAllViews()
                remoteSurfaceView = SurfaceView(baseContext)
                container.addView(remoteSurfaceView)
                Log.d(TAG, "Container para vídeo remoto configurado")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar visão remota", e)
        }
    }

    private fun requestCameraAccess() {
        try {
            binding.waitingLayout.visibility = View.VISIBLE

            if (!streamManager.isEngineInitialized()) {
                streamManager.initializeAgoraEngine(applicationContext)
            }

            // Enviar solicitação para o cuidado
            streamManager.requestCameraAccess(caregiverId, caredId, caregiverName, caredName)

            // Monitorar alterações no status da solicitação
            listenForRequestStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao solicitar acesso à câmera", e)
            Toast.makeText(this, "Erro ao solicitar acesso. Tente novamente.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    private fun initAgoraEngineAsync() {
        CoroutineScope(Dispatchers.Default).launch {
            if (!streamManager.isEngineInitialized()) {
                streamManager.initializeAgoraEngine(applicationContext)
            }
        }
    }
    
    private fun listenForRequestStatus() {
        Log.d(TAG, "Iniciando monitoramento do status da solicitação para caredId: $caredId")

        // Remover listener anterior se existir
        removeRequestListener()

        requestListener = database.reference.child("stream_requests").child(caredId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isFinishing && snapshot.exists()) {
                        val status = snapshot.child("status").getValue(String::class.java)
                        Log.d(TAG, "Status da solicitação atualizado: $status")

                        when (status) {
                            StreamManager.REQUEST_STATUS_ACCEPTED -> {
                                // Solicitação aceita, iniciar visualização
                                binding.waitingLayout.visibility = View.GONE
                                binding.rejectedLayout.visibility = View.GONE
                                startViewing()
                            }
                            StreamManager.REQUEST_STATUS_REJECTED -> {
                                // Solicitação rejeitada, mostrar mensagem
                                binding.waitingLayout.visibility = View.GONE
                                binding.rejectedLayout.visibility = View.VISIBLE
                            }
                            StreamManager.REQUEST_STATUS_ENDED -> {
                                // Transmissão encerrada pelo cuidado
                                if (!isFinishing) {
                                    Toast.makeText(this@CaregiverStreamActivity,
                                        "A transmissão foi encerrada",
                                        Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (!isFinishing) {
                        Log.e(TAG, "Erro ao monitorar status da solicitação", error.toException())
                        Toast.makeText(this@CaregiverStreamActivity,
                            "Erro ao monitorar status da solicitação",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            })
    }

    private fun startViewing() {
        try {
            Log.d(TAG, "Iniciando visualização do vídeo")

            if (!isStreamActive) {
                // Certificar-se que o container de vídeo está visível
                val container = findViewById<FrameLayout>(R.id.remote_video_view_container)
                container.visibility = View.VISIBLE

                // Iniciar visualização do vídeo
                if (streamManager.startViewing()) {
                    isStreamActive = true
                    Log.d(TAG, "Visualização iniciada com sucesso")
                } else {
                    Toast.makeText(this, "Erro ao iniciar visualização", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar visualização", e)
            Toast.makeText(this, "Erro ao conectar ao vídeo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun endStream() {
        try {
            Log.d(TAG, "Encerrando transmissão")
            isStreamActive = false

            // Encerrar transmissão
            streamManager.endStream(caredId)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao encerrar transmissão", e)
            finish() // Encerrar atividade mesmo em caso de erro
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = ArrayList<String>()

        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_ID
            )
            return false
        }

        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_ID) {
            var allPermissionsGranted = true

            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }

            if (allPermissionsGranted) {
                // Permissões concedidas, enviar solicitação
                requestCameraAccess()
            } else {
                Toast.makeText(
                    this,
                    "Permissões necessárias não concedidas",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun setupAlertViews() {
        try {
            alertBanner = findViewById(R.id.alertBanner)
            btnSilenceAlert = findViewById(R.id.btnSilenceAlert)

            btnSilenceAlert?.setOnClickListener {
                stopAlertSound()
            }

            updateAlertBanner(SafeStepsApplication.instance.currentAppState)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar views de alerta", e)
        }
    }

    fun onRemoteUserJoined(uid: Int) {
        Log.d(TAG, "Utilizador remoto entrou, uid: $uid, configurando visualização")

        runOnUiThread {
            try {
                if (!isFinishing) {
                    val localRemoteSurfaceView = remoteSurfaceView

                    if (localRemoteSurfaceView != null) {
                        streamManager.setupRemoteVideo(localRemoteSurfaceView, uid)
                        Log.d(TAG, "Vídeo remoto configurado com sucesso")
                    } else {
                        Log.d(TAG, "SurfaceView remoto é nulo, recriando")
                        val container = findViewById<FrameLayout>(R.id.remote_video_view_container)
                        remoteSurfaceView = SurfaceView(baseContext)
                        container.addView(remoteSurfaceView)
                        remoteSurfaceView?.let { streamManager.setupRemoteVideo(it, uid) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao configurar vídeo remoto", e)
            }
        }
    }

    private fun removeRequestListener() {
        requestListener?.let {
            try {
                database.reference.child("stream_requests").child(caredId).removeEventListener(it)
                requestListener = null
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao remover listener", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            // Encerrar o stream se estiver ativo
            if (isStreamActive) {
                streamManager.endStream(caredId)
                isStreamActive = false
            }

            removeRequestListener()
            streamManager.setCaregiverActivity(null)
            streamManager.leaveChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar recursos", e)
        }
    }
}