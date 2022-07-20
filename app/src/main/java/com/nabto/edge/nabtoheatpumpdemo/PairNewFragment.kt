package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

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
    private val model: PairNewViewModel by viewModels { PairNewViewModelFactory(database) }
    private val deviceListAdapter = DeviceListAdapter()

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
}