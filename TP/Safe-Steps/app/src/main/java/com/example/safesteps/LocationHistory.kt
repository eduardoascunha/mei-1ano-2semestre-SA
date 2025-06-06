package com.example.safesteps

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.safesteps.databinding.ActivityLocationHistoryBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class LocationHistory : BaseActivity(), OnMapReadyCallback {
    private lateinit var binding: ActivityLocationHistoryBinding
    private lateinit var database: FirebaseDatabase
    private lateinit var googleMap: GoogleMap
    private lateinit var caredId: String
    private lateinit var caregiverId: String
    private lateinit var caredName: String

    private var timeInSafeZone = 0L
    private var timeInDangerZone = 0L
    private var timeOutsideSafeZone = 0L
    private var totalTimeAnalyzed = 0L

    private var heatmapOverlay: TileOverlay? = null
    private var locationPoints = mutableListOf<WeightedLatLng>()

    private val calendar = Calendar.getInstance()
    private var startDate = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -1) // Default to 1 day ago
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private var endDate = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }

    // Flag para controlar se o mapa já foi centralizado
    private var mapCentered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance()

        caregiverId = intent.getStringExtra("CAREGIVER_ID") ?: ""
        caredId = intent.getStringExtra("CARED_ID") ?: ""
        caredName = intent.getStringExtra("CARED_NAME") ?: "Cuidado"

        binding.tvCaredName.text = "Histórico de $caredName"

        setupMapFragment()
        setupTimePeriodSpinner()
        setupAlertViews()

        binding.btnBack.setOnClickListener {
            finish()
        }

        // Inicializar com dados do último dia
        updateDateRangeDisplay()
    }

    override fun setupAlertViews() {
        alertBanner = findViewById(R.id.alertBanner)
        btnSilenceAlert = findViewById(R.id.btnSilenceAlert)

        btnSilenceAlert?.setOnClickListener {
            stopAlertSound()
        }

        updateAlertBanner(SafeStepsApplication.instance.currentAppState)
    }

    private fun setupMapFragment() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun setupTimePeriodSpinner() {
        val timePeriods = arrayOf("Último dia", "Últimos 7 dias", "Últimos 30 dias")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, timePeriods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTimePeriod.adapter = adapter

        binding.spinnerTimePeriod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { // Último dia
                        startDate = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_MONTH, -1)
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        endDate = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                            set(Calendar.MILLISECOND, 999)
                        }
                        updateDateRangeDisplay()
                        if (mapCentered) {
                            loadLocationData()
                        }
                    }
                    1 -> { // Últimos 7 dias
                        startDate = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_MONTH, -7)
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        endDate = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                            set(Calendar.MILLISECOND, 999)
                        }
                        updateDateRangeDisplay()
                        if (mapCentered) {
                            loadLocationData()
                        }
                    }
                    2 -> { // Últimos 30 dias
                        startDate = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_MONTH, -30)
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        endDate = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                            set(Calendar.MILLISECOND, 999)
                        }
                        updateDateRangeDisplay()
                        if (mapCentered) {
                            loadLocationData()
                        }
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        // Iniciar com "Último dia" selecionado
        binding.spinnerTimePeriod.setSelection(0)
    }

    private fun updateDateRangeDisplay() {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        binding.tvDateRange.text = "${dateFormat.format(startDate.time)} - ${dateFormat.format(endDate.time)}"
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isCompassEnabled = true

        // Centralizar no mapa usando a última posição conhecida
        centerMapAtLastKnownLocation()
    }

    private fun centerMapAtLastKnownLocation() {
        binding.progressLoading.visibility = View.VISIBLE

        // Primeiro, tentar a última localização atual do cared
        database.reference.child("user_locations")
            .child(caredId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val latitude = snapshot.child("latitude").getValue(Double::class.java) ?: 0.0
                        val longitude = snapshot.child("longitude").getValue(Double::class.java) ?: 0.0

                        if (latitude != 0.0 || longitude != 0.0) {
                            val location = LatLng(latitude, longitude)
                            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
                            mapCentered = true

                            Log.d("LocationHistory", "Mapa centralizado na última posição atual: $latitude, $longitude")

                            // Carregar dados de histórico depois de centralizar o mapa
                            loadLocationData()
                        } else {
                            // Se as coordenadas forem inválidas (0,0), buscamos no histórico
                            findMostRecentHistoryLocation()
                        }
                    } else {
                        // Se não encontrar a localização atual, buscar a mais recente no histórico
                        findMostRecentHistoryLocation()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LocationHistory", "Erro ao carregar localização atual: ${error.message}")
                    findMostRecentHistoryLocation()
                }
            })
    }

    private fun findMostRecentHistoryLocation() {
        // Buscar a entrada mais recente no histórico de localização
        database.reference.child("location_history")
            .child(caredId)
            .orderByChild("timestamp")
            .limitToLast(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists() && snapshot.childrenCount > 0) {
                        for (childSnapshot in snapshot.children) { // Loop com apenas 1 item
                            val latitude = childSnapshot.child("latitude").getValue(Double::class.java) ?: 0.0
                            val longitude = childSnapshot.child("longitude").getValue(Double::class.java) ?: 0.0

                            if (latitude != 0.0 || longitude != 0.0) {
                                val location = LatLng(latitude, longitude)
                                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
                                mapCentered = true

                                Log.d("LocationHistory", "Mapa centralizado na última posição do histórico: $latitude, $longitude")
                            } else {
                                useDefaultLocation()
                            }
                            break
                        }
                    } else {
                        useDefaultLocation()
                    }

                    loadLocationData()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LocationHistory", "Erro ao buscar histórico de localização: ${error.message}")
                    Toast.makeText(this@LocationHistory,
                        "Erro ao carregar dados de localização",
                        Toast.LENGTH_SHORT).show()
                    binding.progressLoading.visibility = View.GONE

                    useDefaultLocation()
                    loadLocationData()
                }
            })
    }

    private fun useDefaultLocation() {
        // Usar um local padrão
        val defaultLocation = LatLng(41.5454, -8.4265) // Braga
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
        mapCentered = true
    }

    private fun loadLocationData() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.cardStats.visibility = View.GONE

        timeInSafeZone = 0L
        timeInDangerZone = 0L
        timeOutsideSafeZone = 0L
        totalTimeAnalyzed = 0L
        locationPoints.clear()

        heatmapOverlay?.remove()

        val startTimestamp = startDate.timeInMillis
        val endTimestamp = endDate.timeInMillis

        database.reference.child("location_history")
            .child(caredId)
            .orderByChild("timestamp")
            .startAt(startTimestamp.toDouble())
            .endAt(endTimestamp.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        val points = mutableListOf<WeightedLatLng>()
                        val locationData = mutableListOf<LocationData>()

                        for (dataSnapshot in snapshot.children) {
                            val latitude = dataSnapshot.child("latitude").getValue(Double::class.java) ?: continue
                            val longitude = dataSnapshot.child("longitude").getValue(Double::class.java) ?: continue
                            val timestamp = dataSnapshot.child("timestamp").getValue(Long::class.java) ?: continue
                            val insideSafeZone = dataSnapshot.child("inside_safe_zone").getValue(Boolean::class.java) ?: true
                            val insideDangerZone = dataSnapshot.child("inside_danger_zone").getValue(Boolean::class.java) ?: false

                            val latLng = LatLng(latitude, longitude)
                            points.add(WeightedLatLng(latLng, 1.0))

                            locationData.add(LocationData(
                                latitude = latitude,
                                longitude = longitude,
                                timestamp = timestamp,
                                insideSafeZone = insideSafeZone,
                                insideDangerZone = insideDangerZone
                            ))
                        }

                        locationPoints = points

                        if (points.isNotEmpty()) {
                            addHeatmap(points)
                            calculateStatistics(locationData)
                        } else {
                            Toast.makeText(this@LocationHistory, "Não há dados para o período selecionado", Toast.LENGTH_SHORT).show()
                            binding.progressLoading.visibility = View.GONE
                        }
                    } else {
                        Toast.makeText(this@LocationHistory, "Não há dados para o período selecionado", Toast.LENGTH_SHORT).show()
                        binding.progressLoading.visibility = View.GONE
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("LocationHistory", "Erro ao carregar dados: ${error.message}")
                    binding.progressLoading.visibility = View.GONE
                }
            })
    }

    private fun addHeatmap(points: List<WeightedLatLng>) {
        if (points.isEmpty()) return

        try {
            val provider = HeatmapTileProvider.Builder()
                .weightedData(points)
                .radius(50) // Raio do heatmap em pixels
                .build()

            heatmapOverlay = googleMap.addTileOverlay(TileOverlayOptions().tileProvider(provider))

            if (!mapCentered) {
                val bounds = calculateBounds(points)
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                mapCentered = true
            }

        } catch (e: Exception) {
            Log.e("LocationHistory", "Erro ao criar mapa de calor: ${e.message}")
        }
    }

    private fun calculateBounds(points: List<WeightedLatLng>): com.google.android.gms.maps.model.LatLngBounds {
        val builder = com.google.android.gms.maps.model.LatLngBounds.Builder()
        for (point in points) {
            builder.include(LatLng(point.point.x, point.point.y))
        }
        return builder.build()
    }

    private fun calculateStatistics(locationData: List<LocationData>) {
        if (locationData.isEmpty()) {
            binding.progressLoading.visibility = View.GONE
            return
        }

        // Ordenar por timestamp para cálculos corretos
        val sortedData = locationData.sortedBy { it.timestamp }

        var prevTimestamp = sortedData.first().timestamp
        var prevInSafeZone = sortedData.first().insideSafeZone
        var prevInDangerZone = sortedData.first().insideDangerZone

        for (i in 1 until sortedData.size) {
            val currentData = sortedData[i]
            val timeSpan = currentData.timestamp - prevTimestamp

            if (timeSpan > 0) {
                if (prevInDangerZone) {
                    timeInDangerZone += timeSpan
                } else if (prevInSafeZone) {
                    timeInSafeZone += timeSpan
                } else {
                    timeOutsideSafeZone += timeSpan
                }

                totalTimeAnalyzed += timeSpan
            }

            prevTimestamp = currentData.timestamp
            prevInSafeZone = currentData.insideSafeZone
            prevInDangerZone = currentData.insideDangerZone
        }

        // Calcular percentagens
        val safeZonePercentage = if (totalTimeAnalyzed > 0) (timeInSafeZone * 100 / totalTimeAnalyzed) else 0
        val dangerZonePercentage = if (totalTimeAnalyzed > 0) (timeInDangerZone * 100 / totalTimeAnalyzed) else 0
        val outsideSafeZonePercentage = if (totalTimeAnalyzed > 0) (timeOutsideSafeZone * 100 / totalTimeAnalyzed) else 0

        // Exibir estatísticas
        binding.tvSafeZonePercentage.text = "$safeZonePercentage%"
        binding.tvDangerZonePercentage.text = "$dangerZonePercentage%"
        binding.tvOutsideSafeZonePercentage.text = "$outsideSafeZonePercentage%"

        binding.progressLoading.visibility = View.GONE
        binding.cardStats.visibility = View.VISIBLE
    }

    // Classe para guardar os dados de localização
    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val insideSafeZone: Boolean,
        val insideDangerZone: Boolean
    )
}