package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

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

class HomeFragment : Fragment() {
    private val mainViewModel: MainViewModel by activityViewModels()
    private val database: DeviceDatabase by inject()
    private val model: HomeViewModel by viewModel { parametersOf(database) }
    private val deviceListAdapter = DeviceListAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        val recycler = view.findViewById<RecyclerView>(R.id.home_recycler)
        recycler.adapter = deviceListAdapter
        recycler.layoutManager = LinearLayoutManager(activity)

        model.deviceList.observe(viewLifecycleOwner) { devices ->
            deviceListAdapter.submitDeviceList(devices)
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainViewModel.setTitle(getString(R.string.title_home))
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