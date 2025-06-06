package com.example.safesteps

data class NavigationHistoryItem(
    val duration_minutes: Long = 0,
    val distance_km: String = "",
    val timestamp: Long = 0,
    val destination_name: String = "",
    val start_latitude: Double = 0.0,
    val start_longitude: Double = 0.0,
    val life_points: Int = 100,
    val isGeofenceAlert: Boolean = false
)

