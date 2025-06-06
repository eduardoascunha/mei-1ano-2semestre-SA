package com.example.safesteps

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.safesteps.databinding.ActivityCaregiverNavigationBinding
import com.example.safesteps.models.NavigationDestination
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaregiverNavigationActivity : AppCompatActivity(), OnMapReadyCallback {
    private var mMap: GoogleMap? = null
    private lateinit var binding: ActivityCaregiverNavigationBinding
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    // IDs for caregiver and cared person
    private lateinit var caregiverId: String
    private lateinit var caredId: String

    // Current state
    private var currentMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var selectedDestination: LatLng? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCaregiverNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get IDs passed by intent
        caregiverId = intent.getStringExtra("CAREGIVER_ID") ?: ""
        caredId = intent.getStringExtra("CARED_ID") ?: ""
        Log.d("CaregiverNavigation", "Caregiver ID: $caregiverId, Cared ID: $caredId")


        if (caredId.isEmpty() || caregiverId.isEmpty()) {
            Toast.makeText(this, "IDs not provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Set up UI
        setupUI()

        // Check location permissions
        checkLocationPermissions()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSetDestination.setOnClickListener {
            // Show dialog to get destination name
            showDestinationNameDialog()
        }

        binding.btnClearDestination.setOnClickListener {
            clearDestinationMarker()
        }

        binding.btnViewSavedDestinations.setOnClickListener {
            // Show a list of saved destinations
            showSavedDestinations()
        }

        // Initially disable the set destination button until a location is selected
        binding.btnSetDestination.isEnabled = false
        binding.btnClearDestination.isEnabled = false
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
        val database = FirebaseDatabase.getInstance()
        val caredLocationRef = database.getReference("user_locations").child(caredId)

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

                // Get cared location
                caredLocationRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val lat = snapshot.child("latitude").getValue(Double::class.java)
                        val lng = snapshot.child("longitude").getValue(Double::class.java)

                        if (lat != null && lng != null) {
                            val caredLocation = LatLng(lat, lng)
                            updateCaredLocation(caredLocation)
                        } else {
                            Toast.makeText(this@CaregiverNavigationActivity, "Localização do cuidado não disponível", Toast.LENGTH_SHORT).show()
                        }
                    }

                    private fun updateCaredLocation(latLng: LatLng) {
                        currentMarker?.remove()
                        currentMarker = mMap?.addMarker(
                            MarkerOptions()
                                .position(latLng)
                                .title("Localização do Cuidado")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        )
                        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Toast.makeText(this@CaregiverNavigationActivity, "Erro ao obter localização: ${error.message}", Toast.LENGTH_SHORT).show()
                        Log.e("CaregiverNavigation", "Erro Firebase", error.toException())
                    }
                })
            }

            // Setup map click listener to select destination
            mMap?.setOnMapClickListener { latLng ->
                setDestinationMarker(latLng)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing map: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("CaregiverNavigationActivity", "Error initializing map", e)
        }
    }

    private fun setDestinationMarker(latLng: LatLng) {
        // Remove existing marker if any
        clearDestinationMarker()

        // Set new marker
        destinationMarker = mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Selected Destination")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        selectedDestination = latLng

        // Enable the set destination button
        binding.btnSetDestination.isEnabled = true
        binding.btnClearDestination.isEnabled = true
    }

    private fun clearDestinationMarker() {
        destinationMarker?.remove()
        destinationMarker = null
        selectedDestination = null

        // Disable buttons
        binding.btnSetDestination.isEnabled = false
        binding.btnClearDestination.isEnabled = false
    }

    @SuppressLint("MissingInflatedId")
    private fun showDestinationNameDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_destination_name, null)
        val etDestinationName = dialogView.findViewById<EditText>(R.id.etDestinationName)

        MaterialAlertDialogBuilder(this)
            .setTitle("Destination Name")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val destinationName = etDestinationName.text.toString().trim()

                if (destinationName.isEmpty()) {
                    Toast.makeText(this, "Please enter a name for the destination", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Save destination to Firebase
                saveDestinationToFirebase(destinationName)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun saveDestinationToFirebase(destinationName: String) {
        selectedDestination?.let { latLng ->
            val database = FirebaseDatabase.getInstance()
            val destinationsRef = database.reference
                .child("navigation_destinations")
                .child(caredId)

            // Create a unique ID for this destination
            val destinationId = destinationsRef.push().key ?: return

            // Format the current date
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val currentDate = dateFormat.format(Date())

            // Create destination object
            val destination = NavigationDestination(
                id = destinationId,
                name = destinationName,
                latitude = latLng.latitude,
                longitude = latLng.longitude,
                caregiverId = caregiverId,
                createdAt = currentDate,
                status = "pending" // pending = not yet seen by cared, active = seen but not completed, completed = reached
            )

            // Save to Firebase
            destinationsRef.child(destinationId).setValue(destination)
                .addOnSuccessListener {
                    Toast.makeText(
                        this,
                        "Destination saved and notification sent to cared person!",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Create a notification entry
                    createNotificationEntry(destinationId, destinationName)

                    // Clear the marker after saving
                    clearDestinationMarker()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(
                        this,
                        "Failed to save destination: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.e("CaregiverNavigationActivity", "Error saving destination", e)
                }
        }
    }

    private fun createNotificationEntry(destinationId: String, destinationName: String) {
        val database = FirebaseDatabase.getInstance()
        val notificationsRef = database.reference
            .child("notifications")
            .child(caredId)

        // Create notification data
        val notification = mapOf(
            "type" to "navigation",
            "title" to "New Destination",
            "message" to "Your caregiver has set a new destination for you: $destinationName",
            "destinationId" to destinationId,
            "timestamp" to ServerValue.TIMESTAMP,
            "read" to false
        )

        // Save notification
        notificationsRef.push().setValue(notification)
    }

    private fun showSavedDestinations() {
        val database = FirebaseDatabase.getInstance()
        val destinationsRef = database.reference
            .child("navigation_destinations")
            .child(caredId)

        destinationsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val destinations = mutableListOf<NavigationDestination>()

                for (childSnapshot in snapshot.children) {
                    val destination = childSnapshot.getValue(NavigationDestination::class.java)
                    destination?.let { destinations.add(it) }
                }

                if (destinations.isEmpty()) {
                    Toast.makeText(
                        this@CaregiverNavigationActivity,
                        "No saved destinations found",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }

                // Show destinations in a dialog
                showDestinationsListDialog(destinations)
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(
                    this@CaregiverNavigationActivity,
                    "Error loading destinations: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e("CaregiverNavigationActivity", "Error loading destinations", error.toException())
            }
        })
    }

    private fun showDestinationsListDialog(destinations: List<NavigationDestination>) {
        // Create a list of destination names
        val destinationNames = destinations.map {
            "${it.name} (${it.status})"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Saved Destinations")
            .setItems(destinationNames) { _, which ->
                // Get selected destination
                val selectedDestination = destinations[which]

                // Show details and options
                showDestinationOptionsDialog(selectedDestination)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDestinationOptionsDialog(destination: NavigationDestination) {
        val options = arrayOf("View on Map", "Delete")

        MaterialAlertDialogBuilder(this)
            .setTitle(destination.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // View on map
                        viewDestinationOnMap(destination)
                    }
                    1 -> {
                        // Delete
                        deleteDestination(destination)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun viewDestinationOnMap(destination: NavigationDestination) {
        val latLng = LatLng(destination.latitude, destination.longitude)

        // Set marker
        setDestinationMarker(latLng)

        // Move camera to the destination
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
    }

    private fun deleteDestination(destination: NavigationDestination) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Destination")
            .setMessage("Are you sure you want to delete '${destination.name}'?")
            .setPositiveButton("Yes") { _, _ ->
                // Delete from Firebase
                val database = FirebaseDatabase.getInstance()
                val destinationRef = database.reference
                    .child("navigation_destinations")
                    .child(caredId)
                    .child(destination.id)

                destinationRef.removeValue()
                    .addOnSuccessListener {
                        Toast.makeText(
                            this,
                            "Destination deleted successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            this,
                            "Failed to delete destination: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
            .setNegativeButton("No", null)
            .show()
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
                    "This application requires location permissions to work properly",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
}