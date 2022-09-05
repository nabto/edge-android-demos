package com.nabto.edge.nabtodemo

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.findFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

class PairNewDeviceListAdapter : RecyclerView.Adapter<PairNewDeviceListAdapter.ViewHolder>() {
    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        lateinit var device: Device
        val title: TextView = view.findViewById(R.id.home_device_item_title)
        val status: TextView = view.findViewById(R.id.home_device_item_subtitle)
    }

    private var dataSet: List<Device> = listOf()

    fun submitDeviceList(devices: List<Device>) {
        dataSet = devices
        notifyDataSetChanged()
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
        holder.title.text = dataSet[position].productId
        holder.status.text = dataSet[position].deviceId
        holder.view.setOnClickListener {
            it.findFragment<PairNewFragment>().onDeviceClick(dataSet[position])
        }
    }

    override fun getItemCount() = dataSet.size
}

/**
 * Fragment for fragment_pair_new.xml
 * This fragment shows a list of devices on the local network that were found through mDNS
 *
 * @TODO: Should be renamed. PairNewFragment is a confusing name that was used at the start
 * of the project when this was the only pairing-related fragment.
 */
class PairNewFragment : Fragment() {
    private val TAG = "PairNewFragment"
    private val database: DeviceDatabase by inject()
    private val repo: NabtoRepository by inject()
    private val deviceListAdapter = PairNewDeviceListAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_pair_new, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.pn_recycler)
        recycler.adapter = deviceListAdapter
        recycler.layoutManager = LinearLayoutManager(activity)

        val emptyCard = view.findViewById<View>(R.id.pair_new_empty_layout)

        repo.getScannedDevices().observe(viewLifecycleOwner) { devices ->
            if (devices.isEmpty()) {
                emptyCard.visibility = View.VISIBLE
            } else {
                emptyCard.visibility = View.GONE
            }
            deviceListAdapter.submitDeviceList(devices)
        }
    }

    fun onDeviceClick(mdnsDevice: Device) {
        lifecycleScope.launch {
            val alreadyPaired = withContext(Dispatchers.IO) {
                val dao = database.deviceDao()
                dao.exists(mdnsDevice.productId, mdnsDevice.deviceId)
            }
            if (alreadyPaired) {
                Snackbar.make(requireView(), getString(R.string.pair_device_already_paired), Snackbar.LENGTH_LONG).show()
            } else {
                val bundle = mdnsDevice.toBundle()
                bundle.putString("deviceId", mdnsDevice.deviceId)
                findNavController().navigate(R.id.action_nav_pairDeviceFragment, bundle)
            }
        }
    }
}