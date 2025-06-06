package com.example.safesteps

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.example.safesteps.databinding.ActivityCaregiverRegisterBinding

class CaregiverRegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaregiverRegisterBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaregiverRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        binding.tvTermsLink.setOnClickListener {
            showTermsAndConditions()
        }

        binding.btnRegister.setOnClickListener {
            registerCaregiver()
        }

        binding.tvLoginLink.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun showTermsAndConditions() {
        val termsText = """
            Termos e Condições de Uso – SafeSteps

            1. Aceitação
            Ao instalar ou usar o SafeSteps, concorda com estes termos. Se não concorda, não utilize a aplicação.
            
            2. O que é o SafeSteps
            O SafeSteps é uma aplicação móvel que permite:
            2.1 Cuidador:
                - Ver localização em tempo real do cuidado
                - Definir zonas seguras ou de alerta (geofences)
                - Aceder à câmara do cuidado (com o seu consentimento)
                - Consultar o histórico de movimentos do cuidado
            
            2.2 Cuidado:
                - Receber alertas de segurança
                - Seguir percursos pré-definidos
            
            3. Privacidade e Dados
            
            3.1 Que dados recolhemos: nome, username, e-mail, localização em tempo real, idade (para cuidados), tipo de deficiência (opcional) e código de associação.
            Para quê servem:
                - Garantir o funcionamento da aplicação (alertas, monitorização)
                - Melhorar o serviço (ex.: análise de uso por idade)
            
            3.2 Armazenamento: os dados ficam guardados no Firebase (Google).
            
            3.3 Partilha com terceiros:
                - Utilizamos serviços como Google Maps (geolocalização), Firebase (armazenamento) e Agora IO (vídeo chamadas). 
            
            3.4 Consentimento:
                - Autoriza-nos a aceder à localização e à câmara. Pode desativar GPS ou notificações nas definições do seu telemóvel a qualquer momento.
            
            4. Responsabilidades
            4.1 Cuidador:
                - Fornecer dados verdadeiros.
                - Respeitar a privacidade do cuidado.
                - Não usar o SafeSteps para fins ilegais ou vigilância excessiva.
                - Manter seguros os seus dados de acesso.
            
            4.2 Cuidado:
                - Manter o GPS ativo.
                - Não partilhar o código de associação.
                - Seguir as recomendações de segurança da aplicação.
            
            5. Limitação de Responsabilidade
            5.1 Não garantimos funcionamento sem falhas ou interrupções.
            5.2 A precisão da localização depende de fatores externos (sinal GPS, internet).
            5.3 Não nos responsabilizamos por danos diretos ou indiretos causados pelo uso da aplicação nem por falhas técnicas de terceiros.
            
            6. Segurança dos Dados
            6.1 Ainda não temos encriptação completa nesta versão (dados expostos na Firebase).
            
            7. Alterações aos Termos
            7.1 Podemos atualizar estes termos a qualquer momento.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Termos e Condições")
            .setMessage(termsText)
            .setPositiveButton("Aceito") { _, _ ->
                binding.cbTerms.isChecked = true
            }
            .setNegativeButton("Não Aceito") { _, _ ->
                binding.cbTerms.isChecked = false
            }
            .show()
    }

    private fun registerCaregiver() {
        val firstName = binding.etFirstName.text.toString().trim()
        val lastName = binding.etLastName.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        if (!binding.cbTerms.isChecked) {
            Toast.makeText(this, "É necessário aceitar os termos e condições para continuar", Toast.LENGTH_SHORT).show()
            return
        }

        if (firstName.isEmpty()) {
            binding.etFirstName.error = "Primeiro nome é obrigatório"
            binding.etFirstName.requestFocus()
            return
        }

        if (lastName.isEmpty()) {
            binding.etLastName.error = "Último nome é obrigatório"
            binding.etLastName.requestFocus()
            return
        }

        if (username.isEmpty()) {
            binding.etUsername.error = "Username é obrigatório"
            binding.etUsername.requestFocus()
            return
        }

        if (email.isEmpty()) {
            binding.etEmail.error = "Email é obrigatório"
            binding.etEmail.requestFocus()
            return
        }

        if (password.isEmpty()) {
            binding.etPassword.error = "Password é obrigatória"
            binding.etPassword.requestFocus()
            return
        }

        if (password.length < 6) {
            binding.etPassword.error = "A password deve ter pelo menos 6 caracteres"
            binding.etPassword.requestFocus()
            return
        }

        if (confirmPassword.isEmpty() || confirmPassword != password) {
            binding.etConfirmPassword.error = "As passwords não correspondem"
            binding.etConfirmPassword.requestFocus()
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        // Criar utilizador no Firebase Auth
        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = firebaseAuth.currentUser
                    val userId = user?.uid

                    val userMap = hashMapOf(
                        "id" to userId,
                        "firstName" to firstName,
                        "lastName" to lastName,
                        "username" to username,
                        "email" to email,
                        "userType" to "caregiver" // Identificar que é um cuidador
                    )

                    // Guardar informações do utilizador no Firebase Database
                    userId?.let {
                        database.reference.child("users").child(it).setValue(userMap)
                            .addOnSuccessListener {
                                binding.progressBar.visibility = View.GONE
                                Toast.makeText(
                                    this,
                                    "Registo realizado com sucesso!",
                                    Toast.LENGTH_SHORT
                                ).show()

                                // Redirecionar para tela principal
                                val intent = Intent(this, LoginActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                startActivity(intent)
                                finish()
                            }
                            .addOnFailureListener { e ->
                                binding.progressBar.visibility = View.GONE
                                Toast.makeText(
                                    this,
                                    "Erro ao guardar dados: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                } else {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Erro no registo: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }
}