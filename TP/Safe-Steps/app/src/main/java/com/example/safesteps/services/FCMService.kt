package com.example.safesteps.services

import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.safesteps.SafeStepsApplication
import com.example.safesteps.SplashActivity
import com.example.safesteps.utils.GeofenceEventBus
import com.example.safesteps.utils.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.safesteps.utils.TokenManager

class FCMService : FirebaseMessagingService() {

    private val tokenManager = TokenManager()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d("FCMService", "Mensagem recebida de: ${remoteMessage.from}")

        // Processar notificação
        val notificationData = remoteMessage.data

        // Verificar o tipo de notificação
        when (notificationData["type"]) {
            NotificationHelper.TYPE_GEOFENCE_ALERT -> {
                processSafeLeave(remoteMessage)
            }
            NotificationHelper.TYPE_DANGER_ZONE_ALERT -> {
                processDangerZone(remoteMessage)
            }
            NotificationHelper.TYPE_GEOFENCE_SAFE_RETURN -> {
                processSafeReturn(remoteMessage)
            }
            else -> {
                // Outras notificações
                processStandardNotification(remoteMessage)
            }
        }
    }

    private fun processSafeLeave(remoteMessage: RemoteMessage) {
        val notificationHelper = NotificationHelper(this)
        val title = remoteMessage.notification?.title ?: "Alerta SafeSteps"
        val message = remoteMessage.notification?.body ?: "O utilizador saiu da zona segura."

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            FirebaseDatabase.getInstance().reference.child("users").child(currentUser.uid)
                .child("userType").get().addOnSuccessListener { snapshot ->
                    val userType = snapshot.getValue(String::class.java)
                    val isCared = userType == "cared"

                    notificationHelper.sendSafeLeaveNotification(title, message, isCared)

                    // Obter o ID do cuidado da notificação
                    val caredId = remoteMessage.data["caredId"] ?: currentUser.uid

                    GeofenceEventBus.notifyAppGeofenceAlert(
                        caredId,
                        GeofenceEventBus.AlertType.LEFT_SAFE_ZONE
                    )
                }
        } else {
            notificationHelper.sendSafeLeaveNotification(title, message, false)
        }
    }

    private fun processDangerZone(remoteMessage: RemoteMessage) {
        val notificationHelper = NotificationHelper(this)
        val title = remoteMessage.notification?.title ?: "ALERTA DE PERIGO"
        val message = remoteMessage.notification?.body ?: "O utilizador entrou numa zona de perigo!"

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            FirebaseDatabase.getInstance().reference.child("users").child(currentUser.uid)
                .child("userType").get().addOnSuccessListener { snapshot ->
                    val userType = snapshot.getValue(String::class.java)
                    val isCared = userType == "cared"

                    notificationHelper.sendDangerZoneNotification(title, message, isCared)

                    // Obter o ID do cuidado da notificação
                    val caredId = remoteMessage.data["caredId"] ?: currentUser.uid

                    GeofenceEventBus.notifyAppGeofenceAlert(
                        caredId,
                        GeofenceEventBus.AlertType.ENTERED_DANGER_ZONE
                    )
                }
        } else {
            notificationHelper.sendDangerZoneNotification(title, message, false)
        }
    }

    private fun processSafeReturn(remoteMessage: RemoteMessage) {
        val notificationHelper = NotificationHelper(this)
        val title = remoteMessage.notification?.title ?: "Regresso à Zona Segura"
        val message = remoteMessage.notification?.body ?: "O utilizador voltou à zona segura."

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            FirebaseDatabase.getInstance().reference.child("users").child(currentUser.uid)
                .child("userType").get().addOnSuccessListener { snapshot ->
                    val userType = snapshot.getValue(String::class.java)
                    val isCared = userType == "cared"

                    notificationHelper.sendSafeReturnNotification(title, message, isCared)

                    // Obter o ID do cuidado da notificação
                    val caredId = remoteMessage.data["caredId"] ?: currentUser.uid

                    GeofenceEventBus.notifyAppGeofenceAlert(
                        caredId,
                        GeofenceEventBus.AlertType.RETURNED_TO_SAFE_ZONE
                    )
                }
        } else {
            notificationHelper.sendSafeReturnNotification(title, message, false)
        }
    }

    private fun processStandardNotification(remoteMessage: RemoteMessage) {
        val notificationHelper = NotificationHelper(this)
        val title = remoteMessage.notification?.title ?: "SafeSteps"
        val message = remoteMessage.notification?.body ?: "Nova notificação recebida."

        notificationHelper.sendStandardNotification(title, message)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCMService", "Novo token FCM: $token")

        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            tokenManager.saveUserToken(userId, token)
        } else {
            val sharedPrefs = getSharedPreferences("fcm_prefs", MODE_PRIVATE)
            sharedPrefs.edit().putString("pending_token", token).apply()
        }
    }
}