package com.nabto.edge.nabtoheatpumpdemo

import android.graphics.Color.green
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.findFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

enum class HomeDeviceItemStatus {
    ONLINE,
    CONNECTING,
    OFFLINE
}

class HomeViewModelFactory(private val database: DeviceDatabase, private val manager: NabtoConnectionManager): ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(
            DeviceDatabase::class.java,
            NabtoConnectionManager::class.java
        ).newInstance(database, manager)
    }
}

class HomeViewModel(private val database: DeviceDatabase, private val manager: NabtoConnectionManager) : ViewModel() {
    data class HomeDeviceItem(
        var observer: Observer<NabtoConnectionState>?,
        val device: Device,
        val handle: ConnectionHandle,
        var status: HomeDeviceItemStatus
    )

    private val _deviceList = MutableLiveData<List<HomeDeviceItem>>()
    val deviceList: LiveData<List<HomeDeviceItem>>
        get() = _deviceList

    private var devices: List<Device> = emptyList()
    private val connections = mutableMapOf<Device, HomeDeviceItem>()

    init {
        viewModelScope.launch {
            database.deviceDao().getAll().collect { devices ->
                this@HomeViewModel.devices = devices
                connectDevices()
            }
        }
    }

    fun connectDevices() {
        for (dev in devices) {
            if (!connections.contains(dev)) {
                val handle = manager.requestConnection(dev)

                val item = HomeDeviceItem(
                    device = dev,
                    observer = null,
                    handle = handle,
                    status = HomeDeviceItemStatus.CONNECTING
                )

                val observer = Observer<NabtoConnectionState> { state ->
                    item.status = when (state) {
                        NabtoConnectionState.CONNECTED -> HomeDeviceItemStatus.ONLINE
                        NabtoConnectionState.CONNECTING -> HomeDeviceItemStatus.CONNECTING
                        else -> HomeDeviceItemStatus.OFFLINE
                    }
                    _deviceList.postValue(connections.values.toList())
                }
                item.observer = observer

                connections[dev] = item
                manager.getConnectionState(handle).observeForever(observer)
            }
        }
        _deviceList.postValue(connections.values.toList())
    }

    fun releaseConnections() {
        super.onCleared()
        for (item in connections.values) {
            item.observer?.let { manager.getConnectionState(item.handle).removeObserver(it) }
            manager.releaseHandle(item.handle)
        }
        connections.clear()
    }

    override fun onCleared() {
        super.onCleared()
        Log.i("HOME", "onclear")
    }
}

class DeviceListAdapter : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {
    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        lateinit var device: Device
        val title: TextView = view.findViewById(R.id.home_device_item_title)
        val status: TextView = view.findViewById(R.id.home_device_item_subtitle)
        val connectionStatusView: ImageView = view.findViewById(R.id.home_device_item_connection)
    }

    private var dataSet: List<HomeViewModel.HomeDeviceItem> = listOf()

    fun submitDeviceList(devices: List<HomeViewModel.HomeDeviceItem>) {
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
        holder.title.text = dataSet[position].device.getDeviceNameOrElse("Unnamed device")
        holder.status.text = dataSet[position].device.deviceId
        holder.view.setOnClickListener {
            it.findFragment<HomeFragment>().onDeviceClick(dataSet[position].device)
        }
        val color = when (dataSet[position].status) {
            HomeDeviceItemStatus.ONLINE -> R.color.green
            HomeDeviceItemStatus.CONNECTING -> R.color.red
            HomeDeviceItemStatus.OFFLINE -> R.color.red
        }
        Log.i("HOME", dataSet[position].status.name)
        holder.connectionStatusView.setColorFilter(ContextCompat.getColor(holder.view.context, color))
    }

    override fun getItemCount() = dataSet.size
}


class HomeFragment : Fragment(), MenuProvider {
    private val database: DeviceDatabase by inject()
    private val manager: NabtoConnectionManager by inject()
    private val model: HomeViewModel by viewModels {
        HomeViewModelFactory(database, manager)
    }
    private val deviceListAdapter = DeviceListAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val recycler = view.findViewById<RecyclerView>(R.id.home_recycler)
        recycler.adapter = deviceListAdapter
        recycler.layoutManager = LinearLayoutManager(activity)

        view.findViewById<Button>(R.id.home_pair_new_button).setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_pairNewFragment)
        }

        model.deviceList.observe(viewLifecycleOwner, Observer { devices ->
            deviceListAdapter.submitDeviceList(devices)
            view.findViewById<View>(R.id.home_empty_layout).visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        })
    }

    fun onDeviceClick(device: Device) {
        val title = device.friendlyName.ifEmpty { getString(R.string.unnamed_device) }
        val bundle = bundleOf("device" to device, "title" to title)
        findNavController().navigate(R.id.action_homeFragment_to_devicePageFragment, bundle)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_pair_new) {
            findNavController().navigate(R.id.action_homeFragment_to_pairNewFragment)
            return true
        }
        return false
    }

    override fun onStart() {
        super.onStart()
        model.connectDevices()
    }

    override fun onStop() {
        super.onStop()
        model.releaseConnections()
    }
}