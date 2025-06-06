package com.example.safesteps.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.BuildConfig
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.messaging.FirebaseMessaging

class TokenManager {
    private val database = FirebaseDatabase.getInstance()
    private val tokensRef = database.reference.child("user_tokens")

    fun saveUserToken(userId: String, token: String) {
        // Verificar se o token já existe para evitar atualizações desnecessárias
        tokensRef.child(userId).child("token").get().addOnSuccessListener { snapshot ->
            val existingToken = snapshot.getValue(String::class.java)

            if (existingToken != token) {
                // Adicionar um timestamp ao token para saber quando foi atualizado pela última vez
                val tokenData = hashMapOf(
                    "token" to token,
                    "updated_at" to ServerValue.TIMESTAMP,
                    "device_info" to getDeviceInfo()
                )

                tokensRef.child(userId)
                    .setValue(tokenData)
                    .addOnSuccessListener {
                        Log.d("TokenManager", "Token atualizado com sucesso para o utilizador $userId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("TokenManager", "Erro ao guardar o token: ${e.message}")
                    }
            } else {
                tokensRef.child(userId).child("updated_at")
                    .setValue(ServerValue.TIMESTAMP)
            }
        }
    }

    fun getUserToken(userId: String, callback: (String?) -> Unit) {
        tokensRef.child(userId)
            .child("token")
            .get()
            .addOnSuccessListener { dataSnapshot ->
                val token = dataSnapshot.getValue(String::class.java)
                callback(token)
            }
            .addOnFailureListener { e ->
                Log.e("TokenManager", "Erro ao obter token: ${e.message}")
                callback(null)
            }
    }

    private fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "os_version" to Build.VERSION.RELEASE,
            "app_version" to BuildConfig.VERSION_NAME,
            "device_id" to Settings.Secure.ANDROID_ID
        )
    }

    // Verificar e atualizar token quando necessário
    fun checkAndUpdateToken(context: Context) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        tokensRef.child(userId).child("updated_at").get().addOnSuccessListener { snapshot ->
            val lastUpdate = snapshot.getValue(Long::class.java) ?: 0L
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastUpdate > 24 * 60 * 60 * 1000 || lastUpdate == 0L) {
                FirebaseMessaging.getInstance().token
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            saveUserToken(userId, task.result)
                        } else {
                            Log.e("TokenManager", "Erro ao obter token FCM: ${task.exception?.message}")
                        }
                    }
            }
        }
    }

    fun removeUserToken(userId: String) {
        tokensRef.child(userId).removeValue()
            .addOnSuccessListener {
                Log.d("TokenManager", "Token removido para o utilizador $userId")
            }
            .addOnFailureListener { e ->
                Log.e("TokenManager", "Erro ao remover token: ${e.message}")
            }
    }
}