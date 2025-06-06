package com.example.safesteps

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.safesteps.databinding.ActivityCaredDetailBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

class CaredDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityCaredDetailBinding
    private lateinit var database: FirebaseDatabase
    private lateinit var caregiverId: String
    private lateinit var caredId: String
    private lateinit var caredName: String
    private lateinit var caregiverName: String
    private var geofenceViolationListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaredDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance()

        caregiverId = intent.getStringExtra("CAREGIVER_ID") ?: ""
        caredId = intent.getStringExtra("CARED_ID") ?: ""
        caredName = intent.getStringExtra("CARED_NAME") ?: "Cuidado"

        binding.tvCaredName.text = caredName

        binding.btnMonitorLocation.setOnClickListener {
            val intent = Intent(this, CaregiverMapsActivity::class.java)
            intent.putExtra("CAREGIVER_ID", caregiverId)
            intent.putExtra("CARED_ID", caredId)
            intent.putExtra("MODE", "MONITOR")
            startActivity(intent)
        }

        binding.btnManageGeofences.setOnClickListener {
            val intent = Intent(this, CaregiverMapsActivity::class.java)
            intent.putExtra("CAREGIVER_ID", caregiverId)
            intent.putExtra("CARED_ID", caredId)
            intent.putExtra("MODE", "DRAW_GEOFENCE")
            startActivity(intent)
        }

        binding.btnManageNavigate.setOnClickListener {
            val intent = Intent(this, CaregiverNavigationActivity::class.java)
            intent.putExtra("CAREGIVER_ID", caregiverId)
            intent.putExtra("CARED_ID", caredId)
            intent.putExtra("MODE", "NAVIGATE")
            startActivity(intent)
        }

        binding.btnAccessCamera.setOnClickListener {
            val userRef = database.getReference("users").child(caregiverId)
            userRef.get().addOnSuccessListener { dataSnapshot ->
                val firstName = dataSnapshot.child("firstName").getValue(String::class.java) ?: ""
                val lastName = dataSnapshot.child("lastName").getValue(String::class.java) ?: ""
                caregiverName = "$firstName $lastName"

                val intent = Intent(this, CaregiverStreamActivity::class.java).apply {
                    putExtra("CAREGIVER_ID", caregiverId)
                    putExtra("CARED_ID", caredId)
                    putExtra("CARED_NAME", caredName)
                    putExtra("CAREGIVER_NAME", caregiverName)
                }
                startActivity(intent)
            }.addOnFailureListener {
                Log.e("CaredDetail", "Erro ao buscar nome do cuidador", it)
                Toast.makeText(this, "Erro ao carregar dados", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnHistory.setOnClickListener {
            val intent = Intent(this, NavigationHistoryActivity::class.java)
            intent.putExtra("CAREGIVER_ID", caregiverId)
            intent.putExtra("CARED_ID", caredId)
            intent.putExtra("MODE", "HISTORY")
            startActivity(intent)
        }

        binding.btnHistoryLocation.setOnClickListener {
            // Determine o tipo de visualização do histórico
            val intent = Intent(this, LocationHistory::class.java)
            intent.putExtra("CAREGIVER_ID", caregiverId)
            intent.putExtra("CARED_ID", caredId)
            intent.putExtra("CARED_NAME", caredName)
            startActivity(intent)
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

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

    override fun onDestroy() {
        super.onDestroy()

        geofenceViolationListener?.let {
            database.reference
                .child("geofence_violations")
                .child(caredId)
                .removeEventListener(it)
        }
    }
}