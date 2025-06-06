package com.example.safesteps.services


import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.safesteps.utils.Geofence
import com.example.safesteps.utils.GeofenceEventBus
import com.example.safesteps.utils.GeofenceManager
import com.example.safesteps.utils.NotificationHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.android.gms.maps.model.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlin.text.compareTo

class LocationService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var database: FirebaseDatabase
    private lateinit var userId: String
    private lateinit var notificationHelper: NotificationHelper
    private val geofences = mutableListOf<Geofence>()
    private val geofenceManager = GeofenceManager(FirebaseDatabase.getInstance())

    private val NOTIFICATION_ID = NotificationHelper.NOTIFICATION_ID_SERVICE
    private var isInsideSafeZone = true
    private var isInsideDangerZone = false
    private var hasActiveSafeZoneAlert = false
    private var hasActiveDangerZoneAlert = false

    private var safeZoneExitHandler: Handler? = null
    private var safeZoneExitRunnable: Runnable? = null

    // Configurações para histórico de localização
    private var lastHistorySavedTime = 0L

    //private val LOCATION_HISTORY_INTERVAL = 3 * 60 * 1000L // 3 minutos em milissegundos
    private val LOCATION_HISTORY_INTERVAL = 5000L

    //private val MAX_HISTORY_ENTRIES_PER_DAY = 480 // 24 horas × 20 entradas por hora (uma a cada 3 min)
    private val MAX_HISTORY_ENTRIES_PER_DAY = 17280


    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        database = FirebaseDatabase.getInstance()
        notificationHelper = NotificationHelper(this)

        val firebaseAuth = FirebaseAuth.getInstance()
        userId = firebaseAuth.currentUser?.uid ?: ""

        if (userId.isEmpty()) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, notificationHelper.createServiceNotification("A sua localização está a ser monitorada..."))

        loadGeofencesForUser()
        setupLocationUpdates()
    }

    private fun loadGeofencesForUser() {
        geofenceManager.loadGeofences(userId) { loadedGeofences, error ->
            if (error == null) {
                geofences.clear()
                geofences.addAll(loadedGeofences)

                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let { checkGeofenceStatus(it) }
                }
            }
        }

        // Adicionar listener para mudanças nas geofences
        geofenceManager.registerChangeListener(userId, object : GeofenceManager.GeofenceChangeListener {
            override fun onGeofenceAdded(geofence: Geofence) {
                if (!geofences.any { it.id == geofence.id }) {
                    geofences.add(geofence)
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        location?.let { checkGeofenceStatus(it) }
                    }
                }
            }

            override fun onGeofenceRemoved(geofenceId: String) {
                geofences.removeAll { it.id == geofenceId }
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let { checkGeofenceStatus(it) }
                }
            }

            override fun onGeofenceListUpdated(updatedGeofences: List<Geofence>) {
                geofences.clear()
                geofences.addAll(updatedGeofences)
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let { checkGeofenceStatus(it) }
                }
            }
        })
    }

    private fun setupLocationUpdates() {
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).apply {
            setMinUpdateIntervalMillis(2000L)
            setMinUpdateDistanceMeters(5f)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    updateLocationInFirebase(location)
                    checkGeofenceStatus(location)

                    // Guardar no historico
                    saveLocationToHistory(location)

                    // Notificar através do EventBus
                    GeofenceEventBus.notifyLocationUpdate(userId, location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("LocationService", "Erro de permissão: ${e.message}")
            stopSelf()
        }
    }

    private fun updateLocationInFirebase(location: Location) {
        val locationData = hashMapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "timestamp" to System.currentTimeMillis(),
            "inside_safe_zone" to isInsideSafeZone,
            "inside_danger_zone" to isInsideDangerZone
        )

        database.reference.child("user_locations")
            .child(userId)
            .setValue(locationData)
    }

    private fun saveLocationToHistory(location: Location) {
        val currentTime = System.currentTimeMillis()

        // Verificar se já passou o intervalo desde a última vez que guardamos
        if (currentTime - lastHistorySavedTime >= LOCATION_HISTORY_INTERVAL) {
            lastHistorySavedTime = currentTime

            // Criar um ID único com base no timestamp para garantir ordenação
            val historyId = currentTime.toString()

            // Preparar os dados para guardar
            val historyData = hashMapOf(
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "timestamp" to currentTime,
                "inside_safe_zone" to isInsideSafeZone,
                "inside_danger_zone" to isInsideDangerZone
            )

            // Guarda na firebase
            database.reference.child("location_history")
                .child(userId)
                .child(historyId)
                .setValue(historyData)
                .addOnSuccessListener {
                    Log.d("LocationService", "Localização guardada no histórico com sucesso")

                    // Limpar dados antigos para não sobrecarregar a bd
                    cleanupOldHistoryData()
                }
                .addOnFailureListener { e ->
                    Log.e("LocationService", "Erro ao guardar localização no histórico: ${e.message}")
                }
        }
    }

    private fun cleanupOldHistoryData() {
        // Calcular a data de 30 dias atrás
        val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)

        // Limitar o número de entradas por dia para evitar sobrecarga
        database.reference.child("location_history")
            .child(userId)
            .orderByChild("timestamp")
            .endAt(thirtyDaysAgo.toDouble())
            .limitToLast(500) // Só para garantir que não vamos remover muita coisa de uma vez
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        for (childSnapshot in snapshot.children) {
                            childSnapshot.ref.removeValue()
                        }
                        Log.d("LocationService", "Dados antigos de histórico removidos")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LocationService", "Erro ao limpar histórico antigo: ${error.message}")
                }
            })
    }

    private fun checkGeofenceStatus(location: Location) {
        val point = LatLng(location.latitude, location.longitude)
        val wasInsideSafeZone = isInsideSafeZone
        val wasInsideDangerZone = isInsideDangerZone

        isInsideSafeZone = geofenceManager.isInsideSafeZone(point, geofences)
        isInsideDangerZone = geofenceManager.isInsideDangerZone(point, geofences)

        database.reference.child("geofence_violations").child(userId).child("safe_zone").setValue(!isInsideSafeZone)
        database.reference.child("geofence_violations").child(userId).child("danger_zone").setValue(isInsideDangerZone)

        if (wasInsideDangerZone && !isInsideDangerZone) {
            handleDangerZoneExit(location)
        }

        if (wasInsideSafeZone != isInsideSafeZone) {
            handleSafeZoneStatusChange(location, isInsideSafeZone)
        }

        if (!wasInsideDangerZone && isInsideDangerZone) {
            handleDangerZoneEntry(location)
        }
    }

    private fun handleSafeZoneStatusChange(location: Location, isInside: Boolean) {
        if (isInside) {
            // Retornou à zona segura
            safeZoneExitHandler?.removeCallbacks(safeZoneExitRunnable!!)
            hasActiveSafeZoneAlert = false
            hasActiveDangerZoneAlert = false

            // Notificação para cuidados
            notificationHelper.sendSafeReturnNotification(
                "Regresso à Zona Segura",
                "Você voltou para a zona segura."
            )

            // Enviar FCM para os cuidadores
            notificationHelper.sendFCMNotificationToCaregivers(
                userId,
                NotificationHelper.TYPE_GEOFENCE_SAFE_RETURN,
                location
            )

            // Notificar o EventBus também para a interface atualizar
            GeofenceEventBus.notifyAppGeofenceAlert(
                userId,
                GeofenceEventBus.AlertType.RETURNED_TO_SAFE_ZONE
            )
        } else {
            if (!isInsideDangerZone && !hasActiveSafeZoneAlert) {
                hasActiveSafeZoneAlert = true

                notificationHelper.sendSafeLeaveNotification(
                    "ALERTA!",
                    "Você saiu da zona segura!",
                )

                GeofenceEventBus.notifyAppGeofenceAlert(
                    userId,
                    GeofenceEventBus.AlertType.LEFT_SAFE_ZONE
                )

                // Iniciar contagem de 10s antes de enviar alerta para o cuidador
                safeZoneExitHandler = Handler(Looper.getMainLooper())
                safeZoneExitRunnable = Runnable {
                    if (!isInsideSafeZone && !isInsideDangerZone) {

                        notificationHelper.sendFCMNotificationToCaregivers(
                            userId,
                            NotificationHelper.TYPE_GEOFENCE_ALERT,
                            location
                        )
                    }
                }
                safeZoneExitHandler?.postDelayed(safeZoneExitRunnable!!, 10000L) // 10 segundos
            }
        }
    }

    private fun handleDangerZoneEntry(location: Location) {
        if (!hasActiveDangerZoneAlert) {
            hasActiveDangerZoneAlert = true

            notificationHelper.sendDangerZoneNotification(
                "PERIGO!",
                "Você entrou em uma zona de perigo!",
            )

            // Enviar FCM para os cuidadores
            notificationHelper.sendFCMNotificationToCaregivers(
                userId,
                NotificationHelper.TYPE_DANGER_ZONE_ALERT,
                location
            )

            GeofenceEventBus.notifyAppGeofenceAlert(
                userId,
                GeofenceEventBus.AlertType.ENTERED_DANGER_ZONE
            )
        }
    }

    private fun handleDangerZoneExit(location: Location) {
        hasActiveDangerZoneAlert = false

        // Se saiu da zona de perigo mas ainda não está na zona segura
        if (!isInsideSafeZone) {
            // Verificar se já tem um alerta de zona segura ativo
            if (!hasActiveSafeZoneAlert) {
                hasActiveSafeZoneAlert = true

                notificationHelper.sendSafeLeaveNotification(
                    "ALERTA!",
                    "Você saiu da zona de perigo, mas ainda está fora da zona segura!",
                )

                // Enviar FCM para os cuidadores
                notificationHelper.sendFCMNotificationToCaregivers(
                    userId,
                    NotificationHelper.TYPE_GEOFENCE_ALERT,
                    location
                )

                GeofenceEventBus.notifyAppGeofenceAlert(
                    userId,
                    GeofenceEventBus.AlertType.LEFT_SAFE_ZONE
                )
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)

            geofences.clear()

            // Limpar todos os alertas ativos
            isInsideSafeZone = true
            isInsideDangerZone = false
            hasActiveSafeZoneAlert = false
            hasActiveDangerZoneAlert = false

            // remover handlers pendentes
            safeZoneExitHandler?.removeCallbacks(safeZoneExitRunnable ?: return)
            safeZoneExitHandler = null
            safeZoneExitRunnable = null

            // Cancelar qualquer listener de mudança nas geofences
            userId?.let { id ->
                geofenceManager.unregisterChangeListener(id)
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Erro ao destruir serviço: ${e.message}")
        }
    }
}