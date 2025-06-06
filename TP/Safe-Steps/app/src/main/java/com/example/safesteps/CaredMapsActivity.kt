package com.example.safesteps

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.safesteps.databinding.ActivityCaredMapsBinding
import com.example.safesteps.models.NavigationDestination
import com.example.safesteps.utils.Geofence
import com.example.safesteps.utils.GeofenceEventBus
import com.example.safesteps.utils.GeofenceManager
import com.example.safesteps.utils.GeofencePoint
import com.example.safesteps.utils.GeofenceType
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.maps.android.PolyUtil
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Timer
import java.util.TimerTask

class CaredMapsActivity : BaseActivity(), OnMapReadyCallback, GeofenceEventBus.GeofenceZoneListener, GeofenceEventBus.GeofenceStatusListener{
    private var mMap: GoogleMap? = null
    private lateinit var binding: ActivityCaredMapsBinding
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var geofenceManager: GeofenceManager

    private var currentMarker: Marker? = null
    private val geofences = mutableListOf<Geofence>()
    private var isInsideSafeZone = true
    private var currentRoute: MutableList<LatLng> = mutableListOf()
    private var routePolyline: Polyline? = null
    private var currentDestination: LatLng? = null
    private var lastRouteUpdateLocation: LatLng? = null
    private val ROUTE_RECALCULATION_THRESHOLD = 50f // meters
    private val polygons = mutableMapOf<String, Polygon>()

    private var navigationStartTime: Long? = null
    private var totalDistanceMeters = 0f
    private var lastLocationForDistance: LatLng? = null
    private var initialLocation: LatLng? = null
    private var destino: NavigationDestination? = null

    private var lifePoints = 100
    private val MAX_LIFE_POINTS = 100
    private val PENALTY_POINTS = 10
    private var originalRoute: List<LatLng>? = null

    private var geoLifePoints = 100
    private val GEO_MAX_LIFE = 100
    private val GEO_RECOVERY = 2
    private val GEO_PENALTY_SAFE = 3
    private val GEO_PENALTY_DANGER = 7
    private var geofenceTimer: Timer? = null
    private var navigationActive = false
    private var geofenceZeroAlreadyHandled = false

    private lateinit var caredId: String

    private var currentZoneState = "SAFE"

    private var currentTravelMode = "driving"

    override fun onCreate(savedInstanceState: Bundle?) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        GeofenceEventBus.registerZoneListener(this)

        if (currentUser != null) {
            caredId = currentUser.uid
        } else {
            super.onCreate(savedInstanceState)
            Toast.makeText(this, "Utilizador não autenticado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        super.onCreate(savedInstanceState)

        binding = ActivityCaredMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.lifeBarGeofence.visibility = View.VISIBLE
        binding.lifeBar.visibility = View.GONE

        val database = FirebaseDatabase.getInstance()
        geofenceManager = GeofenceManager(database)

        // Registrar como listener de mudanças de geofence
        geofenceManager.registerChangeListener(caredId, object : GeofenceManager.GeofenceChangeListener {
            override fun onGeofenceAdded(geofence: Geofence) {
                if (!geofences.any { it.id == geofence.id }) {
                    geofences.add(geofence)
                    mMap?.let { map ->
                        val polygon = geofenceManager.drawGeofenceOnMap(geofence, map)
                        polygon?.let { polygons[geofence.id] = it }
                    }
                }
            }

            override fun onGeofenceRemoved(geofenceId: String) {
                geofences.removeAll { it.id == geofenceId }
                polygons[geofenceId]?.remove()
                polygons.remove(geofenceId)
            }

            override fun onGeofenceListUpdated(updatedGeofences: List<Geofence>) {
                geofences.clear()
                geofences.addAll(updatedGeofences)
                redrawAllGeofences()
            }

        })


        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.fabViewDestinations.setOnClickListener {
            showPaths()
        }

        binding.fabCancelNavigation.setOnClickListener {
            clearNavigation()
            Toast.makeText(this, "Navegação cancelada", Toast.LENGTH_SHORT).show()
        }

        checkLocationPermissions()
        setupAlertViews()
        createNotificationChannel()
        startGeofenceLifeLoop()
    }

    override fun onZoneStateChanged(state: GeofenceEventBus.ZoneState) {
        currentZoneState = when (state) {
            is GeofenceEventBus.ZoneState.SAFE -> "SAFE"
            is GeofenceEventBus.ZoneState.UNSAFE -> "UNSAFE"
            is GeofenceEventBus.ZoneState.DANGER -> "DANGER"
        }
    }

    private fun startGeofenceLifeLoop() {
        geofenceTimer = Timer()
        geofenceTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    if (!navigationActive) {
                        updateGeofenceLifeBar()
                    }
                }
            }
        }, 0, 2000) // a cada 2 segundos
    }

    private fun updateGeofenceLifeBar() {
        val state = currentZoneState // enum ou string: "SAFE", "UNSAFE", "DANGER"

        when (state) {
            "SAFE" -> {
                geoLifePoints = (geoLifePoints + GEO_RECOVERY).coerceAtMost(GEO_MAX_LIFE)
            }
            "UNSAFE" -> {
                geoLifePoints = (geoLifePoints - GEO_PENALTY_SAFE).coerceAtLeast(0)
                if (geoLifePoints == 0 && !geofenceZeroAlreadyHandled) {
                    geofenceZeroAlreadyHandled = true
                    handleGeofenceLifeZero()
                }
            }
            "DANGER" -> {
                geoLifePoints = (geoLifePoints - GEO_PENALTY_DANGER).coerceAtLeast(0)
                if (geoLifePoints == 0 && !geofenceZeroAlreadyHandled) {
                    geofenceZeroAlreadyHandled = true
                    handleGeofenceLifeZero()
                }
            }
        }

        binding.lifeBarGeofence.progress = geoLifePoints

        if (geoLifePoints > 0) {
            geofenceZeroAlreadyHandled = false
        }
    }

    private fun handleGeofenceLifeZero() {
        // Notificação local para o cuidado
        val builder = NotificationCompat.Builder(this, "navigation_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Atenção!")
            .setContentText("A sua barra de segurança chegou a zero. O cuidador será avisado.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(this)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(202, builder.build())
        }

        // Registar evento no Firebase
        saveGeofenceLifeZeroEvent()
    }

    private fun saveGeofenceLifeZeroEvent() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance().reference
            .child("navigation_history")
            .child(userId)
            .push()

        val info = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "isGeofenceAlert" to true,
            "destination_name" to "",
            "duration_minutes" to 0,
            "distance_km" to "",
            "life_points" to 0
        )

        ref.setValue(info)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Navigation Updates"
            val descriptionText = "Notificações relacionadas com a navegação"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("navigation_channel", name, importance).apply {
                description = descriptionText
            }

            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onLocationUpdate(caredId: String, location: Location) {
        if (caredId == this.caredId) {
            runOnUiThread {
                updateMarkerOnMap(LatLng(location.latitude, location.longitude))
            }
        }
    }

    override fun setupAlertViews() {
        alertBanner = findViewById(R.id.alertBanner)
        btnSilenceAlert = findViewById(R.id.btnSilenceAlert)

        btnSilenceAlert?.setOnClickListener {
            stopAlertSound()
            btnSilenceAlert?.visibility = View.GONE
        }

        updateAlertBanner(SafeStepsApplication.instance.currentAppState)
    }

    private fun showPaths() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val destiniesRef = FirebaseDatabase.getInstance()
            .getReference("navigation_destinations")
            .child(userId)

        destiniesRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val destenies = mutableListOf<NavigationDestination>()

                for (child in snapshot.children){
                    val destiny = child.getValue(NavigationDestination::class.java)
                    destiny?.let { destenies.add(it) }
                }

                if (destenies.isEmpty()){
                    Toast.makeText(this@CaredMapsActivity, "Nenhum destino disponível", Toast.LENGTH_SHORT).show()
                    return
                }
                showDialogSelected(destenies)
            }

            private fun showDialogSelected(destenies: MutableList<NavigationDestination>) {
                val names = destenies.map{it.name}.toTypedArray()

                MaterialAlertDialogBuilder(this@CaredMapsActivity)
                    .setTitle("Selecione um destino")
                    .setItems(names) { _, which ->
                        val destinoSelecionado = destenies[which]
                        showDestination(destinoSelecionado)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            private fun askForTravelMode(currentLatLng: LatLng, destinationLatLng: LatLng, onModeChosen: (String) -> Unit) {
                val options = arrayOf("A pé", "De carro")
                AlertDialog.Builder(this@CaredMapsActivity)
                    .setTitle("Escolha o modo de transporte")
                    .setItems(options) { _, which ->
                        val mode = if (which == 0) "walking" else "driving"
                        currentTravelMode = mode
                        onModeChosen(mode)
                    }
                    .show()
            }

            @SuppressLint("MissingPermission")
            private fun showDestination(destiny: NavigationDestination) {
                val destinationLatLng = LatLng(destiny.latitude, destiny.longitude)
                destino = destiny
                currentDestination = destinationLatLng
                mMap?.addMarker(
                    MarkerOptions()
                        .position(destinationLatLng)
                        .title(destiny.name)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
                mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(destinationLatLng, 16f))

                MaterialAlertDialogBuilder(this@CaredMapsActivity)
                    .setTitle("Inciar Navegação")
                    .setMessage("Deseja iniciar o caminho até '${destiny.name}'?")
                    .setPositiveButton("Sim") { _, _ ->
                        //Quando o utilizador confirmar, obter localização e desenhar rota
                        navigationActive = true

                        binding.lifeBarGeofence.visibility = View.GONE
                        binding.lifeBar.visibility = View.VISIBLE
                        lifePoints = MAX_LIFE_POINTS
                        binding.lifeBar.progress = lifePoints

                        fusedLocationProviderClient.lastLocation.addOnSuccessListener { location ->
                            location?.let {
                                val currentLatLng = LatLng(it.latitude, it.longitude)
                                initialLocation = currentLatLng

                                navigationStartTime = System.currentTimeMillis()
                                totalDistanceMeters = 0f
                                lastLocationForDistance = currentLatLng

                                lastRouteUpdateLocation = currentLatLng

                                lifePoints = MAX_LIFE_POINTS
                                binding.lifeBar.progress = lifePoints
                                binding.lifeBar.visibility = View.VISIBLE
                                //Buscar e desenhar
                                askForTravelMode(currentLatLng, destinationLatLng) { mode ->
                                    fetchRoute(currentLatLng, destinationLatLng, mode) { routePoints, durationText ->
                                        drawRouteOnMap(routePoints)
                                        originalRoute = routePoints.toList() // copy
                                        binding.tvEta.text = "Tempo estimado: $durationText"
                                        binding.fabCancelNavigation.visibility = View.VISIBLE
                                    }
                                }
                                //Ajustar o zoom
                                mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(destinationLatLng, 16f))
                            } ?: run {
                                //Failure
                                Toast.makeText(this@CaredMapsActivity, "Não foi possível obter a localização atual", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@CaredMapsActivity, "Error ao carregar destinos: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            setupLocationServices()
            initMap()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun setupLocationServices() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun initMap() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        try {
            mMap?.uiSettings?.apply {
                isZoomControlsEnabled = true
                isMyLocationButtonEnabled = true
            }

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                mMap?.isMyLocationEnabled = true

                // Obter localização atual
                fusedLocationProviderClient.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        val latLng = LatLng(it.latitude, it.longitude)
                        updateMarkerOnMap(latLng)
                    }
                }

                // Carregar geofences
                loadGeofences()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao inicializar o mapa: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("CaredMapsActivity", "Erro ao inicializar o mapa", e)
        }
    }

    private fun loadGeofences() {
        geofenceManager.loadGeofences(caredId) { loadedGeofences, error ->
            if (error == null) {
                geofences.clear()
                geofences.addAll(loadedGeofences)
                redrawAllGeofences()
            } else {
                Toast.makeText(this, "Erro ao carregar geofences: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateMarkerOnMap(latLng: LatLng) {
        currentMarker?.remove()
        currentMarker = mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("A sua Localização")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
        )
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
    }

    private fun redrawAllGeofences() {
        for (polygon in polygons.values) {
            polygon.remove()
        }
        polygons.clear()

        mMap?.let { map ->
            for (geofence in geofences) {
                val polygon = geofenceManager.drawGeofenceOnMap(geofence, map)
                polygon?.let { polygons[geofence.id] = it }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Registrar no EventBus para receber atualizações de localização
        GeofenceEventBus.register(caredId, this)

        // Solicitar atualizações de localização em tempo real
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()

        // Desregistrar do EventBus ao sair da tela
        GeofenceEventBus.unregister(caredId, this)

        // Parar atualizações de localização
        stopLocationUpdates()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).apply {
            setMinUpdateIntervalMillis(2000L)
            setMinUpdateDistanceMeters(5f)
        }.build()

        try {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationProviderClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            Log.e("MapsActivity", "Erro ao solicitar atualizações de localização: ${e.message}")
        }
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    val latLng = LatLng(location.latitude, location.longitude)
                    updateUserLocation(latLng)
                    checkGeofenceStatus(latLng)

                    // Atualizar localização no Firebase
                    updateLocationInFirebase(location)

                    checkAndUpdate(latLng)
                }
            }

            private fun checkAndUpdate(currentLatLng: LatLng) {
                //If not navigating do nothing
                if (currentDestination == null || lastRouteUpdateLocation == null) return

                //Calculate distance from last route update location
                val distance = FloatArray(1)
                Location.distanceBetween(
                    currentLatLng.latitude, currentLatLng.longitude,
                    lastRouteUpdateLocation!!.latitude, lastRouteUpdateLocation!!.longitude,
                    distance
                )

                val isOffRoute = isUserOffOriginalRoute(currentLatLng)
                //Penalize for detour
                if (isOffRoute) {
                    lifePoints = (lifePoints - PENALTY_POINTS).coerceAtLeast(0)
                    binding.lifeBar.progress = lifePoints

                    sendRouteUpdateNotification()
                }

                //If significantly, recalculate the route
                if (distance[0] > ROUTE_RECALCULATION_THRESHOLD){
                    lastLocationForDistance?.let { last ->
                        val segment = FloatArray(1)
                        Location.distanceBetween(
                            last.latitude, last.longitude,
                            currentLatLng.latitude, currentLatLng.longitude,
                            segment
                        )
                        totalDistanceMeters += segment[0]
                    }
                    lastLocationForDistance = currentLatLng

                    lastRouteUpdateLocation = currentLatLng

                    //Recalculate route to destination
                    fetchRoute(currentLatLng, currentDestination!!, currentTravelMode) { routePoints, durationText ->
                        drawRouteOnMap(routePoints)
                        binding.tvEta.text = "Tempo estimado: $durationText"

                        // Atualizar o caminho original com o novo
                        originalRoute = routePoints.toList()
                    }

                }else{
                    //Minor movement -> update existing route progress
                    updateRouteProgress(currentLatLng)
                }

                //Check if destination achieved
                val dest = currentDestination

                if (dest != null) {
                    val toDestination = FloatArray(1)
                    Location.distanceBetween(
                        currentLatLng.latitude, currentLatLng.longitude,
                        dest.latitude, dest.longitude,
                        toDestination
                    )
                    if (toDestination[0] < 20) {
                        showArrivalDialog()
                        clearNavigation()
                        return
                    }
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationProviderClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private fun sendRouteUpdateNotification() {
        val builder = NotificationCompat.Builder(this, "navigation_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rota atualizada")
            .setContentText("Foi definido um novo caminho. Siga a nova rota até ao destino.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(this)

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return // ou pedir permissões se estiveres no Android 13+
        }

        notificationManager.notify(101, builder.build())
    }

    private fun isUserOffOriginalRoute(currentLatLng: LatLng): Boolean {
        val path = originalRoute ?: return false
        val thresholdMeters = 25.0

        return path.none { point ->
            val distance = FloatArray(1)
            Location.distanceBetween(
                currentLatLng.latitude, currentLatLng.longitude,
                point.latitude, point.longitude,
                distance
            )
            distance[0] <= thresholdMeters
        }
    }

    private fun updateRouteProgress(currentLatLng: LatLng) {
        if (currentRoute.isEmpty()) return

        //Remover pontos já ultrapassados
        while(currentRoute.size > 1){
            val nextPoint = currentRoute[1]
            val distance = FloatArray(1)
            Location.distanceBetween(
                currentLatLng.latitude, currentLatLng.longitude,
                nextPoint.latitude, nextPoint.longitude,
                distance
            )

            if(distance[0] < 20) {
                currentRoute.removeAt(0)
            }else{
                break
            }
        }

        //Atualiza a Polyline com os pontos restantes
        routePolyline?.points = currentRoute
    }

    private fun updateLocationInFirebase(location: Location) {
        val locationData = hashMapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "timestamp" to System.currentTimeMillis()
        )

        FirebaseDatabase.getInstance().reference
            .child("user_locations")
            .child(caredId)
            .setValue(locationData)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                val latLng = LatLng(location.latitude, location.longitude)
                updateMarkerOnMap(latLng)   // Em CaredMapsActivity
            }
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
    }

    private fun checkGeofenceStatus(latLng: LatLng) {
        if (currentDestination != null) return

        var newInsideSafeZone = false

        // Verificar se o usuário está dentro de alguma zona segura
        for (geofence in geofences) {
            if (geofence.type == GeofenceType.SAFE && isPointInPolygon(latLng, geofence.points)) {
                newInsideSafeZone = true
                break
            }
        }

        // Se mudou de status (entrou ou saiu de uma zona segura)
        if (newInsideSafeZone != isInsideSafeZone) {
            isInsideSafeZone = newInsideSafeZone

        }

        var isInsideDangerZone = false

        for (geofence in geofences) {
            if (geofence.type == GeofenceType.DANGER && isPointInPolygon(latLng, geofence.points)) {
                isInsideDangerZone = true
                break
            }
        }

        when {
            isInsideDangerZone -> {
                GeofenceEventBus.notifyZoneStateChange(GeofenceEventBus.ZoneState.DANGER)
            }
            isInsideSafeZone -> {
                GeofenceEventBus.notifyZoneStateChange(GeofenceEventBus.ZoneState.SAFE)
            }
            else -> {
                GeofenceEventBus.notifyZoneStateChange(GeofenceEventBus.ZoneState.UNSAFE)
            }
        }


    }

    private fun showSafeZoneAlert() {
        if (!isFinishing) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Alerta de Zona Segura")
                .setMessage("Você está fora da zona segura. Por favor, volte para uma área segura.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun updateGeofenceViolationStatus(isViolation: Boolean) {
        FirebaseDatabase.getInstance().reference
            .child("geofence_violations")
            .child(caredId)
            .setValue(isViolation)
    }

    private fun isPointInPolygon(point: LatLng, polygon: List<GeofencePoint>): Boolean {
        var inside = false
        var j = polygon.size - 1

        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]

            if ((pi.longitude > point.longitude) != (pj.longitude > point.longitude) &&
                (point.latitude < (pj.latitude - pi.latitude) * (point.longitude - pi.longitude) /
                        (pj.longitude - pi.longitude) + pi.latitude)
            ) {
                inside = !inside
            }
            j = i
        }

        return inside
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupLocationServices()
                initMap()
            } else {
                Toast.makeText(
                    this,
                    "Esta aplicação necessita de permissões de localização para funcionar corretamente",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    fun fetchRoute(currentLatLng: LatLng, destinationLatLng: LatLng, mode: String = "driving", onRouteReady: (List<LatLng>, String) -> Unit){
        val apiKey = "your_key"
        val origin = "${currentLatLng.latitude},${currentLatLng.longitude}"
        val destination = "${destinationLatLng.latitude},${destinationLatLng.longitude}"
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=$origin&destination=$destination&mode=$mode&key=$apiKey"
        Log.d("RouteDebug", "Request URL: $url")

        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("RouteError", "Failed to get directions ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: return
                val jsonObject = JSONObject(json)
                val routes = jsonObject.getJSONArray("routes")
                val legs = routes.getJSONObject(0).getJSONArray("legs")
                val durationText = legs.getJSONObject(0).getJSONObject("duration").getString("text")
                if (routes.length() == 0) return

                val overviewPolyline = routes.getJSONObject(0)
                    .getJSONObject("overview_polyline")
                    .getString("points")

                val path = PolyUtil.decode(overviewPolyline)

                // Devolve o caminho á thread principal
                Handler(Looper.getMainLooper()).post {
                    onRouteReady(path, durationText)
                }
            }

        })
    }

    private fun drawRouteOnMap(routePoints: List<LatLng>) {
        currentRoute.clear()
        currentRoute.addAll(routePoints)

        routePolyline?.remove()

        val polylineOptions = PolylineOptions()
            .addAll(routePoints)
            .width(10f)
            .color(android.graphics.Color.BLUE)
            .geodesic(true)

        routePolyline = mMap?.addPolyline(polylineOptions)
    }

    private fun clearNavigation() {
        currentDestination = null
        lastRouteUpdateLocation = null
        currentRoute.clear()
        routePolyline?.remove()
        routePolyline = null
        binding.fabCancelNavigation.visibility = View.GONE
        binding.tvEta.text = "Tempo estimado: --"
        destino = null
        binding.lifeBar.progress = MAX_LIFE_POINTS
        binding.lifeBar.visibility = View.GONE
        navigationActive = false

        binding.lifeBarGeofence.visibility = View.VISIBLE
        binding.lifeBarGeofence.progress = geoLifePoints

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        fusedLocationProviderClient.lastLocation.addOnSuccessListener { location ->
            if (location != null){
                val currentLatLng = LatLng(location.latitude, location.longitude)
                checkGeofenceStatus(currentLatLng)
            }
        }
    }

    private fun showArrivalDialog() {
        if (!isFinishing) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Destino alcançado")
                .setMessage("Parabéns! Chegou ao destino.")
                .setPositiveButton("OK", null)
                .show()
            saveNavigationSummary()
            binding.tvEta.text = "Tempo estimado: --"
            destino = null
        }
    }

    private fun saveNavigationSummary() {
        val start = navigationStartTime ?: return
        val startLocation = initialLocation ?: return

        val durationMillis = System.currentTimeMillis() - start
        val durationMinutes = durationMillis / 60000
        val distanceKm = totalDistanceMeters / 1000

        val sessionId = FirebaseDatabase.getInstance()
            .getReference("navigation_history")
            .child(caredId)
            .push().key ?: return

        val summary = mapOf(
            "destination_name" to destino?.name,
            "start_latitude" to startLocation.latitude,
            "start_longitude" to startLocation.longitude,
            "duration_minutes" to durationMinutes,
            "distance_km" to String.format("%.2f", distanceKm).replace(",","."),
            "timestamp" to System.currentTimeMillis(),
            "life_points" to lifePoints
        )
        Log.d("DEBUG", "caredId = $caredId")
        Log.d("DEBUG", "Guardando: duração = $durationMinutes min, distância = $distanceKm km")
        FirebaseDatabase.getInstance()
            .getReference("navigation_history")
            .child(caredId)
            .child(sessionId)
            .setValue(summary)
            .addOnSuccessListener {
                Log.d("Firebase", "Histórico de navegação guardado")
            }
            .addOnFailureListener {
                Log.d("Firebase", "Erro ao guardar o histórico: ${it.message}")
            }
        Log.d("DEBUG", "Still here")
        destino?.id?.let { destinationId ->
            updateDestinationStatus(destinationId)
        }

    }

    private fun updateDestinationStatus(destinationId: String) {
        val destinationRef = FirebaseDatabase.getInstance()
            .getReference("navigation_destinations")
            .child(caredId)
            .child(destinationId)

        destinationRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if(snapshot.exists()){
                    val currentStatus = snapshot.child("status").getValue(String::class.java) ?: "pending"
                    val completedTimes = Regex("""\d+""").find(currentStatus)?.value?.toIntOrNull() ?: 0
                    val newCompletedTimes = completedTimes + 1

                    val newStatus = "completed ($newCompletedTimes)"

                    val updates = mapOf(
                        "status" to newStatus
                    )

                    destinationRef.updateChildren(updates)
                        .addOnSuccessListener {
                            Log.d("Firebase", "Status do destino atualizado para: $newStatus")
                            // sendArrivalNotification(snapshot.child("caregiverId").value.toString(), destino?.name)
                        }
                        .addOnFailureListener {
                            Log.e("Firebase", "Erro ao atualizar destino: ${it.message}")
                        }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "Erro ao ler destino: ${error.message}")
            }

        })

    }

    private fun updateUserLocation(latLng: LatLng) {
        currentMarker?.remove()
        currentMarker = mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Sua Localização")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
        )
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
    }
}