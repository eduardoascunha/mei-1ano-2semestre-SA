package com.example.safesteps.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.safesteps.R
import com.example.safesteps.SplashActivity
import com.example.safesteps.services.SilenceAlarmReceiver
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID_SERVICE = "safe_steps_service"
        private const val CHANNEL_ID_GEOFENCE = "geofence_channel"
        private const val CHANNEL_ID_ALERTS = "alerts_channel"

        const val TYPE_GEOFENCE_ALERT = "geofence_alert"
        const val TYPE_DANGER_ZONE_ALERT = "danger_zone_alert"
        const val TYPE_GEOFENCE_SAFE_RETURN = "geofence_safe_return"

        // IDs das notificações
        const val NOTIFICATION_ID_SERVICE = 1000
        const val NOTIFICATION_ID_SAFE_ZONE_ALERT = 1001
        const val NOTIFICATION_ID_DANGER_ZONE_ALERT = 1002
        const val NOTIFICATION_ID_SAFE_RETURN = 1003
        const val NOTIFICATION_ID_STANDARD = 1004

        private var instance: NotificationHelper? = null

        // MediaPlayer para reproduzir sons de alerta continuamente
        private var mediaPlayer: MediaPlayer? = null


        fun getInstance(context: Context): NotificationHelper {
            if (instance == null) {
                instance = NotificationHelper(context.applicationContext)
            }
            return instance!!
        }

        // Metodo para parar o som de alerta
        fun stopAlertSound() {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                reset()
                release()
            }
            mediaPlayer = null
        }
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val database = FirebaseDatabase.getInstance()
    private val tokenManager = TokenManager()

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal para serviço em primeiro plano
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Serviço de Localização",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Canal usado para o serviço de localização em primeiro plano"
                setSound(null, null)
                enableVibration(false)
            }

            // Canal para alertas de geofence
            val geofenceChannel = NotificationChannel(
                CHANNEL_ID_GEOFENCE,
                "Alertas de Zona",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas quando o utilizador sai da zona segura ou entra numa zona de perigo"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                enableLights(true)
                lightColor = context.getColor(R.color.danger_color)
            }

            // Canal para outras notificações
            val alertsChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Notificações Gerais",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificações gerais da aplicação"
            }

            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(geofenceChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    private fun startAlertSound(alertType: String) {
        stopAlertSound()
        try {
            // Selecionar o som de alerta apropriado com base no tipo
            val soundUri = when (alertType) {
                TYPE_DANGER_ZONE_ALERT -> {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                }
                TYPE_GEOFENCE_ALERT -> {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
                else -> {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }

        } catch (e: Exception) {
            Log.e("NotificationHelper", "Erro ao reproduzir som de alerta: ${e.message}")
        }
    }

    fun createServiceNotification(text: String): Notification {
        val intent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setContentTitle("SafeSteps")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()
    }

    fun sendSafeLeaveNotification(title: String, message: String, isCared: Boolean = true) {
        val intent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("NOTIFICATION_TYPE", TYPE_GEOFENCE_ALERT)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Criar intent para silenciar o alarme
        val silenceIntent = Intent(context, SilenceAlarmReceiver::class.java).apply {
            action = "ACTION_SILENCE_ALARM"
            putExtra("ALERT_TYPE", TYPE_GEOFENCE_ALERT)
        }

        val silencePendingIntent = PendingIntent.getBroadcast(
            context, 0, silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_GEOFENCE)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (isCared) {
            // Adicionar botão para silenciar o alarme
            notification.addAction(
                R.drawable.ic_notification,
                "Silenciar",
                silencePendingIntent
            )

            // Iniciar som de alerta
            startAlertSound(TYPE_GEOFENCE_ALERT)
        }

        notificationManager.notify(NOTIFICATION_ID_SAFE_ZONE_ALERT, notification.build())
    }

    fun sendDangerZoneNotification(title: String, message: String, isCared: Boolean = true) {
        val intent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("NOTIFICATION_TYPE", TYPE_DANGER_ZONE_ALERT)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Criar intent para silenciar o alarme
        val silenceIntent = Intent(context, SilenceAlarmReceiver::class.java).apply {
            action = "ACTION_SILENCE_ALARM"
            putExtra("ALERT_TYPE", TYPE_DANGER_ZONE_ALERT)
        }

        val silencePendingIntent = PendingIntent.getBroadcast(
            context, 0, silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_GEOFENCE)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (isCared) {
            // Adicionar botão para silenciar o alarme
            notification.addAction(
                R.drawable.ic_notification,
                "Silenciar",
                silencePendingIntent
            )

            startAlertSound(TYPE_DANGER_ZONE_ALERT)
        }

        notificationManager.notify(NOTIFICATION_ID_DANGER_ZONE_ALERT, notification.build())
    }

    fun sendSafeReturnNotification(title: String, message: String, isCared: Boolean = true) {
        val intent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("NOTIFICATION_TYPE", TYPE_GEOFENCE_SAFE_RETURN)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_GEOFENCE)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SAFE_RETURN, notification)

        if (isCared) {
            stopAlertSound()
        }
    }

    fun sendStandardNotification(title: String, message: String) {
        val intent = Intent(context, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_SERVICE, notification)
    }

    fun sendFCMNotificationToCaregivers(caredId: String, notificationType: String, location: Location? = null) {
        // Buscar todos os cuidadores associados a este cuidado
        database.reference.child("cared_caregiver").child(caredId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        // Obter nome do cuidado para incluir na notificação
                        database.reference.child("users").child(caredId)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(userSnapshot: DataSnapshot) {
                                    val firstName = userSnapshot.child("firstName").getValue(String::class.java) ?: ""
                                    val lastName = userSnapshot.child("lastName").getValue(String::class.java) ?: ""
                                    val caredName = "$firstName $lastName".trim()

                                    for (caregiverSnapshot in snapshot.children) {
                                        val caregiverId = caregiverSnapshot.key ?: continue
                                        sendFCMNotificationToCaregiver(caregiverId, caredId, caredName, notificationType, location)
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    Log.e("NotificationHelper", "Erro ao obter informações do cuidado: ${error.message}")
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("NotificationHelper", "Erro ao obter lista de cuidadores: ${error.message}")
                }
            })
    }

    private fun sendFCMNotificationToCaregiver(caregiverId: String, caredId: String, caredName: String, notificationType: String, location: Location? = null) {
        tokenManager.getUserToken(caregiverId) { token ->
            if (token != null) {
                // Preparar dados da notificação com base no tipo
                val (title, body) = when (notificationType) {
                    TYPE_GEOFENCE_ALERT -> {
                        Pair(
                            "Alerta de Zona Segura",
                            "$caredName saiu da zona segura!"
                        )
                    }
                    TYPE_DANGER_ZONE_ALERT -> {
                        Pair(
                            "ALERTA DE PERIGO",
                            "$caredName entrou numa zona de perigo!"
                        )
                    }
                    TYPE_GEOFENCE_SAFE_RETURN -> {
                        Pair(
                            "Regresso à Zona Segura",
                            "$caredName voltou à zona segura."
                        )
                    }
                    else -> {
                        Pair(
                            "Notificação SafeSteps",
                            "Nova notificação."
                        )
                    }
                }

                // Dados adicionais para a notificação
                val notificationData = hashMapOf(
                    "type" to notificationType,
                    "caregiverId" to caregiverId,
                    "caredId" to caredId,
                    "caredName" to caredName
                )

                // Adicionar localização se disponível
                if (location != null) {
                    notificationData["latitude"] = location.latitude.toString()
                    notificationData["longitude"] = location.longitude.toString()
                }

                // Criar notificação para o Firebase
                val notification = hashMapOf(
                    "to" to token,
                    "notification" to hashMapOf(
                        "title" to title,
                        "body" to body
                    ),
                    "data" to notificationData
                )

                database.reference.child("notifications").push().setValue(notification)
                    .addOnSuccessListener {
                        Log.d("NotificationHelper", "Notificação FCM enviada para o cuidador $caregiverId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("NotificationHelper", "Erro ao enviar notificação FCM: ${e.message}")
                    }
            } else {
                Log.e("NotificationHelper", "Token FCM não encontrado para o cuidador $caregiverId")
            }
        }
    }
}