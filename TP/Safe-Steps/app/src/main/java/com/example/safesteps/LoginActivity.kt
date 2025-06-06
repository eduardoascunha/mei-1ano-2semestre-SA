package com.example.safesteps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.example.safesteps.databinding.ActivityLoginBinding
import com.example.safesteps.utils.TokenManager
import com.example.safesteps.services.LocationService
import com.google.firebase.messaging.FirebaseMessaging

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database : FirebaseDatabase
    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar Firebase Auth e Database
        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        tokenManager = TokenManager()

        binding.btnLogin.setOnClickListener {
            loginUser()
        }

        binding.tvRegisterLink.setOnClickListener {
            val intent = Intent(this, UserTypeSelectionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun loginUser() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        // Validar campos
        if (email.isEmpty()) {
            binding.etEmail.error = "Email é obrigatório"
            binding.etEmail.requestFocus()
            return
        }

        if (password.isEmpty()) {
            binding.etPassword.error = "Password é obrigatória"
            binding.etPassword.requestFocus()
            return
        }

        // Mostrar ProgressBar
        binding.progressBar.visibility = View.VISIBLE

        // Login no Firebase Auth
        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Login bem sucedido
                    val user = firebaseAuth.currentUser

                    if (user!=null){
                        registerFCMToken()
                        checkPendingFCMToken(user.uid)

                        redirectUserBasedOnType(user.uid)
                    }
                    else {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(
                            this,
                            "Erro ao obter informações do utilizador!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    // Se falhar, mostrar erro
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Erro no login: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

    }


    private fun redirectUserBasedOnType(userId: String) {
        val userRef = database.reference.child("users").child(userId)

        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                binding.progressBar.visibility = View.GONE

                val userType = snapshot.child("userType").getValue(String::class.java)

                when (userType) {
                    "caregiver" -> {
                        // Garantir que o serviço de localização está parado para cuidadores
                        val locintent = Intent(this@LoginActivity, LocationService::class.java)
                        stopService(locintent)

                        // Redirecionar para tela do cuidador
                        val intent = Intent(this@LoginActivity, CaregiverHomeActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    "cared" -> {
                        // Garantir que qualquer serviço antigo seja interrompido antes de iniciar um novo
                        val locintent = Intent(this@LoginActivity, LocationService::class.java)
                        stopService(locintent)

                        // Iniciar serviço de localização para os cuidados
                        SafeStepsApplication.instance.startLocationService()

                        // Redirecionar para tela do cuidado
                        val intent = Intent(this@LoginActivity, CaredHomeActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    else -> {
                        val locintent = Intent(this@LoginActivity, LocationService::class.java)
                        stopService(locintent)

                        Toast.makeText(
                            this@LoginActivity,
                            "Tipo de utilizador desconhecido",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@LoginActivity,
                    "Erro ao verificar o tipo do utilizador: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun registerFCMToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                val userId = FirebaseAuth.getInstance().currentUser?.uid

                if (userId != null) {
                    val tokenManager = TokenManager()
                    tokenManager.saveUserToken(userId, token)
                } else {
                    // Guardar para usar depois do login
                    val sharedPrefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                    sharedPrefs.edit().putString("pending_token", token).apply()
                }
            } else {
                Log.e("FCM", "Failed to get token: ${task.exception}")
            }
        }
    }

    private fun checkPendingFCMToken(userId: String) {
        val sharedPrefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
        val pendingToken = sharedPrefs.getString("pending_token", null)

        if (pendingToken != null) {
            val tokenManager = TokenManager()
            tokenManager.saveUserToken(userId, pendingToken)

            // Limpar token pendente
            sharedPrefs.edit().remove("pending_token").apply()
        }
    }
}