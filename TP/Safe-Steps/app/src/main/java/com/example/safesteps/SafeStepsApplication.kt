package com.example.safesteps

import android.app.Application
import com.example.safesteps.utils.NotificationHelper
import com.google.firebase.database.FirebaseDatabase
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.example.safesteps.utils.GeofenceEventBus
import com.example.safesteps.services.LocationService
import com.example.safesteps.utils.TokenManager


class SafeStepsApplication : Application(), GeofenceEventBus.AppGeofenceStatusListener {
    companion object {
        lateinit var instance: SafeStepsApplication
            private set

        // Constantes para o estado da aplicação
        const val APP_STATE_NORMAL = 0
        const val APP_STATE_SAFE_ZONE_ALERT = 1
        const val APP_STATE_DANGER_ZONE_ALERT = 2
    }

    lateinit var database: FirebaseDatabase
        private set
    lateinit var auth: FirebaseAuth
        private set
    lateinit var notificationHelper: NotificationHelper
        private set

    var currentAppState = APP_STATE_NORMAL
        private set

    private val caredAlertStates = mutableMapOf<String, Int>()

    interface AppStateListener {
        fun onAppStateChanged(newState: Int)
    }

    private val appStateListeners = mutableListOf<AppStateListener>()

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = FirebaseDatabase.getInstance()
        auth = FirebaseAuth.getInstance()
        notificationHelper = NotificationHelper(this)

        configureFCM()

        GeofenceEventBus.registerAppListener(this)

        startLocationServiceIfNeeded()
    }

    private fun configureFCM() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d("SafeStepsApplication", "FCM Token: $token")

                val userId = auth.currentUser?.uid
                if (userId != null) {
                    TokenManager().saveUserToken(userId, token)
                } else {
                    val sharedPrefs = getSharedPreferences("fcm_prefs", MODE_PRIVATE)
                    sharedPrefs.edit().putString("pending_token", token).apply()
                }
            } else {
                Log.e("SafeStepsApplication", "Falha ao obter token FCM", task.exception)
            }
        }
    }

    fun startLocationServiceIfNeeded() {
        if (auth.currentUser != null) {
            val userId = auth.currentUser!!.uid

            database.reference.child("users").child(userId).child("type")
                .get().addOnSuccessListener { snapshot ->
                    val userType = snapshot.getValue(String::class.java)
                    if (userType == "cared") {
                        startLocationService()
                    }
                }
        }
    }

    fun startLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    fun registerAppStateListener(listener: AppStateListener) {
        if (!appStateListeners.contains(listener)) {
            appStateListeners.add(listener)
            listener.onAppStateChanged(currentAppState)
        }
    }

    fun unregisterAppStateListener(listener: AppStateListener) {
        appStateListeners.remove(listener)
    }

    private fun setAppState(newState: Int) {
        if (currentAppState != newState) {
            currentAppState = newState
            appStateListeners.forEach { it.onAppStateChanged(newState) }
        }
    }

    fun checkCaredGeofenceStatus(caregiverId: String) {
        database.reference.child("caregiver_cared").child(caregiverId)
            .get()
            .addOnSuccessListener { snapshot ->
                var highestAlertLevel = APP_STATE_NORMAL

                if (snapshot.exists() && snapshot.childrenCount > 0) {
                    var countChecked = 0
                    val totalToCheck = snapshot.childrenCount

                    snapshot.children.forEach { caredSnapshot ->
                        val caredId = caredSnapshot.key ?: return@forEach

                        // Verificar violações de geofence para este cuidado
                        database.reference.child("geofence_violations").child(caredId)
                            .get()
                            .addOnSuccessListener { violationSnapshot ->
                                // Verificar se está em zona de perigo
                                val inDangerZone = violationSnapshot.child("danger_zone").getValue(Boolean::class.java) ?: false
                                // Verificar se está fora da zona segura
                                val outsideSafeZone = violationSnapshot.child("safe_zone").getValue(Boolean::class.java) ?: false

                                if (inDangerZone && highestAlertLevel < APP_STATE_DANGER_ZONE_ALERT) {
                                    highestAlertLevel = APP_STATE_DANGER_ZONE_ALERT
                                } else if (outsideSafeZone && highestAlertLevel < APP_STATE_SAFE_ZONE_ALERT) {
                                    highestAlertLevel = APP_STATE_SAFE_ZONE_ALERT
                                }

                                countChecked++
                                if (countChecked >= totalToCheck) {
                                    // Atualizar o estado da aplicação baseado no nível mais alto de alerta
                                    setAppState(highestAlertLevel)
                                }
                            }
                            .addOnFailureListener {
                                countChecked++
                                if (countChecked >= totalToCheck) {
                                    setAppState(highestAlertLevel)
                                }
                            }
                    }
                }
            }
    }


    fun updateCaredAlertState(caredId: String, alertState: Int) {
        val previousHighestState = caredAlertStates.values.maxOrNull() ?: APP_STATE_NORMAL

        if (alertState == APP_STATE_NORMAL) {
            caredAlertStates.remove(caredId)
        } else {
            caredAlertStates[caredId] = alertState
        }

        // Determinar o novo estado mais alto
        val newHighestState = caredAlertStates.values.maxOrNull() ?: APP_STATE_NORMAL

        if (previousHighestState != newHighestState) {
            setAppState(newHighestState)
        } else {
            // Mesmo que o estado geral não mude, notificar os listeners para atualizar a UI
            // para mostrar os novos detalhes de quais cuidados estão em alerta
            appStateListeners.forEach { it.onAppStateChanged(newHighestState) }
        }
    }

    override fun onGeofenceAlert(caredId: String, alertType: GeofenceEventBus.AlertType) {
        when (alertType) {
            GeofenceEventBus.AlertType.LEFT_SAFE_ZONE -> {
                updateCaredAlertState(caredId, APP_STATE_SAFE_ZONE_ALERT)
            }
            GeofenceEventBus.AlertType.ENTERED_DANGER_ZONE -> {
                updateCaredAlertState(caredId, APP_STATE_DANGER_ZONE_ALERT)
            }
            GeofenceEventBus.AlertType.RETURNED_TO_SAFE_ZONE -> {
                updateCaredAlertState(caredId, APP_STATE_NORMAL)
            }
        }
    }

    fun stopLocationService() {
        val serviceIntent = Intent(this, LocationService::class.java)
        stopService(serviceIntent)
    }
}