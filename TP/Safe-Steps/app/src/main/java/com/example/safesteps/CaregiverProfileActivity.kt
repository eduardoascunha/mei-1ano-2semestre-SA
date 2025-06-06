package com.example.safesteps

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.example.safesteps.databinding.ActivityCaregiverProfileBinding
import com.example.safesteps.utils.TokenManager

class CaregiverProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityCaregiverProfileBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaregiverProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Verificar se o user está logado
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            navigateToLogin()
            return
        }

        loadProfileData()

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnLogout.setOnClickListener {
            showLogoutConfirmDialog()
        }

        setupAlertViews()
    }

    override fun setupAlertViews() {
        alertBanner = findViewById(R.id.alertBanner)
        btnSilenceAlert = findViewById(R.id.btnSilenceAlert)

        btnSilenceAlert?.setOnClickListener {
            stopAlertSound()
        }

        updateAlertBanner(SafeStepsApplication.instance.currentAppState)
    }

    private fun loadProfileData() {
        binding.progressBar.visibility = View.VISIBLE

        val userId = firebaseAuth.currentUser?.uid
        userId?.let {
            database.reference.child("users").child(it)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        binding.progressBar.visibility = View.GONE

                        val firstName = snapshot.child("firstName").getValue(String::class.java) ?: ""
                        val lastName = snapshot.child("lastName").getValue(String::class.java) ?: ""
                        val email = snapshot.child("email").getValue(String::class.java) ?: ""
                        val username = snapshot.child("username").getValue(String::class.java) ?: ""

                        binding.tvName.text = "$firstName $lastName"
                        binding.tvUsername.text = username
                        binding.tvEmail.text = email
                    }

                    override fun onCancelled(error: DatabaseError) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(
                            this@CaregiverProfileActivity,
                            "Erro ao carregar o perfil: ${error.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
        }
    }

    private fun showLogoutConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Tem a certeza que deseja fazer logout?")
            .setPositiveButton("Sim") { _, _ ->
                val userId = firebaseAuth.currentUser?.uid
                if (userId != null) {
                    // Remover o token FCM antes de fazer logout
                    val tokenManager = TokenManager()
                    tokenManager.removeUserToken(userId)

                    firebaseAuth.signOut()
                    navigateToLogin()
                } else {
                    // Se por algum motivo não tiver o userId, apenas faz logout
                    firebaseAuth.signOut()
                    navigateToLogin()
                }
            }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}