package com.driverapp.recycler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.driverapp.R
import com.driverapp.models.Zone
import com.driverapp.utils.OffersClickListener

class OffersAdapter(
    private val offersList: List<Zone>,
    private val onOfferClickListener: OffersClickListener?
) : RecyclerView.Adapter<OffersAdapter.OffersViewHolder>() {

    class OffersViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val acceptButton: Button = itemView.findViewById(R.id.acceptButton)
        val title: TextView = itemView.findViewById(R.id.title)
        val price: TextView = itemView.findViewById(R.id.price)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OffersViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_offers, parent, false)
        return OffersViewHolder(view)
    }

    override fun onBindViewHolder(holder: OffersViewHolder, position: Int) {
        val offers = offersList[position]
        holder.title.text = offers.title
        holder.price.text = offers.price

        holder.acceptButton.setOnClickListener {
            onOfferClickListener?.onStartEndButtonClick(offers,holder.acceptButton)
        }

    }

    override fun getItemCount(): Int = offersList.size
}
