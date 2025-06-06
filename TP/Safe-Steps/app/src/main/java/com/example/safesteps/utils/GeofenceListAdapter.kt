package com.example.safesteps.utils

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.safesteps.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeofenceListAdapter(
    private val geofences: List<Geofence>,
    private val onDeleteClick: (Geofence) -> Unit
) : RecyclerView.Adapter<GeofenceListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvGeofenceName: TextView = view.findViewById(R.id.tvGeofenceName)
        val tvGeofenceType: TextView = view.findViewById(R.id.tvGeofenceType)
        val tvGeofenceDate: TextView = view.findViewById(R.id.tvGeofenceDate)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteGeofence)
        val viewTypeIndicator: View = view.findViewById(R.id.viewTypeIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_geofence, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val geofence = geofences[position]

        holder.tvGeofenceName.text = geofence.name
        holder.tvGeofenceType.text = when (geofence.type) {
            GeofenceType.SAFE -> "Zona Segura"
            GeofenceType.DANGER -> "Zona de Perigo"
        }

        // Formatação da data
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val formattedDate = dateFormat.format(Date(geofence.createdAt))
        holder.tvGeofenceDate.text = "Criada em: $formattedDate"

        val colorRes = when (geofence.type) {
            GeofenceType.SAFE -> R.color.colorSafeZone
            GeofenceType.DANGER -> R.color.colorDangerZone
        }
        val color = ContextCompat.getColor(holder.itemView.context, colorRes)
        val indicator = holder.viewTypeIndicator.background as GradientDrawable
        indicator.setColor(color)

        holder.btnDelete.setOnClickListener {
            onDeleteClick(geofence)
        }
    }

    override fun getItemCount() = geofences.size
}