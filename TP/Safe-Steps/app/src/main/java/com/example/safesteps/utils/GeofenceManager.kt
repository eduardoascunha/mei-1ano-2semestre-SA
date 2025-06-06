package com.example.safesteps.utils

import android.graphics.Color
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import android.util.Log
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.firebase.database.ChildEventListener

class GeofenceManager(private val database: FirebaseDatabase) {
    // Interfaces para callbacks
    interface GeofenceDrawCallback {
        fun onGeofenceDrawn(geofence: Geofence)
        fun onClearTemporaryMarkers()
    }

    interface GeofenceChangeListener {
        fun onGeofenceAdded(geofence: Geofence)
        fun onGeofenceRemoved(geofenceId: String)
        fun onGeofenceListUpdated(geofences: List<Geofence>)
    }

    private val changeListeners = mutableMapOf<String, GeofenceChangeListener>()
    private val childEventListeners = mutableMapOf<String, ChildEventListener>()

    // Registrar listeners para mudanças em geofences
    fun registerChangeListener(caredId: String, listener: GeofenceChangeListener) {
        changeListeners[caredId] = listener

        unregisterChildEventListener(caredId)

        val childEventListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val geofence = parseGeofenceSnapshot(snapshot)
                geofence?.let {
                    listener.onGeofenceAdded(it)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.key
                id?.let {
                    listener.onGeofenceRemoved(it)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val geofence = parseGeofenceSnapshot(snapshot)
                geofence?.let {
                    listener.onGeofenceRemoved(it.id)
                    listener.onGeofenceAdded(it)
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                // Não necessário para este caso
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("GeofenceManager", "Erro no listener de geofences: ${error.message}")
            }
        }

        childEventListeners[caredId] = childEventListener

        // Adicionar o listener ao Firebase
        database.reference
            .child("geofences")
            .child(caredId)
            .addChildEventListener(childEventListener)

        // Carregar lista inicial completa
        loadGeofences(caredId) { geofences, _ ->
            listener.onGeofenceListUpdated(geofences)
        }
    }

    private fun unregisterChildEventListener(caredId: String) {
        childEventListeners[caredId]?.let { listener ->
            database.reference
                .child("geofences")
                .child(caredId)
                .removeEventListener(listener)
        }
        childEventListeners.remove(caredId)
    }

    fun unregisterChangeListener(caredId: String) {
        changeListeners.remove(caredId)
        unregisterChildEventListener(caredId)
    }


    fun saveGeofence(caredId: String, geofence: Geofence, onComplete: (Boolean, Exception?)-> Unit) {
        val geofenceRef = database.reference
            .child("geofences")
            .child(caredId)
            .child(geofence.id)

        val geofenceMap = hashMapOf(
            "id" to geofence.id,
            "name" to geofence.name,
            "type" to geofence.type.toString(),
            "createdAt" to geofence.createdAt
        )

        geofenceRef.setValue(geofenceMap)
            .addOnSuccessListener {
                val pointsRef = geofenceRef.child("points")

                val pointsMap = HashMap<String, Any>()
                geofence.points.forEachIndexed { index, point ->
                    val pointKey = "point_$index"
                    val pointData = hashMapOf(
                        "latitude" to point.latitude,
                        "longitude" to point.longitude
                    )
                    pointsMap[pointKey] = pointData
                }

                pointsRef.setValue(pointsMap)
                    .addOnSuccessListener {
                        onComplete(true, null)

                        // Notificar listeners sobre nova geofence
                        changeListeners[caredId]?.onGeofenceAdded(geofence)
                    }
                    .addOnFailureListener { e ->
                        onComplete(false, e)
                    }
            }
            .addOnFailureListener { e ->
                onComplete(false, e)
            }
    }

    fun loadGeofences(caredId: String, onComplete: (List<Geofence>, Exception?) -> Unit) {
        val geofencesRef = database.reference
            .child("geofences")
            .child(caredId)

        geofencesRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val geofenceList = mutableListOf<Geofence>()

                for (geofenceSnapshot in snapshot.children) {
                    val geofence = parseGeofenceSnapshot(geofenceSnapshot)
                    geofence?.let { geofenceList.add(it) }
                }

                onComplete(geofenceList, null)

                // Notificar listeners sobre lista completa
                changeListeners[caredId]?.onGeofenceListUpdated(geofenceList)
            }

            override fun onCancelled(error: DatabaseError) {
                onComplete(emptyList(), error.toException())
            }
        })
    }

    private fun parseGeofenceSnapshot(geofenceSnapshot: DataSnapshot): Geofence? {
        val id = geofenceSnapshot.child("id").getValue(String::class.java) ?: return null
        val name = geofenceSnapshot.child("name").getValue(String::class.java) ?: "Sem nome"
        val typeStr = geofenceSnapshot.child("type").getValue(String::class.java) ?: "DANGER"
        val createdAt = geofenceSnapshot.child("createdAt").getValue(Long::class.java) ?: System.currentTimeMillis()

        val type = try {
            GeofenceType.valueOf(typeStr)
        } catch (e: Exception) {
            GeofenceType.DANGER
        }

        val points = mutableListOf<GeofencePoint>()
        val pointsSnapshot = geofenceSnapshot.child("points")

        for (pointSnapshot in pointsSnapshot.children) {
            val latitude = pointSnapshot.child("latitude").getValue(Double::class.java) ?: continue
            val longitude = pointSnapshot.child("longitude").getValue(Double::class.java) ?: continue

            points.add(GeofencePoint(latitude, longitude))
        }

        return if (points.size >= 3) {
            Geofence(id, name, type, ArrayList(points), createdAt)
        } else null
    }

    fun removeGeofence(caredId: String, geofenceId: String, onComplete: (Boolean, Exception?) -> Unit) {
        val geofenceRef = database.reference
            .child("geofences")
            .child(caredId)
            .child(geofenceId)

        geofenceRef.removeValue()
            .addOnSuccessListener {
                onComplete(true, null)

                // Notificar listeners sobre remoção
                changeListeners[caredId]?.onGeofenceRemoved(geofenceId)
            }
            .addOnFailureListener { e ->
                onComplete(false, e)
            }
    }

    // Métodos para desenho de geofence no mapa (compartilhados entre atividades)
    fun drawGeofenceOnMap(geofence: Geofence, map: GoogleMap): Polygon? {
        if (map == null) return null

        val polygonOptions = PolygonOptions()

        // Adicionar todos os pontos ao polígono
        for (point in geofence.points) {
            polygonOptions.add(LatLng(point.latitude, point.longitude))
        }

        // Fechar o polígono adicionando o primeiro ponto novamente
        if (geofence.points.isNotEmpty()) {
            val firstPoint = geofence.points.first()
            polygonOptions.add(LatLng(firstPoint.latitude, firstPoint.longitude))
        }

        // Definir estilos baseados no tipo de geofence
        when (geofence.type) {
            GeofenceType.DANGER -> {
                polygonOptions.strokeColor(Color.RED)
                    .fillColor(0x22FF0000) // Vermelho transparente
            }
            GeofenceType.SAFE -> {
                polygonOptions.strokeColor(Color.GREEN)
                    .fillColor(0x2200FF00) // Verde transparente
            }
        }

        // Adicionar o polígono ao mapa
        val polygon = map.addPolygon(polygonOptions)

        // Adicionar marcador com o nome da geofence no centro
        if (geofence.points.isNotEmpty()) {
            val centerLat = geofence.points.map { it.latitude }.average()
            val centerLng = geofence.points.map { it.longitude }.average()
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(centerLat, centerLng))
                    .title(geofence.name)
                    .icon(BitmapDescriptorFactory.defaultMarker(
                        if (geofence.type == GeofenceType.SAFE) BitmapDescriptorFactory.HUE_GREEN
                        else BitmapDescriptorFactory.HUE_RED
                    ))
            )
        }

        return polygon
    }

    // Verificar se um ponto está dentro de um polígono
    fun isPointInPolygon(point: LatLng, polygon: List<GeofencePoint>): Boolean {
        var inside = false
        var j = polygon.size - 1

        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]

            if ((pi.longitude > point.longitude) != (pj.longitude > point.longitude) &&
                (point.latitude < (pj.latitude - pi.latitude) * (point.longitude - pi.longitude) /
                        (pj.longitude - pi.longitude) + pi.latitude)
            ) {
                inside = !inside
            }
            j = i
        }

        return inside
    }

    // Verificar se um ponto está em alguma zona segura
    fun isInsideSafeZone(point: LatLng, geofences: List<Geofence>): Boolean {
        for (geofence in geofences) {
            if (geofence.type == GeofenceType.SAFE && isPointInPolygon(point, geofence.points)) {
                return true
            }
        }
        return false
    }

    fun isInsideDangerZone(point: LatLng, geofences: List<Geofence>): Boolean {
        for (geofence in geofences) {
            if (geofence.type == GeofenceType.DANGER && isPointInPolygon(point, geofence.points)) {
                return true
            }
        }
        return false
    }

    // Atualizar status de violação de geofence
    fun updateGeofenceViolationStatus(caredId: String, isViolation: Boolean) {
        database.reference
            .child("geofence_violations")
            .child(caredId)
            .setValue(isViolation)
    }
}