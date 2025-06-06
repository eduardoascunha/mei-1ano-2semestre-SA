package com.example.safesteps.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.safesteps.utils.NotificationHelper

class SilenceAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "ACTION_SILENCE_ALARM") {
            Log.d("SilenceAlarmReceiver", "Recebido pedido para silenciar o alarme")

            // Silencia o som do alarme usando o método estático da NotificationHelper
            NotificationHelper.stopAlertSound()

            // Tipo de alerta que foi silenciado (pode ser útil para logs ou ações futuras)
            val alertType = intent.getStringExtra("ALERT_TYPE")
            Log.d("SilenceAlarmReceiver", "Tipo de alerta silenciado: $alertType")
        }
    }
}