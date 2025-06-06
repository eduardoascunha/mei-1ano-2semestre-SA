package com.example.safesteps

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.safesteps.databinding.ActivityCaredHomeBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.example.safesteps.services.LocationService
import com.example.safesteps.utils.StreamManager
import com.example.safesteps.utils.TokenManager
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaredHomeActivity : BaseActivity() {

    private lateinit var binding: ActivityCaredHomeBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    // Inicialização lazy do StreamManager
    private val streamManager by lazy { StreamManager.getInstance() }

    private var requestListener: ValueEventListener? = null
    private var isStreaming = false
    private var currentCaregiverName: String? = null
    private var userId: String? = null

    companion object {
        private const val TAG = "CaredHomeActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaredHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Verificar se o utilizador está logado
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            navigateToLogin()
            return
        }

        userId = currentUser.uid

        // Configurar listeners e UI
        setupButtonListeners()
        setupAlertViews()

        loadUserData()

        // Verificar e monitorar serviços da câmera
        checkCameraServices()
    }

    private fun setupButtonListeners() {
        // Configurar botão do mapa
        binding.btnMap.setOnClickListener {
            userId?.let { id ->
                val intent = Intent(this, CaredMapsActivity::class.java)
                intent.putExtra("CARED_ID", id)
                intent.putExtra("MODE", "CARED_VIEW")
                startActivity(intent)
            }
        }

        // Configurar botão de logout
        binding.btnLogout.setOnClickListener {
            showLogoutConfirmDialog()
        }

        // Configurar ícone de câmera
        binding.cameraMonitoringIcon.setOnClickListener {
            showStreamingInfoDialog()
        }
    }

    private fun checkCameraServices() {
        // Inicializar o Agora Engine apenas quando necessário e em background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!streamManager.isEngineInitialized()) {
                    streamManager.initializeAgoraEngine(applicationContext)
                }

                withContext(Dispatchers.Main) {
                    // Verificar solicitações e status da câmera
                    checkPendingCameraRequests()
                    checkIfCameraIsMonitored()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao inicializar serviços de câmera", e)
            }
        }
    }

    private fun checkPendingCameraRequests() {
        userId?.let { id ->
            streamManager.checkPendingRequest(id) { hasPendingRequest, requestData ->
                if (!isFinishing && hasPendingRequest && requestData != null) {
                    runOnUiThread {
                        if (!isStreaming) {
                            showCameraAccessRequestDialog(
                                requestData["caregiverId"].toString(),
                                requestData["caregiverName"].toString()
                            )
                        }
                    }
                }
            }

            // Adicionar listener para novas solicitações
            listenForNewCameraRequests(id)
        }
    }

    private fun listenForNewCameraRequests(userId: String) {
        // Remover listener anterior se existir
        removeRequestListener()

        requestListener = database.reference.child("stream_requests").child(userId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isFinishing && snapshot.exists()) {
                        val status = snapshot.child("status").getValue(String::class.java)

                        when (status) {
                            StreamManager.REQUEST_STATUS_PENDING -> {
                                val caregiverId = snapshot.child("caregiverId").getValue(String::class.java) ?: ""
                                val caregiverName = snapshot.child("caregiverName").getValue(String::class.java) ?: "Cuidador"

                                if (!isStreaming) {
                                    showCameraAccessRequestDialog(caregiverId, caregiverName)
                                }
                            }
                            StreamManager.REQUEST_STATUS_ACCEPTED -> {
                                val caregiverName = snapshot.child("caregiverName").getValue(String::class.java) ?: "Cuidador"
                                currentCaregiverName = caregiverName
                                updateCameraMonitoringStatus(true)
                                streamManager.setupLocalVideo()
                                if (streamManager.startBroadcasting()) {
                                    Log.d(TAG, "Broadcasting iniciado após aceite")
                                    isStreaming = true
                                } else {
                                    Log.e(TAG, "Falha ao iniciar broadcasting após aceite")
                                    updateCameraMonitoringStatus(false)
                                }
                            }
                            StreamManager.REQUEST_STATUS_ENDED, StreamManager.REQUEST_STATUS_REJECTED -> {
                                // Transmissão encerrada
                                if (isStreaming) {
                                    streamManager.leaveChannel()
                                }
                                updateCameraMonitoringStatus(false)
                                currentCaregiverName = null
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    if (!isFinishing) {
                        Log.e(TAG, "Erro ao monitorar solicitações: ${error.message}", error.toException())
                    }
                }
            })
    }

    private fun checkIfCameraIsMonitored() {
        userId?.let { id ->
            streamManager.isCurrentlyStreaming(id) { isCurrentlyStreaming, caregiverName ->
                runOnUiThread {
                    if (!isFinishing) {
                        isStreaming = isCurrentlyStreaming
                        currentCaregiverName = caregiverName

                        updateCameraMonitoringStatus(isCurrentlyStreaming)

                        if (isCurrentlyStreaming) {
                            startBroadcasting()
                        }
                    }
                }
            }
        }
    }

    private fun showCameraAccessRequestDialog(caregiverId: String, caregiverName: String) {
        if (isFinishing) return

        AlertDialog.Builder(this)
            .setTitle("Solicitação de Acesso à Câmera")
            .setMessage("$caregiverName deseja acessar sua câmera. Deseja permitir?")
            .setPositiveButton("Aceitar") { _, _ ->
                acceptCameraRequest(caregiverId, caregiverName)
            }
            .setNegativeButton("Recusar") { _, _ ->
                rejectCameraRequest()
            }
            .setCancelable(false)
            .show()
    }

    private fun acceptCameraRequest(caregiverId: String, caregiverName: String) {
        userId?.let { id ->
            try {
                // Atualizar status na interface
                updateCameraMonitoringStatus(true)
                currentCaregiverName = caregiverName

                // Aceitar a solicitação no Firebase
                streamManager.acceptCameraRequest(caregiverId, id)

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao aceitar solicitação", e)
                Toast.makeText(this, "Erro ao aceitar solicitação", Toast.LENGTH_SHORT).show()
                updateCameraMonitoringStatus(false)
            }
        }
    }

    private fun startBroadcasting() {
        try {
            streamManager.setupLocalVideo()

            // Iniciar a transmissão
            if (streamManager.startBroadcasting()) {
                Log.d(TAG, "Broadcasting started")
                isStreaming = true
            } else {
                Log.e(TAG, "Falha ao iniciar broadcasting")
                updateCameraMonitoringStatus(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar broadcasting", e)
            updateCameraMonitoringStatus(false)
        }
    }

    private fun rejectCameraRequest() {
        userId?.let { id ->
            try {
                streamManager.rejectCameraRequest(id)
                updateCameraMonitoringStatus(false)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao rejeitar solicitação", e)
            }
        }
    }

    private fun updateCameraMonitoringStatus(isMonitoring: Boolean) {
        isStreaming = isMonitoring

        binding.cameraMonitoringIcon.visibility = if (isMonitoring) View.VISIBLE else View.GONE
    }

    private fun showStreamingInfoDialog() {
        if (isFinishing) return

        val dialogView = layoutInflater.inflate(R.drawable.dialog_camera_streaming_info, null)
        val tvCaregiverName = dialogView.findViewById<TextView>(R.id.tvMonitoringCaregiver)

        tvCaregiverName.text = "Câmera a ser monitorada por: $currentCaregiverName"

        AlertDialog.Builder(this)
            .setTitle("Câmera a ser monitorizada")
            .setView(dialogView)
            .setPositiveButton("Encerrar Transmissão") { _, _ ->
                userId?.let { id ->
                    try {
                        streamManager.endStream(id)
                        updateCameraMonitoringStatus(false)
                        currentCaregiverName = null
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao encerrar transmissão", e)
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun loadUserData() {
        binding.progressBar.visibility = View.VISIBLE

        userId?.let { id ->
            database.reference.child("users").child(id)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!isFinishing) {
                            binding.progressBar.visibility = View.GONE

                            val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
                            val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
                            val username = snapshot.child("username").getValue(String::class.java) ?: ""
                            val code = snapshot.child("code").getValue(String::class.java) ?: ""

                            binding.tvWelcome.text = "Olá $firstName!"
                            binding.tvName.text = "$firstName $lastName"
                            binding.tvUsername.text = username
                            binding.tvCode.text = code
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (!isFinishing) {
                            binding.progressBar.visibility = View.GONE
                            Log.e(TAG, "Erro ao carregar dados: ${error.message}", error.toException())

                            Toast.makeText(this@CaredHomeActivity,
                                "Erro ao carregar dados. Tente novamente.",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                })
        }
    }

    override fun setupAlertViews() {
        try {
            alertBanner = findViewById(R.id.alertBanner)
            btnSilenceAlert = findViewById(R.id.btnSilenceAlert)

            btnSilenceAlert?.setOnClickListener {
                stopAlertSound()
                btnSilenceAlert?.visibility = View.GONE
            }

            updateAlertBanner(SafeStepsApplication.instance.currentAppState)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar views de alerta", e)
        }
    }

    private fun showLogoutConfirmDialog() {
        if (isFinishing) return

        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Tem certeza que deseja sair da sua conta?")
            .setPositiveButton("Sim") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun performLogout() {
        try {
            userId?.let { id ->
                // Encerrar transmissão se estiver ativa
                if (isStreaming) {
                    streamManager.endStream(id)
                }

                val tokenManager = TokenManager()
                tokenManager.removeUserToken(id)
                stopLocationService()
            }

            cleanupResources()

            firebaseAuth.signOut()
            navigateToLogin()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao realizar logout", e)

            stopLocationService()

            firebaseAuth.signOut()
            navigateToLogin()
        }
    }

    private fun stopLocationService() {
        try {
            val serviceIntent = Intent(this, LocationService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar o serviço de localização", e)
        }
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun removeRequestListener() {
        requestListener?.let {
            try {
                userId?.let { id ->
                    database.reference.child("stream_requests").child(id).removeEventListener(it)
                }
                requestListener = null
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao remover listener", e)
            }
        }
    }

    private fun cleanupResources() {
        try {
            // Remover listeners
            removeRequestListener()

            // Limpar recursos de streaming
            if (isStreaming) {
                streamManager.leaveChannel()
                isStreaming = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar recursos", e)
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
    }
}