package com.example.safesteps

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.example.safesteps.databinding.ActivityCaregiverHomeBinding
import com.example.safesteps.databinding.DialogAddCaredBinding
import com.example.safesteps.utils.CaredAdapter
import com.example.safesteps.utils.CaredUser

class CaregiverHomeActivity : BaseActivity() {

    private lateinit var binding: ActivityCaregiverHomeBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var caredAdapter: CaredAdapter
    private val caredList = mutableListOf<CaredUser>()
    private lateinit var caregiverId: String
    private var caregiverCaredListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaregiverHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Verificar se o utilizador está logado
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            navigateToLogin()
            return
        }

        caregiverId = currentUser.uid

        SafeStepsApplication.instance.checkCaredGeofenceStatus(caregiverId)

        setupRecyclerView()

        loadCaredList()

        binding.btnProfile.setOnClickListener {
            val intent = Intent(this, CaregiverProfileActivity::class.java)
            intent.putExtra("USER_ID", caregiverId)
            startActivity(intent)
        }

        binding.btnAddCared.setOnClickListener {
            showAddCaredDialog()
        }

        setupAlertViews()
    }

    private fun setupRecyclerView() {
        caredAdapter = CaredAdapter(caredList) { cared ->
            val intent = Intent(this, CaredDetailActivity::class.java)
            intent.putExtra("CAREGIVER_ID", caregiverId)
            intent.putExtra("CARED_ID", cared.id)
            intent.putExtra("CARED_NAME", cared.firstName + " " + cared.lastName)
            startActivity(intent)
        }

        binding.rvCaredList.apply {
            layoutManager = LinearLayoutManager(this@CaregiverHomeActivity)
            adapter = caredAdapter
        }
    }

    override fun setupAlertViews() {
        alertBanner = findViewById(R.id.alertBanner)
        btnSilenceAlert = findViewById(R.id.btnSilenceAlert)

        btnSilenceAlert?.setOnClickListener {
            stopAlertSound()
        }

        updateAlertBanner(SafeStepsApplication.instance.currentAppState)
    }

    private fun loadCaredList() {
        binding.progressBar.visibility = View.VISIBLE

        if (caregiverCaredListener != null) {
            val caregiverCaredRef = database.reference.child("caregiver_cared").child(caregiverId)
            caregiverCaredRef.removeEventListener(caregiverCaredListener!!)
        }

        // Buscar os relacionamentos cuidador-cuidado
        val caregiverCaredRef = database.reference.child("caregiver_cared").child(caregiverId)

        caregiverCaredListener = object : ValueEventListener  {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tempList = mutableListOf<CaredUser>()
                var countLoaded = 0
                val totalToLoad = snapshot.childrenCount

                if (totalToLoad == 0L) {
                    // Nenhum cuidado associado
                    binding.progressBar.visibility = View.GONE
                    binding.tvNoCaredInfo.visibility = View.VISIBLE
                    binding.rvCaredList.visibility = View.GONE
                    caredAdapter.updateList(tempList)
                    return
                }

                binding.tvNoCaredInfo.visibility = View.GONE
                binding.rvCaredList.visibility = View.VISIBLE

                // Para cada cuidado associado, buscar informações detalhadas
                snapshot.children.forEach { caredSnapshot ->
                    val caredId = caredSnapshot.key

                    caredId?.let {
                        database.reference.child("users").child(it)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(userSnapshot: DataSnapshot) {
                                    val caredUser = userSnapshot.getValue(CaredUser::class.java)
                                    caredUser?.let {
                                        tempList.add(it)
                                    }

                                    countLoaded++
                                    if (countLoaded.toLong() == totalToLoad) {
                                        // Todos os cuidados foram carregados
                                        binding.progressBar.visibility = View.GONE
                                        caredAdapter.updateList(tempList)
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    binding.progressBar.visibility = View.GONE
                                    Toast.makeText(this@CaregiverHomeActivity,
                                        "Erro ao carregar informações: ${error.message}",
                                        Toast.LENGTH_SHORT).show()
                                }
                            })
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@CaregiverHomeActivity,
                    "Erro ao carregar lista: ${error.message}",
                    Toast.LENGTH_SHORT).show()
            }
        }

        caregiverCaredRef.addValueEventListener(caregiverCaredListener!!)
    }

    private fun removeListeners() {
        if (caregiverCaredListener != null) {
            val caregiverCaredRef = database.reference.child("caregiver_cared").child(caregiverId)
            caregiverCaredRef.removeEventListener(caregiverCaredListener!!)
            caregiverCaredListener = null
        }
    }

    private fun showAddCaredDialog() {
        val dialogBinding = DialogAddCaredBinding.inflate(layoutInflater)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnAdd.setOnClickListener {
            val username = dialogBinding.etUsername.text.toString().trim()
            val code = dialogBinding.etCode.text.toString().trim()

            if (username.isEmpty()) {
                dialogBinding.etUsername.error = "Insira o username"
                return@setOnClickListener
            }

            if (code.isEmpty()) {
                dialogBinding.etCode.error = "Insira o código"
                return@setOnClickListener
            }

            dialog.dismiss()
            addCaredByCode(username, code)
        }

        dialog.show()
    }

    private fun addCaredByCode(username: String, code: String) {
        binding.progressBar.visibility = View.VISIBLE

        // Verificar o código para obter o ID do cuidado
        database.reference.child("user_codes").child(code)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val caredId = snapshot.getValue(String::class.java)

                    if (caredId == null) {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@CaregiverHomeActivity,
                            "Código inválido",
                            Toast.LENGTH_SHORT).show()
                        return
                    }

                    // Verificar se o username corresponde ao ID encontrado
                    database.reference.child("users").child(caredId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(userSnapshot: DataSnapshot) {
                                val caredUser = userSnapshot.getValue(CaredUser::class.java)

                                if (caredUser == null || caredUser.username != username) {
                                    binding.progressBar.visibility = View.GONE
                                    Toast.makeText(this@CaregiverHomeActivity,
                                        "Username não corresponde ao código",
                                        Toast.LENGTH_SHORT).show()
                                    return
                                }

                                // Verificar se o tipo do utilizador é "cared"
                                val userType = userSnapshot.child("userType").getValue(String::class.java)
                                if (userType != "cared") {
                                    binding.progressBar.visibility = View.GONE
                                    Toast.makeText(this@CaregiverHomeActivity,
                                        "Este utilizador não é do tipo cuidado",
                                        Toast.LENGTH_SHORT).show()
                                    return
                                }

                                // Adicionar relacionamento entre cuidador e cuidado
                                val caregiverCaredRef = database.reference
                                    .child("caregiver_cared")
                                    .child(caregiverId)
                                    .child(caredId)

                                val caredCaregiverRef = database.reference
                                    .child("cared_caregiver")
                                    .child(caredId)
                                    .child(caregiverId)

                                // Usar transação para garantir que ambas as referências são atualizadas
                                caregiverCaredRef.setValue(true)
                                    .addOnSuccessListener {
                                        caredCaregiverRef.setValue(true)
                                            .addOnSuccessListener {
                                                binding.progressBar.visibility = View.GONE
                                                Toast.makeText(this@CaregiverHomeActivity,
                                                    "Cuidado adicionado com sucesso",
                                                    Toast.LENGTH_SHORT).show()
                                            }
                                            .addOnFailureListener { e ->
                                                binding.progressBar.visibility = View.GONE
                                                Toast.makeText(this@CaregiverHomeActivity,
                                                    "Erro ao associar cuidado: ${e.message}",
                                                    Toast.LENGTH_SHORT).show()
                                            }
                                    }
                                    .addOnFailureListener { e ->
                                        binding.progressBar.visibility = View.GONE
                                        Toast.makeText(this@CaregiverHomeActivity,
                                            "Erro ao associar cuidado: ${e.message}",
                                            Toast.LENGTH_SHORT).show()
                                    }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                binding.progressBar.visibility = View.GONE
                                Log.e("FirebaseError", "Error code: ${error.code}, Message: ${error.message}, Details: ${error.details}")
                                Toast.makeText(this@CaregiverHomeActivity,
                                    "Erro ao verificar utilizador: ${error.message}",
                                    Toast.LENGTH_SHORT).show()
                            }
                        })
                }

                override fun onCancelled(error: DatabaseError) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@CaregiverHomeActivity,
                        "Erro ao verificar código: ${error.message}",
                        Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        removeListeners()
        super.onDestroy()
    }
}