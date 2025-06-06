package com.example.safesteps.utils

data class CaredUser(
    var id: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val username: String = "",
    val email: String = "",
    val age: Int = 0,
    val disabilities: Map<String, Any> = emptyMap(),
    val code: String = ""
) {
    constructor() : this("", "", "", "", "", 0, emptyMap(), "")
}

