package com.example.safesteps

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.example.safesteps.databinding.ActivityUserTypeSelectionBinding

class UserTypeSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserTypeSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserTypeSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCaregiver.setOnClickListener {
            val intent = Intent(this, CaregiverRegisterActivity::class.java)
            startActivity(intent)
        }

        binding.btnCared.setOnClickListener {
            val intent = Intent(this, CaredRegisterActivity::class.java)
            startActivity(intent)
        }

        binding.tvLoginLink.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }
}