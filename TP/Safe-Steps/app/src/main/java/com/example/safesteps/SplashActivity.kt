package com.example.safesteps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.safesteps.services.LocationService
import com.example.safesteps.utils.TokenManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

class SplashActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 100
        private const val BACKGROUND_LOCATION_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Verificar permissões
        Handler(Looper.getMainLooper()).postDelayed({
            checkPermissions()
        }, 1500) //

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                val userId = FirebaseAuth.getInstance().currentUser?.uid
                if (userId != null) {
                    val tokenManager = TokenManager()
                    tokenManager.saveUserToken(userId, token)
                }
            }
        }
    }

    private fun checkPermissions() {
        // Verificar se todas as permissões básicas já foram concedidas
        if (allBasicPermissionsGranted()) {
            // Verificar permissão de localização em background separadamente
            checkBackgroundLocationPermission()
        } else {
            requestBasicPermissions()
        }
    }

    private fun allBasicPermissionsGranted(): Boolean {
        return (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
    }

    private fun requestBasicPermissions() {
        val permissionsToRequest = mutableListOf<String>().apply {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        ActivityCompat.requestPermissions(
            this,
            permissionsToRequest.toTypedArray(),
            PERMISSIONS_REQUEST_CODE
        )
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this)
                    .setTitle("Permissão de Localização em Background")
                    .setMessage("Para monitorar a localização mesmo quando o app não está em uso, precisamos da permissão de localização em background.")
                    .setPositiveButton("Conceder Permissão") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            BACKGROUND_LOCATION_PERMISSION_CODE
                        )
                    }
                    .setNegativeButton("Não Conceder") { _, _ ->
                        showPermissionDeniedDialog()
                    }
                    .setCancelable(false)
                    .create()
                    .show()
            } else {
                navigateToNextScreen()
            }
        } else {
            navigateToNextScreen()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissões Necessárias")
            .setMessage("Este app precisa de todas as permissões solicitadas para funcionar corretamente. Por favor, conceda todas as permissões nas configurações do app.")
            .setPositiveButton("Ir para Configurações") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Sair") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .create()
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSIONS_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    checkBackgroundLocationPermission()
                } else {
                    showPermissionDeniedDialog()
                }
            }
            BACKGROUND_LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    navigateToNextScreen()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        }
    }

    private fun navigateToNextScreen() {
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser != null) {
            registerFCMToken()

            val database = FirebaseDatabase.getInstance()
            database.reference.child("users").child(currentUser.uid)
                .child("userType").get().addOnSuccessListener { snapshot ->
                    val userType = snapshot.getValue(String::class.java)

                    when (userType) {
                        "caregiver" -> {
                            val intent = Intent(this, LocationService::class.java)
                            stopService(intent)

                            val homeIntent = Intent(this, CaregiverHomeActivity::class.java)
                            homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(homeIntent)
                        }
                        "cared" -> {
                            // Garantir que qualquer serviço antigo seja interrompido antes de iniciar um novo
                            val intent = Intent(this, LocationService::class.java)
                            stopService(intent)

                            // Iniciar serviço de localização para os cuidados
                            SafeStepsApplication.instance.startLocationService()

                            val homeIntent = Intent(this, CaredHomeActivity::class.java)
                            homeIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(homeIntent)
                        }
                        else -> {
                            val intent = Intent(this, LocationService::class.java)
                            stopService(intent)

                            val loginIntent = Intent(this, LoginActivity::class.java)
                            loginIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(loginIntent)
                        }
                    }
                    finish()
                }.addOnFailureListener {
                    // Em caso de erro, parar o serviço e redirecionar para login
                    val intent = Intent(this, LocationService::class.java)
                    stopService(intent)

                    val loginIntent = Intent(this, LoginActivity::class.java)
                    loginIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(loginIntent)
                    finish()
                }
        } else {
            val intent = Intent(this, LocationService::class.java)
            stopService(intent)

            val loginIntent = Intent(this, LoginActivity::class.java)
            loginIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(loginIntent)
            finish()
        }
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
}