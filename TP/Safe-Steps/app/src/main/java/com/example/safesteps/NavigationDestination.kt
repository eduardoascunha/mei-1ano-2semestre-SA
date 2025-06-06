package com.example.safesteps.models

data class NavigationDestination(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val caregiverId: String = "",
    val createdAt: String = "",
    val status: String = "pending" // pending, active, completed
) {
    // Empty constructor for Firebase
    constructor() : this("", "", 0.0, 0.0, "", "", "pending")
}