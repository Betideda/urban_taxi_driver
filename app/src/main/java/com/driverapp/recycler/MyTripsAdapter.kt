package com.driverapp.recycler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.driverapp.R
import com.driverapp.networkApi.models.Trip
import com.driverapp.utils.MyTripsButtonClickListener

class MyTripsAdapter(
    private val tripList: List<Trip>,
    private val buttonClickListener: MyTripsButtonClickListener
) : RecyclerView.Adapter<MyTripsAdapter.MyTripViewHolder>() {

    class MyTripViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pickupAddress: TextView = itemView.findViewById(R.id.pickupAddress)
        val dropOffAddress: TextView = itemView.findViewById(R.id.dropOffAddress)
        val status: TextView = itemView.findViewById(R.id.status)
        val total: TextView = itemView.findViewById(R.id.total)
        val acceptButton: Button = itemView.findViewById(R.id.acceptButton)
        val openInMaps: Button = itemView.findViewById(R.id.openInMaps)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyTripViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip, parent, false)
        return MyTripViewHolder(view)
    }

    override fun onBindViewHolder(holder: MyTripViewHolder, position: Int) {
        val trip = tripList[position]

        holder.pickupAddress.text = trip.requested_pickup_address ?: "N/A"
        holder.dropOffAddress.text = trip.requested_drop_off_address ?: "N/A"
        holder.status.text = trip.trip_status_label ?: "N/A"
        holder.status.visibility = View.VISIBLE
        holder.total.text = trip.total ?: "0.0"

        // Reset visibility first
        holder.acceptButton.visibility = View.VISIBLE
        holder.openInMaps.visibility = View.VISIBLE
        holder.dropOffAddress.text = trip.requested_drop_off_address ?: "N/A"
        holder.total.text = trip.total +" ALL"

        when {
            trip.can_accept -> {
                holder.acceptButton.text = "Accept"
                holder.dropOffAddress.text = "N/A"
                holder.total.text = "N/A"
                holder.openInMaps.visibility = View.GONE
            }

            trip.can_start -> {
                holder.acceptButton.text = "Start"
                holder.openInMaps.visibility = View.VISIBLE
            }

            trip.can_complete -> {
                holder.acceptButton.text = "End"
                holder.openInMaps.visibility = View.VISIBLE
            }

            else -> {
                holder.acceptButton.visibility = View.GONE
                holder.openInMaps.visibility = View.GONE
            }
        }

        holder.acceptButton.setOnClickListener {
            buttonClickListener.onAcceptStartEndButtonClick(trip)
        }

        holder.openInMaps.setOnClickListener {
            buttonClickListener.onOpenInMapsClick(trip)
        }
    }

    override fun getItemCount(): Int = tripList.size
}
