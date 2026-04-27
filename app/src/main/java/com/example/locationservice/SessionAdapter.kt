package com.example.locationservice

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SessionAdapter(
    private val onMapClick: (Session) -> Unit,
    private val onTypeToggle: (Session) -> Unit,
    private val onDelete: (Session) -> Unit,
    private val onEnd: (Session) -> Unit
) :
    ListAdapter<Session, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view, onMapClick, onTypeToggle, onDelete, onEnd)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(
        itemView: View, 
        val onMapClick: (Session) -> Unit,
        val onTypeToggle: (Session) -> Unit,
        val onDelete: (Session) -> Unit,
        val onEnd: (Session) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceCar: TextView = itemView.findViewById(R.id.tvDeviceCar)
        private val tvType: TextView = itemView.findViewById(R.id.tvType)
        private val tvStart: TextView = itemView.findViewById(R.id.tvStart)
        private val tvEnd: TextView = itemView.findViewById(R.id.tvEnd)
        private val tvLocationStart: TextView = itemView.findViewById(R.id.tvLocationStart)
        private val tvLocationEnd: TextView = itemView.findViewById(R.id.tvLocationEnd)
        private val btnEndSession: View = itemView.findViewById(R.id.btnEndSession)
        private val btnDeleteSession: View = itemView.findViewById(R.id.btnDeleteSession)

        private val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val outputFormat = SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.getDefault())

        private fun parseDate(dateStr: String?): String {
            if (dateStr.isNullOrEmpty()) return "N/A"
            return try {
                val cleanStr = dateStr.substringBefore("Z").substringBefore(".")
                val date = inputFormat.parse(cleanStr)
                if (date != null) outputFormat.format(date) else "Invalid Date"
            } catch (e: Exception) {
                dateStr
            }
        }

        fun bind(session: Session) {
            tvDeviceCar.text = session.description ?: ("Car: " + session.carId)
            
            tvStart.text = "Start: " + parseDate(session.startTime)
            if (session.endTime.isNullOrEmpty()) {
                tvEnd.text = "End: Active Now"
                tvEnd.setTextColor(Color.parseColor("#15803D")) // Green text for active
            } else {
                tvEnd.text = "End: " + parseDate(session.endTime)
                tvEnd.setTextColor(Color.parseColor("#4B5563")) // Default gray
            }
            
            // Set type badge
            val typeText: String
            val bgCol: Int
            val textCol: Int
            when (session.type) {
                "P" -> {
                    typeText = "Personal"
                    bgCol = Color.parseColor("#DBEAFE") // blue-100
                    textCol = Color.parseColor("#1D4ED8") // blue-700
                }
                "B" -> {
                    typeText = "Business"
                    bgCol = Color.parseColor("#FEF3C7") // yellow-100
                    textCol = Color.parseColor("#B45309") // yellow-700
                }
                else -> {
                    typeText = session.type ?: "Standard"
                    bgCol = Color.parseColor("#F3F4F6") // gray-100
                    textCol = Color.parseColor("#374151") // gray-700
                }
            }
            tvType.text = typeText
            tvType.setTextColor(textCol)
            val badgeDrawable = GradientDrawable().apply {
                setColor(bgCol)
                cornerRadius = 32f
            }
            tvType.background = badgeDrawable

            tvLocationStart.text = "📍 " + (if (session.locationStart.isNullOrEmpty()) "Unknown Location" else session.locationStart)
            tvLocationEnd.text = "🏁 " + (if (session.locationEnd.isNullOrEmpty()) "Unknown Location" else session.locationEnd)
            
            if (session.endTime.isNullOrEmpty()) {
                tvLocationEnd.visibility = View.GONE
                btnEndSession.visibility = View.VISIBLE
            } else {
                tvLocationEnd.visibility = View.VISIBLE
                btnEndSession.visibility = View.GONE
            }

            itemView.setOnClickListener { onMapClick(session) }
            tvType.setOnClickListener { onTypeToggle(session) }
            btnDeleteSession.setOnClickListener { onDelete(session) }
            btnEndSession.setOnClickListener { onEnd(session) }
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<Session>() {
        override fun areItemsTheSame(oldItem: Session, newItem: Session): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Session, newItem: Session): Boolean {
            return oldItem == newItem
        }
    }
}
