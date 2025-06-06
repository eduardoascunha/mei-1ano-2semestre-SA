package com.example.safesteps.utils
import java.io.Serializable

/**
 * Representa um ponto de uma geofence
 */
data class GeofencePoint(
    val latitude: Double,
    val longitude: Double
) : Serializable

/**
 * Tipos de geofence disponíveis
 */
enum class GeofenceType {
    DANGER,  // Zonas de perigo
    SAFE     // Zonas seguras
}

/**
 * Classe que representa uma geofence
 */
data class Geofence(
    val id: String,
    val name: String,
    val type: GeofenceType,
    val points: ArrayList<GeofencePoint>,
    val createdAt: Long = System.currentTimeMillis()
) : Serializable