package com.example.safesteps.utils

import android.location.Location
import com.example.safesteps.CaredMapsActivity

object GeofenceEventBus {
    private val listeners = mutableMapOf<String, MutableList<GeofenceStatusListener>>()
    private val appListeners = mutableListOf<AppGeofenceStatusListener>()

    interface GeofenceStatusListener {
        fun onLocationUpdate(caredId: String, location: Location)
    }

    // Interface para listeners globais da aplicação
    interface AppGeofenceStatusListener {
        fun onGeofenceAlert(caredId: String, alertType: AlertType)
    }

    enum class AlertType {
        LEFT_SAFE_ZONE,
        ENTERED_DANGER_ZONE,
        RETURNED_TO_SAFE_ZONE
    }

    sealed class ZoneState {
        object SAFE : ZoneState()
        object UNSAFE : ZoneState()
        object DANGER : ZoneState()
    }

    fun register(caredId: String, listener: GeofenceStatusListener) {
        if (!listeners.containsKey(caredId)) {
            listeners[caredId] = mutableListOf()
        }
        listeners[caredId]?.add(listener)
    }

    fun unregister(caredId: String, listener: GeofenceStatusListener) {
        listeners[caredId]?.remove(listener)
    }

    // Métodos para gestão de listeners de aplicação (global)
    fun registerAppListener(listener: AppGeofenceStatusListener) {
        if (!appListeners.contains(listener)) {
            appListeners.add(listener)
        }
    }

    fun unregisterAppListener(listener: AppGeofenceStatusListener) {
        appListeners.remove(listener)
    }


    fun notifyLocationUpdate(caredId: String, location: Location) {
        listeners[caredId]?.forEach { it.onLocationUpdate(caredId, location) }
    }

    fun notifyAppGeofenceAlert(caredId: String, alertType: AlertType) {
        appListeners.forEach { it.onGeofenceAlert(caredId, alertType) }
    }

    interface GeofenceZoneListener {
        fun onZoneStateChanged(state: ZoneState)
    }

    private var zoneListener: GeofenceZoneListener? = null

    fun registerZoneListener(listener: CaredMapsActivity) {
        zoneListener = listener
    }

    fun notifyZoneStateChange(state: ZoneState) {
        zoneListener?.onZoneStateChanged(state)
    }
}