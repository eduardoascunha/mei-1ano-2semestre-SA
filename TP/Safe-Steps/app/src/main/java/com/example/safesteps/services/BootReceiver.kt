package com.example.safesteps.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser != null) {
                val database = FirebaseDatabase.getInstance()
                val userId = auth.currentUser!!.uid

                database.reference.child("users").child(userId)
                    .child("userType").get().addOnSuccessListener { snapshot ->
                        val userType = snapshot.getValue(String::class.java)

                        // Se o utilizador for um cuidado, iniciar o serviço de localização
                        if (userType == "cared") {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(Intent(context, LocationService::class.java))
                            } else {
                                context.startService(Intent(context, LocationService::class.java))
                            }
                        }
                    }
            }
        }
    }
}