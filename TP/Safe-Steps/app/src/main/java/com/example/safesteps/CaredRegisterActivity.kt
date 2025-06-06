package com.example.safesteps

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.example.safesteps.databinding.ActivityCaredRegisterBinding

class CaredRegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaredRegisterBinding
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaredRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar Firebase Auth e Database
        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Configurar o checkbox de daltonismo para mostrar/esconder os tipos de daltonismo
        binding.cbColorblind.setOnCheckedChangeListener { _, isChecked ->
            binding.colorblindTypesGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Termos e condições
        binding.tvTermsLink.setOnClickListener {
            showTermsAndConditions()
        }

        binding.btnRegister.setOnClickListener {
            registerCared()
        }

        binding.tvLoginLink.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    private fun generateUserCode(): String {
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = java.security.SecureRandom()
        val sb = StringBuilder(6)

        for (i in 0 until 6) {
            sb.append(allowedChars[random.nextInt(allowedChars.length)])
        }

        return sb.toString()
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

    private fun registerCared() {
        val firstName = binding.etFirstName.text.toString().trim()
        val lastName = binding.etLastName.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val age = binding.etAge.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()
        val otherDisability = binding.etOtherDisability.text.toString().trim()

        // Lista de deficiências selecionadas como um mapa
        val disabilities = mutableMapOf<String, Any>()
        if (binding.cbMobility.isChecked) disabilities["mobilidade"] = true
        if (binding.cbVisual.isChecked) disabilities["visual"] = true
        if (binding.cbHearing.isChecked) disabilities["auditiva"] = true
        if (binding.cbCognitive.isChecked) disabilities["cognitiva"] = true
        if (otherDisability.isNotEmpty()) disabilities["outra"] = otherDisability

        // Adicionar daltonismo com seu tipo específico
        if (binding.cbColorblind.isChecked) {
            val colorblindType = when {
                binding.rbDeuteranopia.isChecked -> "deuteranopia"
                binding.rbProtanopia.isChecked -> "protanopia"
                binding.rbTritanopia.isChecked -> "tritanopia"
                else -> "não_especificado"
            }
            disabilities["daltonismo"] = colorblindType
        }

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

        if (age.isEmpty()) {
            binding.etAge.error = "Idade é obrigatória"
            binding.etAge.requestFocus()
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

        // Verificar se o daltonismo foi selecionado mas nenhum tipo foi especificado
        if (binding.cbColorblind.isChecked && disabilities["daltonismo"] == "não_especificado") {
            Toast.makeText(this, "Por favor, selecione um tipo específico de daltonismo", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        // Criar utilizador no Firebase Auth
        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = firebaseAuth.currentUser
                    val userId = user?.uid

                    val userCode = generateUserCode()

                    val userMap = hashMapOf(
                        "id" to userId,
                        "firstName" to firstName,
                        "lastName" to lastName,
                        "username" to username,
                        "email" to email,
                        "age" to age.toInt(),
                        "disabilities" to disabilities,
                        "userType" to "cared", // Identificar que é um cuidado
                        "code" to userCode
                    )

                    // guardar informações do utilizador no Firebase Database
                    userId?.let {
                        database.reference.child("users").child(it).setValue(userMap)
                            .addOnSuccessListener {
                                // Guardar também o código para referência fácil
                                database.reference.child("user_codes").child(userCode).setValue(userId)
                                    .addOnSuccessListener {
                                        binding.progressBar.visibility = View.GONE

                                        // Mostrar o código ao utilizador
                                        AlertDialog.Builder(this)
                                            .setTitle("Código de Associação")
                                            .setMessage("O seu código de associação é: $userCode\n\nGuarde este código pois será necessário para que os cuidadores possam associar-se a si.")
                                            .setPositiveButton("OK") { _, _ ->
                                                // Redirecionar para tela principal
                                                val intent = Intent(this, LoginActivity::class.java)
                                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                                startActivity(intent)
                                                finish()
                                            }
                                            .setCancelable(false)
                                            .show()
                                    }
                                    .addOnFailureListener { e ->
                                        binding.progressBar.visibility = View.GONE
                                        Toast.makeText(
                                            this,
                                            "Erro ao guardar código: ${e.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
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