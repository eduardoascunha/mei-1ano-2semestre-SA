package com.example.safesteps

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NavigationHistoryAdapter(private val items: List<NavigationHistoryItem>) :
    RecyclerView.Adapter<NavigationHistoryAdapter.ViewHolder>(){

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view){
            val tvTitle: TextView = view.findViewById(R.id.tvRouteTitle)
            val tvInfo: TextView = view.findViewById(R.id.tvRouteInfo)
            val tvLifePoints: TextView = view.findViewById(R.id.tvLifePoints)
            val card: CardView = view.findViewById(R.id.cardHistoryItem)

        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_navigation_history, parent, false)
            return ViewHolder(view)
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val date = Date(item.timestamp)

        if (item.destination_name == "") {
            holder.tvTitle.text = "⚠️ Alerta de segurança"
            holder.tvInfo.text = "Barra de segurança esgotada em ${sdf.format(date)}"
            holder.tvLifePoints.text = ""
            holder.card.setCardBackgroundColor(Color.parseColor("#FF8A80")) // vermelho forte
        } else {
            holder.tvTitle.text = "${item.destination_name} em ${sdf.format(date)}"
            holder.tvInfo.text = "Tempo ${item.duration_minutes} min | Distância: ${item.distance_km} km"
            holder.tvLifePoints.text = "Pontuação: ${item.life_points}"

            when (item.life_points) {
                in 80..100 -> holder.card.setCardBackgroundColor(Color.parseColor("#C8E6C9")) // verde
                in 40..79  -> holder.card.setCardBackgroundColor(Color.parseColor("#FFF9C4")) // laranja
                else       -> holder.card.setCardBackgroundColor(Color.parseColor("#FFCDD2")) // vermelho claro
            }
        }
    }

        override fun getItemCount(): Int = items.size
    }
