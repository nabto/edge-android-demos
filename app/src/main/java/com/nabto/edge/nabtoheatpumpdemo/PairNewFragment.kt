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
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.ktx.connectAsync
import com.nabto.edge.iamutil.Iam
import com.nabto.edge.iamutil.IamException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

class PairNewViewModel(private val database: DeviceDatabase) : ViewModel() {
}

class PairNewViewModelFactory(private val database: DeviceDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(DeviceDatabase::class.java).newInstance(database)
    }
}

class PairNewFragment : Fragment() {
    private val mainViewModel: MainViewModel by activityViewModels()
    private val database: DeviceDatabase by inject()
    private val repo: NabtoRepository by inject()
    private val service: NabtoConnectionService by inject()
    private val model: PairNewViewModel by viewModels { PairNewViewModelFactory(database) }
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
        mainViewModel.setTitle(getString(R.string.title_pair_new))

        val recycler = view.findViewById<RecyclerView>(R.id.pn_recycler)
        recycler.adapter = deviceListAdapter
        recycler.layoutManager = LinearLayoutManager(activity)

        repo.getScannedDevices().observe(viewLifecycleOwner) { devices ->
            deviceListAdapter.submitDeviceList(devices)
        }
    }

    fun onDeviceClick(device: MdnsDeviceInfo) {
        // @TODO: This is copied over from InitialPairingFragment and is _VERY_ bad, but it's here so that we can pair and test the app for now.
        if (device != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val activity = this@PairNewFragment.requireActivity()
                    val connection = service.createConnection()
                    val options = JSONObject()
                    options.put("ProductId", device.productId)
                    options.put("DeviceId", device.deviceId)
                    options.put("ServerKey", repo.getServerKey())
                    options.put("PrivateKey", repo.getClientPrivateKey())
                    connection.updateOptions(options.toString())

                    try {
                        connection.connect()
                        val iam = Iam.create()
                        val isPaired = iam.isCurrentUserPaired(connection)

                        if (!isPaired) {
                            iam.pairLocalInitial(connection)
                            val user = iam.getCurrentUser(connection)
                            val details = iam.getDeviceDetails(connection)
                            val updatedDevice = Device(
                                details.productId,
                                details.deviceId,
                                user.sct,
                                details.appName ?: "",
                                ""
                            )
                            val dao = database.deviceDao()
                            // @TODO: Let the user choose a friendly name for the device before inserting
                            dao.insertOrUpdate(updatedDevice)
                            val snackbar = Snackbar.make(
                                this@PairNewFragment.requireView(),
                                "Successfully paired",
                                Snackbar.LENGTH_LONG
                            )
                            snackbar.show()
                        }
                        else {
                            val snackbar = Snackbar.make(
                                this@PairNewFragment.requireView(),
                                "Already paired",
                                Snackbar.LENGTH_LONG
                            )
                            snackbar.show()
                        }
                        connection.close()
                    }
                    catch (e: IamException) {
                        Log.i("DeviceDebug", "IamException while pairing: ${e.error.name}")
                    }
                    catch (e: NabtoRuntimeException) {
                        Log.i("DeviceDebug", "NabtoRuntimeException while pairing: ${e.errorCode.name}")
                    }
                }
            }
        }
    }
}