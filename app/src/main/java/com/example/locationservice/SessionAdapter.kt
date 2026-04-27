package com.example.locationservice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class SessionAdapter(private val onClick: (Session) -> Unit) :
    ListAdapter<Session, SessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return SessionViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SessionViewHolder(itemView: View, val onClick: (Session) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceCar: TextView = itemView.findViewById(R.id.tvDeviceCar)
        private val tvStartEnd: TextView = itemView.findViewById(R.id.tvStartEnd)
        private val tvType: TextView = itemView.findViewById(R.id.tvType)

        fun bind(session: Session) {
            val carText = if (!session.carId.isNullOrEmpty()) "Car: ${session.carId}" else "Device: ${session.deviceId}"
            tvDeviceCar.text = carText
            
            val start = session.startTime?.substringBefore("T") ?: "Unknown"
            val time = session.startTime?.substringAfter("T")?.substringBefore(".") ?: ""
            val endStr = if (!session.endTime.isNullOrEmpty()) {
                " - " + session.endTime.substringAfter("T").substringBefore(".")
            } else {
                " - Active"
            }
            tvStartEnd.text = "$start $time$endStr"
            
            tvType.text = if (session.type == "P") "Personal" else if (session.type == "B") "Business" else "Session"
            
            itemView.setOnClickListener {
                onClick(session)
            }
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
