package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.view.*
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class HomeViewModelFactory(private val database: DeviceDatabase): ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(DeviceDatabase::class.java).newInstance(database)
    }
}

class HomeViewModel(private val database: DeviceDatabase) : ViewModel() {
    private val _deviceList = MutableLiveData<List<Device>>()
    val deviceList: LiveData<List<Device>>
    get() = _deviceList

    init {
        viewModelScope.launch {
            database.deviceDao().getAll().collect { devices ->
                _deviceList.postValue(devices)
            }
        }
    }
}

class HomeFragment : Fragment(), MenuProvider {
    private val database: DeviceDatabase by inject()
    private val model: HomeViewModel by viewModels {
        HomeViewModelFactory(database)
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

        model.deviceList.observe(viewLifecycleOwner) { devices ->
            deviceListAdapter.submitDeviceList(devices)
        }
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
}