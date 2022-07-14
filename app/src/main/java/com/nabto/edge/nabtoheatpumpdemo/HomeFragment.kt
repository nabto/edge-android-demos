package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.findFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class HomeDeviceListAdapter(lifecycleOwner: LifecycleOwner) : RecyclerView.Adapter<HomeDeviceListAdapter.ViewHolder>() {
    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        lateinit var device: Device
        val title: TextView = view.findViewById(R.id.home_device_item_title)
        val status: TextView = view.findViewById(R.id.home_device_item_subtitle)
    }

    private var dataSet: List<Device> = listOf()

    init {
        lifecycleOwner.lifecycle.coroutineScope.launch {
            val dao = NabtoHeatPumpApplication.deviceDatabase.deviceDao()
            dao.getAll().collect {
                dataSet = it
                notifyDataSetChanged()
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.home_device_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.title.text = dataSet[position].friendlyName.ifEmpty { "Unnamed Device" }
        holder.status.text = dataSet[position].deviceId
        holder.view.setOnClickListener {
            it.findFragment<HomeFragment>().onDeviceClick(dataSet[position])
        }
    }

    override fun getItemCount() = dataSet.size
}

class HomeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        val recycler = view.findViewById<RecyclerView>(R.id.home_recycler)
        recycler.adapter = HomeDeviceListAdapter(viewLifecycleOwner)
        recycler.layoutManager = LinearLayoutManager(activity)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val button = view.findViewById<Button>(R.id.main_pair_new_button)
        button?.setOnClickListener { _ ->
            findNavController().navigate(R.id.action_homeFragment_to_initialPairingFragment)
        }
    }

    fun onDeviceClick(device: Device) {
        val bundle = bundleOf("device" to device)
        findNavController().navigate(R.id.action_homeFragment_to_devicePageFragment, bundle)
    }
}