package com.example.safesteps

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.safesteps.databinding.ActivityCaregiverMapsBinding
import com.example.safesteps.utils.NotificationHelper
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.FirebaseDatabase
import com.example.safesteps.utils.Geofence
import com.example.safesteps.utils.GeofencePoint
import com.example.safesteps.utils.GeofenceType
import com.example.safesteps.utils.GeofenceManager
import com.example.safesteps.utils.GeofenceListAdapter
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import android.widget.Toast
import com.example.safesteps.utils.GeofenceEventBus
import com.google.android.gms.maps.model.LatLng

class CaregiverMapsActivity : BaseActivity(), OnMapReadyCallback, GeofenceEventBus.GeofenceStatusListener{
    private var mMap: GoogleMap? = null
    private lateinit var binding: ActivityCaregiverMapsBinding
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var geofenceManager: GeofenceManager
    private var caredLocationListener: ValueEventListener? = null

    private lateinit var caregiverId: String
    private lateinit var caredId: String
    private lateinit var mode: String // "MONITOR" ou "DRAW_GEOFENCE"

    // Estado atual
    private var currentMarker: Marker? = null
    private var caredMarker: Marker? = null
    private val geofences = mutableListOf<Geofence>()
    private val polygons = mutableMapOf<String, Polygon>()

    // Desenho de geofence
    private var isDrawingGeofence = false
    private var currentGeofenceType = GeofenceType.SAFE
    private val currentGeofencePoints = mutableListOf<GeofencePoint>()
    private val temporaryMarkers = mutableListOf<Marker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCaregiverMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        caregiverId = intent.getStringExtra("CAREGIVER_ID") ?: ""
        caredId = intent.getStringExtra("CARED_ID") ?: ""
        mode = intent.getStringExtra("MODE") ?: "MONITOR"

        if (caredId.isEmpty() || caregiverId.isEmpty()) {
            super.onCreate(savedInstanceState)
            Toast.makeText(this, "IDs não fornecidos", Toast.LENGTH_SHORT).show()
            finish()
            return
        }


        val database = FirebaseDatabase.getInstance()
        geofenceManager = GeofenceManager(database)

        // Registar como listener de mudanças de geofence
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

        setupUI()
        checkLocationPermissions()
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


    private fun setupUI() {
        when (mode) {
            "DRAW_GEOFENCE" -> {
                binding.groupDrawingControls.visibility = View.VISIBLE
                binding.fabShowGeofences.visibility = View.VISIBLE
                setupDrawingControls()
            }
            "MONITOR" -> {
                binding.groupDrawingControls.visibility = View.GONE
                binding.fabShowGeofences.visibility = View.GONE
            }
        }

        binding.fabShowGeofences.setOnClickListener {
            showGeofencesList()
        }

        binding.btnBack.setOnClickListener {
            finish()
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
        GeofenceEventBus.unregister(caredId, this)
        stopLocationUpdates()

        // Remover listener de localização do "cared"
        val database = FirebaseDatabase.getInstance()
        val caredLocationRef = database.reference
            .child("user_locations")
            .child(caredId)

        caredLocationListener?.let {
            caredLocationRef.removeEventListener(it)
        }
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
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                val latLng = LatLng(location.latitude, location.longitude)
                updateUserLocation(latLng)
            }
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
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
                        updateUserLocation(LatLng(it.latitude, it.longitude))
                    }
                }
            }

            loadGeofencesFromFirebase()

            startCaredLocationTracking()

            mMap?.setOnMapClickListener { latLng ->
                if (isDrawingGeofence) {
                    addPointToCurrentGeofence(latLng)
                }
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao inicializar o mapa: ${e.message}", Toast.LENGTH_LONG)
                .show()
            Log.e("CaregiverMapsActivity", "Erro ao inicializar o mapa", e)
        }
    }

    private fun startCaredLocationTracking() {
        val database = FirebaseDatabase.getInstance()
        val caredLocationRef = database.reference
            .child("user_locations")
            .child(caredId)

        caredLocationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val latitude = snapshot.child("latitude").getValue(Double::class.java)
                val longitude = snapshot.child("longitude").getValue(Double::class.java)

                if (latitude != null && longitude != null) {
                    updateCaredMarkerOnMap(LatLng(latitude, longitude))
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@CaregiverMapsActivity,
                    "Erro ao monitorar localização: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        caredLocationRef.addValueEventListener(caredLocationListener!!)
    }

    private fun updateCaredMarkerOnMap(latLng: LatLng) {
        caredMarker?.remove()
        caredMarker = mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Localização do Cuidado")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        )

        // Centralizar mapa na localização do cuidado
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
    }

    private fun updateUserLocation(latLng: LatLng) {
        currentMarker?.remove()
        currentMarker = mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Sua Localização")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
        )
    }

    private fun loadGeofencesFromFirebase() {
        geofenceManager.loadGeofences(caredId) { loadedGeofences, error ->
            if (error != null) {
                Toast.makeText(
                    this,
                    "Erro ao carregar geofences: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
                return@loadGeofences
            }

            geofences.clear()
            geofences.addAll(loadedGeofences)
            redrawAllGeofences()
        }
    }

    private fun redrawAllGeofences() {
        for (polygon in polygons.values) {
            polygon.remove()
        }
        polygons.clear()

        val currentMarkerPosition = currentMarker?.position
        val caredMarkerPosition = caredMarker?.position

        mMap?.clear()

        currentMarkerPosition?.let {
            currentMarker = mMap?.addMarker(
                MarkerOptions()
                    .position(it)
                    .title("Sua Localização")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
        }

        caredMarkerPosition?.let {
            caredMarker = mMap?.addMarker(
                MarkerOptions()
                    .position(it)
                    .title("Localização do Cuidado")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
        }

        // Desenhar todas as geofences
        mMap?.let { map ->
            for (geofence in geofences) {
                val polygon = geofenceManager.drawGeofenceOnMap(geofence, map)
                polygon?.let { polygons[geofence.id] = it }
            }
        }
    }

    override fun onLocationUpdate(caredId: String, location: Location) {
        if (caredId == this.caredId) {
            runOnUiThread {
                updateCaredMarkerOnMap(LatLng(location.latitude, location.longitude))
            }
        }
    }


// MÉTODOS PARA O DESENHO DA GEOFENCE

    private fun setupDrawingControls() {
        binding.btnStartDrawing.setOnClickListener {
            startDrawingMode()
            binding.btnStartDrawing.isEnabled = false
            binding.btnFinishDrawing.isEnabled = true
            binding.btnCancelDrawing.isEnabled = true
        }

        binding.btnFinishDrawing.setOnClickListener {
            if (currentGeofencePoints.size >= 3) {
                showGeofenceNameDialog()
            } else {
                Toast.makeText(
                    this,
                    "Adicione pelo menos 3 pontos para criar uma geofence",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnCancelDrawing.setOnClickListener {
            cancelDrawing()
            resetDrawingControls()
        }

        binding.radioGroupGeofenceType.setOnCheckedChangeListener { _, checkedId ->
            currentGeofenceType = when (checkedId) {
                R.id.radioSafeZone -> GeofenceType.SAFE
                R.id.radioDangerZone -> GeofenceType.DANGER
                else -> GeofenceType.SAFE
            }
        }

        // Estado inicial
        resetDrawingControls()
    }

    private fun resetDrawingControls() {
        binding.btnStartDrawing.isEnabled = true
        binding.btnFinishDrawing.isEnabled = false
        binding.btnCancelDrawing.isEnabled = false
    }

    private fun startDrawingMode() {
        isDrawingGeofence = true
        currentGeofencePoints.clear()
        clearTemporaryMarkers()
    }

    private fun cancelDrawing() {
        isDrawingGeofence = false
        currentGeofencePoints.clear()
        clearTemporaryMarkers()
        redrawAllGeofences()
    }

    private fun clearTemporaryMarkers() {
        for (marker in temporaryMarkers) {
            marker.remove()
        }
        temporaryMarkers.clear()
    }

    private fun addPointToCurrentGeofence(latLng: LatLng) {
        val point = GeofencePoint(latLng.latitude, latLng.longitude)
        currentGeofencePoints.add(point)

        // Adicionar marcador temporário
        val marker = mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Ponto ${currentGeofencePoints.size}")
                .icon(
                    BitmapDescriptorFactory.defaultMarker(
                        if (currentGeofenceType == GeofenceType.SAFE)
                            BitmapDescriptorFactory.HUE_GREEN
                        else
                            BitmapDescriptorFactory.HUE_RED
                    )
                )
        )

        marker?.let {
            temporaryMarkers.add(it)
        }

        // Se tivermos mais de um ponto, desenhar uma linha entre os dois últimos
        if (currentGeofencePoints.size > 1) {
            val lastPoint = currentGeofencePoints[currentGeofencePoints.size - 2]
            val lastLatLng = LatLng(lastPoint.latitude, lastPoint.longitude)

            mMap?.addPolyline(
                PolylineOptions()
                    .add(lastLatLng, latLng)
                    .width(5f)
                    .color(
                        if (currentGeofenceType == GeofenceType.SAFE)
                            android.graphics.Color.GREEN
                        else
                            android.graphics.Color.RED
                    )
            )
        }

        // Se for o terceiro ponto ou mais, fechar o polígono temporário
        if (currentGeofencePoints.size >= 3) {
            val firstPoint = currentGeofencePoints.first()
            val firstLatLng = LatLng(firstPoint.latitude, firstPoint.longitude)
            val lastLatLng = latLng

            mMap?.addPolyline(
                PolylineOptions()
                    .add(lastLatLng, firstLatLng)
                    .width(5f)
                    .color(
                        if (currentGeofenceType == GeofenceType.SAFE)
                            android.graphics.Color.GREEN
                        else
                            android.graphics.Color.RED
                    )
            )
        }
    }

    private fun showGeofenceNameDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.drawable.dialog_geofence_name, null)
        val etGeofenceName = dialogView.findViewById<EditText>(R.id.etGeofenceName)

        MaterialAlertDialogBuilder(this)
            .setTitle("Nome da Geofence")
            .setView(dialogView)
            .setPositiveButton("Guardar") { _, _ ->
                val geofenceName = etGeofenceName.text.toString().trim()

                if (geofenceName.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Por favor, insira um nome para a geofence",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                finishDrawing(geofenceName)
                resetDrawingControls()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun finishDrawing(geofenceName: String) {
        if (currentGeofencePoints.size >= 3) {
            val newGeofence = Geofence(
                id = "geofence_${System.currentTimeMillis()}",
                name = geofenceName,
                type = currentGeofenceType,
                points = ArrayList(currentGeofencePoints),
                createdAt = System.currentTimeMillis()
            )

            geofenceManager.saveGeofence(caredId, newGeofence) { success, error ->
                if (success) {
                    Toast.makeText(this, "Geofence criada e guardada com sucesso!", Toast.LENGTH_SHORT)
                        .show()

                    // A geofence será adicionada através do listener GeofenceChangeListener
                } else {
                    Toast.makeText(
                        this,
                        "Erro ao guardar a geofence: ${error?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.e("CaregiverMapsActivity", "Erro ao guardar a geofence", error)
                }
            }
        }

        isDrawingGeofence = false
        currentGeofencePoints.clear()
        clearTemporaryMarkers()
    }

    private fun showGeofencesList() {
        if (geofences.isEmpty()) {
            Toast.makeText(this, "Não há geofences para mostrar", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.drawable.dialog_geofences_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rvGeofences)

        recyclerView.layoutManager = LinearLayoutManager(this)

        val adapter = GeofenceListAdapter(geofences) { geofence ->
            showDeleteConfirmationDialog(geofence)
        }

        recyclerView.adapter = adapter

        MaterialAlertDialogBuilder(this)
            .setTitle("Geofences")
            .setView(dialogView)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showDeleteConfirmationDialog(geofence: Geofence) {
        AlertDialog.Builder(this)
            .setTitle("Remover Geofence")
            .setMessage("Tem certeza que deseja remover a geofence '${geofence.name}'?")
            .setPositiveButton("Sim") { _, _ ->
                removeGeofence(geofence)
            }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun removeGeofence(geofence: Geofence) {
        geofenceManager.removeGeofence(caredId, geofence.id) { success, error ->
            if (success) {
                Toast.makeText(this, "Geofence removida com sucesso", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Erro ao remover geofence: ${error?.message}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e("CaregiverMapsActivity", "Erro ao remover geofence", error)
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        geofenceManager.unregisterChangeListener(caredId)

        caredLocationListener?.let {
            FirebaseDatabase.getInstance().reference
                .child("user_locations")
                .child(caredId)
                .removeEventListener(it)
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}