package com.example.safesteps

import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.safesteps.utils.GeofenceEventBus
import com.example.safesteps.utils.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

abstract class BaseActivity : AppCompatActivity(), SafeStepsApplication.AppStateListener {

    protected var alertBanner: View? = null
    protected var btnSilenceAlert: Button? = null
    protected var isCared: Boolean = false
    protected var currentAlertCaredId: String? = null
    protected var currentAlertCaredName: String? = null

    // Lista para armazenar múltiplos alertas
    protected data class AlertInfo(val caredId: String, val caredName: String, val state: Int)
    protected val activeAlerts = mutableListOf<AlertInfo>()

    // Controlo de animação e rotação de alertas
    private val handler = Handler(Looper.getMainLooper())
    private var currentAlertIndex = 0
    private val ALERT_ROTATION_DELAY = 5000L // 5 segundos entre cada alerta
    private var isAnimating = false

    // tipo de daltonismo
    protected var colorblindType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val database = FirebaseDatabase.getInstance()
            database.reference.child("users").child(currentUser.uid)
                .get().addOnSuccessListener { snapshot ->
                    isCared = snapshot.child("userType").getValue(String::class.java) == "cared"

                    // Agora buscando o tipo de daltonismo dentro do nó disabilities
                    if (snapshot.hasChild("disabilities")) {
                        val disabilitiesSnapshot = snapshot.child("disabilities")
                        if (disabilitiesSnapshot.hasChild("daltonismo")) {
                            colorblindType = disabilitiesSnapshot.child("daltonismo").getValue(String::class.java)
                            Log.d("BaseActivity", "Colorblind type detected: $colorblindType")
                        }
                    }

                    setupAlertViews()
                    updateBackgroundColor(SafeStepsApplication.instance.currentAppState)
                }
        }

        SafeStepsApplication.instance.registerAppStateListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        SafeStepsApplication.instance.unregisterAppStateListener(this)
        // Certifique-se de remover callbacks para evitar memory leaks
        handler.removeCallbacksAndMessages(null)
    }

    override fun onPause() {
        super.onPause()
        // Pause rotation when activity is not visible
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        // Atualizar UI com o estado atual da aplicação
        onAppStateChanged(SafeStepsApplication.instance.currentAppState)

        // Retomar rotação de alertas se houver mais de um
        if (activeAlerts.size > 1) {
            startAlertRotation()
        }
    }

    override fun onAppStateChanged(newState: Int) {
        runOnUiThread {
            updateBackgroundColor(newState)

            if (newState != SafeStepsApplication.APP_STATE_NORMAL) {
                fetchAllActiveAlerts()
            } else {
                activeAlerts.clear()
                updateAlertBanner(newState)
                handler.removeCallbacksAndMessages(null)
            }
        }
    }

    private fun updateBackgroundColor(state: Int) {
        val rootView = findViewById<View>(android.R.id.content)
        // Selecionar o background baseado no estado e no tipo de daltonismo
        val backgroundResource = when (colorblindType) {
            "deuteranopia" -> when {
                state == SafeStepsApplication.APP_STATE_SAFE_ZONE_ALERT -> R.drawable.background_alert_deuteranopia
                state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT -> R.drawable.background_danger_deuteranopia
                else -> R.drawable.background_normal_deuteranopia
            }
            "protanopia" -> when {
                state == SafeStepsApplication.APP_STATE_SAFE_ZONE_ALERT -> R.drawable.background_alert_protanopia
                state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT -> R.drawable.background_danger_protanopia
                else -> R.drawable.background_normal_protanopia
            }
            "tritanopia" -> when {
                state == SafeStepsApplication.APP_STATE_SAFE_ZONE_ALERT -> R.drawable.background_alert_tritanopia
                state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT -> R.drawable.background_danger_tritanopia
                else -> R.drawable.background_normal_tritanopia
            }
            else -> when { // Cores padrão para não daltônicos
                isCared && state == SafeStepsApplication.APP_STATE_NORMAL -> R.drawable.background_cared
                state == SafeStepsApplication.APP_STATE_SAFE_ZONE_ALERT -> R.drawable.background_alert
                state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT -> R.drawable.background_danger
                else -> R.drawable.background_normal
            }
        }
        rootView.setBackgroundResource(backgroundResource)
    }

    protected fun updateAlertBanner(state: Int) {
        alertBanner?.let { banner ->
            when (state) {
                SafeStepsApplication.APP_STATE_NORMAL -> {
                    banner.visibility = View.GONE
                    btnSilenceAlert?.visibility = View.GONE
                    handler.removeCallbacksAndMessages(null)
                }
                else -> {
                    // Se não temos alertas ativos ou somos o cuidado, mostramos mensagem genérica
                    if (isCared) {
                        showSingleAlert(state)
                    } else if (activeAlerts.isEmpty()) {
                        showSingleAlert(state)
                    } else {
                        // Temos alertas específicos para mostrar
                        showCurrentAlert()
                    }
                }
            }
        }
    }

    private fun showSingleAlert(state: Int) {
        alertBanner?.let { banner ->
            banner.visibility = View.VISIBLE

            // Aplicar cores adaptadas para daltonismo
            val alertBannerColor = when {
                state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT -> {
                    when (colorblindType) {
                        "deuteranopia" -> R.color.danger_color_deuteranopia
                        "protanopia" -> R.color.danger_color_protanopia
                        "tritanopia" -> R.color.danger_color_tritanopia
                        else -> R.color.danger_color
                    }
                }
                else -> {
                    when (colorblindType) {
                        "deuteranopia" -> R.color.alert_color_deuteranopia
                        "protanopia" -> R.color.alert_color_protanopia
                        "tritanopia" -> R.color.alert_color_tritanopia
                        else -> R.color.alert_color
                    }
                }
            }
            banner.setBackgroundResource(alertBannerColor)

            val textView = banner.findViewById<TextView>(R.id.alertText)

            if (isCared) {
                if (state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT) {
                    textView?.text = "PERIGO! Está numa zona de perigo!"
                } else {
                    textView?.text = "ALERTA! Fora da zona segura!"
                }
                btnSilenceAlert?.visibility = View.VISIBLE
            } else {
                if (state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT) {
                    textView?.text = "PERIGO! Um cuidado está numa zona de perigo!"
                } else {
                    textView?.text = "ALERTA! Um cuidado está fora da zona segura!"
                }
                btnSilenceAlert?.visibility = View.GONE
            }
        }
    }

    private fun showCurrentAlert() {
        if (activeAlerts.isEmpty()) return

        // Prevenir index out of bounds
        if (currentAlertIndex >= activeAlerts.size) {
            currentAlertIndex = 0
        }

        val currentAlert = activeAlerts[currentAlertIndex]

        alertBanner?.let { banner ->
            banner.visibility = View.VISIBLE

            // Aplicar cores adaptadas para daltonismo
            val alertBannerColor = when {
                currentAlert.state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT -> {
                    when (colorblindType) {
                        "deuteranopia" -> R.color.danger_color_deuteranopia
                        "protanopia" -> R.color.danger_color_protanopia
                        "tritanopia" -> R.color.danger_color_tritanopia
                        else -> R.color.danger_color
                    }
                }
                else -> {
                    when (colorblindType) {
                        "deuteranopia" -> R.color.alert_color_deuteranopia
                        "protanopia" -> R.color.alert_color_protanopia
                        "tritanopia" -> R.color.alert_color_tritanopia
                        else -> R.color.alert_color
                    }
                }
            }
            banner.setBackgroundResource(alertBannerColor)

            val textView = banner.findViewById<TextView>(R.id.alertText)
            val indicator = banner.findViewById<TextView>(R.id.alertIndicator)

            if (currentAlert.state == SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT) {
                textView?.text = "PERIGO! ${currentAlert.caredName} está numa zona de perigo!"
            } else {
                textView?.text = "ALERTA! ${currentAlert.caredName} está fora da zona segura!"
            }

            // Mostrar ou esconder o indicador de paginação conforme necessário
            if (activeAlerts.size > 1) {
                indicator?.visibility = View.VISIBLE
                indicator?.text = "${currentAlertIndex + 1}/${activeAlerts.size}"
            } else {
                indicator?.visibility = View.GONE
            }

            btnSilenceAlert?.visibility = View.GONE

            // Se temos múltiplos alertas, iniciar rotação
            if (activeAlerts.size > 1 && !isAnimating) {
                startAlertRotation()
            }
        }
    }

    private fun startAlertRotation() {
        if (activeAlerts.size <= 1) return

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (activeAlerts.size > 1) {
                    animateAlertTransition()
                    handler.postDelayed(this, ALERT_ROTATION_DELAY)
                }
            }
        }, ALERT_ROTATION_DELAY)
    }

    private fun animateAlertTransition() {
        if (isAnimating) return
        isAnimating = true

        alertBanner?.let { banner ->
            // Animação de fade out
            val fadeOut = AlphaAnimation(1.0f, 0.0f)
            fadeOut.duration = 500
            fadeOut.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}

                override fun onAnimationEnd(animation: Animation?) {
                    // Mudar para o próximo alerta
                    currentAlertIndex = (currentAlertIndex + 1) % activeAlerts.size
                    showCurrentAlert()

                    // Animação de fade in
                    val fadeIn = AlphaAnimation(0.0f, 1.0f)
                    fadeIn.duration = 500
                    fadeIn.setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) { isAnimating = false }
                        override fun onAnimationRepeat(animation: Animation?) {}
                    })
                    banner.startAnimation(fadeIn)
                }

                override fun onAnimationRepeat(animation: Animation?) {}
            })

            banner.startAnimation(fadeOut)
        }
    }

    private fun fetchAllActiveAlerts() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val database = FirebaseDatabase.getInstance()

        activeAlerts.clear()

        if (isCared) {
            // Se for cuidado, apenas mostrar o próprio alerta
            updateAlertBanner(SafeStepsApplication.instance.currentAppState)
            return
        }

        // Buscar todos os cuidados deste cuidador
        database.reference.child("caregiver_cared").child(currentUser.uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists() || snapshot.childrenCount.toInt() == 0) {
                        updateAlertBanner(SafeStepsApplication.instance.currentAppState)
                        return
                    }

                    var countChecked = 0
                    val totalCared = snapshot.childrenCount

                    snapshot.children.forEach { caredSnapshot ->
                        val caredId = caredSnapshot.key ?: return@forEach

                        // Verificar violações de geofence para este cuidado
                        database.reference.child("geofence_violations").child(caredId)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(violationSnapshot: DataSnapshot) {
                                    val inDangerZone = violationSnapshot.child("danger_zone").getValue(Boolean::class.java) ?: false
                                    val outsideSafeZone = violationSnapshot.child("safe_zone").getValue(Boolean::class.java) ?: false

                                    if (inDangerZone || outsideSafeZone) {
                                        database.reference.child("users").child(caredId)
                                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                                override fun onDataChange(userSnapshot: DataSnapshot) {
                                                    val firstName = userSnapshot.child("firstName").getValue(String::class.java) ?: ""
                                                    val lastName = userSnapshot.child("lastName").getValue(String::class.java) ?: ""
                                                    val caredName = "$firstName $lastName".trim()

                                                    val alertState = if (inDangerZone)
                                                        SafeStepsApplication.APP_STATE_DANGER_ZONE_ALERT
                                                    else
                                                        SafeStepsApplication.APP_STATE_SAFE_ZONE_ALERT

                                                    activeAlerts.add(AlertInfo(caredId, caredName, alertState))

                                                    checkAndUpdateUI(++countChecked, totalCared.toInt())
                                                }

                                                override fun onCancelled(error: DatabaseError) {
                                                    checkAndUpdateUI(++countChecked, totalCared.toInt())
                                                }
                                            })
                                    } else {
                                        checkAndUpdateUI(++countChecked, totalCared.toInt())
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    checkAndUpdateUI(++countChecked, totalCared.toInt())
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    updateAlertBanner(SafeStepsApplication.instance.currentAppState)
                }
            })
    }

    private fun checkAndUpdateUI(countChecked: Int, totalCared: Int) {
        if (countChecked >= totalCared) {
            // Ordenar alertas: primeiro os de perigo, depois os de alerta
            activeAlerts.sortByDescending { it.state }

            runOnUiThread {
                currentAlertIndex = 0
                updateAlertBanner(SafeStepsApplication.instance.currentAppState)
            }
        }
    }

    protected fun stopAlertSound() {
        NotificationHelper.stopAlertSound()
    }

    abstract fun setupAlertViews()
}