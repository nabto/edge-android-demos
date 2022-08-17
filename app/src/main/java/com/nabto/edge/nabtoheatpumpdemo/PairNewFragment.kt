package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.findFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUtil
import kotlinx.coroutines.*
import org.json.JSONObject
import org.koin.android.ext.android.inject

class PairNewDeviceListAdapter : RecyclerView.Adapter<PairNewDeviceListAdapter.ViewHolder>() {
    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        lateinit var device: MdnsDeviceInfo
        val title: TextView = view.findViewById(R.id.home_device_item_title)
        val status: TextView = view.findViewById(R.id.home_device_item_subtitle)
    }

    private var dataSet: List<MdnsDeviceInfo> = listOf()

    fun submitDeviceList(devices: List<MdnsDeviceInfo>) {
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

        repo.getScannedDevices().observe(viewLifecycleOwner) { devices ->
            deviceListAdapter.submitDeviceList(devices)
        }
    }

    fun onDeviceClick(mdnsDevice: MdnsDeviceInfo) {
        lifecycleScope.launch {
            // @TODO: This alreadyPaired business doesn't seem to work...?
            val alreadyPaired = withContext(Dispatchers.IO) {
                val dao = database.deviceDao()
                dao.exists(mdnsDevice.productId, mdnsDevice.deviceId)
            }
            if (alreadyPaired) {
                Snackbar.make(requireView(), getString(R.string.pair_device_already_paired), Snackbar.LENGTH_LONG).show()
            } else {
                val bundle = PairingData.makeBundle(mdnsDevice.productId, mdnsDevice.deviceId, "")
                findNavController().navigate(R.id.action_pairNewFragment_to_pairDeviceFragment, bundle)
            }
        }
    }
}