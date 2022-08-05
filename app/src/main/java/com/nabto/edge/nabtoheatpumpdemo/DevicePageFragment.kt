@file:OptIn(ExperimentalSerializationApi::class)

package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.android.ext.android.inject

// @TODO: Clicking on a device should dispatch to a correct fragment for that device
//        E.g. click on heatpump device -> navigate to a heatpump fragment
//        Currently we always navigate to DevicePageFragment which is just a heatpump fragment
//
// @TODO: Closing the app before the connection manages to close will not shut down the connection
//
// @TODO: Going to the device settings page has the DevicePageFragment enter a paused lifecycle staet
//        this means that the connection is eventually dropped if one doesnt return to the DevicePageFragment
//        This happens because of onStop/onResume being used for when the app enters background
//        Maybe we can detect going into the background in a different way?

class HeatPumpViewModelFactory(
    private val repo: NabtoRepository,
    private val device: Device,
    private val connectionService: NabtoConnectionService,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(
            NabtoRepository::class.java,
            Device::class.java,
            NabtoConnectionService::class.java
        ).newInstance(repo, device, connectionService)
    }
}

class HeatPumpViewModel(
    private val repo: NabtoRepository,
    device: Device,
    connectionService: NabtoConnectionService,
) : ViewModel() {
    sealed class HeatPumpEvent {
        class Update(val state: HeatPumpState) : HeatPumpEvent()
        object LostConnection : HeatPumpEvent()
        object FailedToConnect : HeatPumpEvent()
    }

    private val heatPumpConnection: HeatPumpConnection =
        HeatPumpConnection(repo, device, connectionService)
    private val heatPumpEvent: MutableLiveData<HeatPumpEvent> = MutableLiveData()

    private val TAG = this.javaClass.simpleName
    private var isConnected = false
    private var isInBackground = false

    // How many times per second should we request a state update from the device?
    private val updatesPerSecond = 10.0

    // If the app goes into the background, how long do we wait before killing the connection?
    // (in seconds)
    private val keepAliveTimeout = 5L

    init {
        viewModelScope.launch {
            heatPumpConnection.subscribe { onConnectionChanged(it) }
        }
    }

    fun connect() {
        isInBackground = false
        viewModelScope.launch {
            heatPumpConnection.connect()
        }
    }

    fun disconnect() {
        isInBackground = true
        viewModelScope.launch {
            delay(keepAliveTimeout * 1000)
            if (isInBackground) {
                heatPumpConnection.close()
            }
        }
    }

    private fun onConnectionChanged(state: DeviceConnectionEvent) {
        Log.i(TAG, "Device connection state changed to: $state")
        when (state) {
            DeviceConnectionEvent.CONNECTED -> onConnected()
            DeviceConnectionEvent.DEVICE_DISCONNECTED -> onDeviceDisconnected()
            DeviceConnectionEvent.FAILED_TO_CONNECT -> heatPumpEvent.postValue(HeatPumpEvent.FailedToConnect)
            DeviceConnectionEvent.CLOSED -> onConnectionClosed()
            else -> {}
        }
    }

    private fun onConnected() {
        isConnected = true
        viewModelScope.launch {
            updateLoop()
        }
    }

    private fun onConnectionClosed() {
        isConnected = false;
    }

    private fun onDeviceDisconnected() {
        isConnected = false
        viewModelScope.launch {
            heatPumpEvent.postValue(HeatPumpEvent.LostConnection)
        }
    }

    private suspend fun updateLoop() {
        withContext(Dispatchers.IO) {
            while (isConnected) {
                if (isInBackground) continue;
                updateHeatPumpState()
                val delayTime = (1.0 / updatesPerSecond * 1000.0).toLong()
                delay(delayTime)
            }
        }
    }

    fun getHeatPumpEventQueue(): LiveData<HeatPumpEvent> {
        return heatPumpEvent
    }

    fun setPower(toggled: Boolean) {
        if (!isConnected) return
        viewModelScope.launch {
            heatPumpConnection.setPower(toggled)
            updateHeatPumpState()
        }
    }

    fun setMode(mode: HeatPumpMode) {
        if (!isConnected) return
        viewModelScope.launch {
            heatPumpConnection.setMode(mode)
            updateHeatPumpState()
        }
    }

    fun setTarget(value: Double) {
        if (!isConnected) return
        viewModelScope.launch {
            heatPumpConnection.setTarget(value)
            updateHeatPumpState()
        }
    }

    private suspend fun updateHeatPumpState() {
        val state = heatPumpConnection.getState()
        if (state.valid) {
            heatPumpEvent.postValue(HeatPumpEvent.Update(state))
        } else {
            heatPumpEvent.postValue(HeatPumpEvent.LostConnection)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repo.getApplicationScope().launch {
            heatPumpConnection.close()
        }
    }
}

class DevicePageFragment : Fragment(), MenuProvider {
    private val model: HeatPumpViewModel by navGraphViewModels(R.id.device_graph) {
        val repo: NabtoRepository by inject()
        val service: NabtoConnectionService by inject()
        HeatPumpViewModelFactory(
            repo,
            requireArguments().get("device") as Device,
            service
        )
    }

    private var hasLoaded = false
    private lateinit var device: Device
    private lateinit var temperatureView: TextView
    private lateinit var modeSpinnerView: Spinner
    private lateinit var powerSwitchView: Switch
    private lateinit var targetSliderView: Slider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = requireArguments().get("device") as Device
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_device_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hasLoaded = false
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        temperatureView = view.findViewById(R.id.dp_temperature)
        modeSpinnerView = view.findViewById(R.id.dp_mode_spinner)
        powerSwitchView = view.findViewById(R.id.dp_power_switch)
        targetSliderView = view.findViewById(R.id.dp_target_slider)

        view.findViewById<TextView>(R.id.dp_info_name).text =
            device.getDeviceNameOrElse(getString(R.string.unnamed_device))
        view.findViewById<TextView>(R.id.dp_info_devid).text = device.deviceId
        view.findViewById<TextView>(R.id.dp_info_proid).text = device.productId

        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.modes_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modeSpinnerView.adapter = adapter
        }

        model.getHeatPumpEventQueue()
            .observe(viewLifecycleOwner, Observer { event -> updateViewFromEvent(view, event) })

        powerSwitchView.setOnClickListener {
            model.setPower(powerSwitchView.isChecked)
        }

        targetSliderView.addOnChangeListener { _, value, _ ->
            model.setTarget(value.toDouble())
        }

        modeSpinnerView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val mode = HeatPumpMode.values()[pos]
                model.setMode(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        model.connect()
    }

    override fun onStop() {
        super.onStop()
        model.disconnect()
    }

    private fun updateViewFromEvent(view: View, event: HeatPumpViewModel.HeatPumpEvent) {
        if (event is HeatPumpViewModel.HeatPumpEvent.FailedToConnect) {
            val snackbar = Snackbar.make(
                view,
                "Failed to connect to device",
                Snackbar.LENGTH_LONG
            )
            snackbar.show()
            findNavController().popBackStack()
            return
        }

        if (event is HeatPumpViewModel.HeatPumpEvent.LostConnection) {
            val snackbar = Snackbar.make(
                view,
                "Lost connection to device",
                Snackbar.LENGTH_LONG
            )
            snackbar.show()
            findNavController().popBackStack()
            return
        }

        if (event is HeatPumpViewModel.HeatPumpEvent.Update) {
            if (!hasLoaded) {
                hasLoaded = true
                powerSwitchView.isChecked = event.state.power
                targetSliderView.value = event.state.target.toFloat()
                modeSpinnerView.setSelection(event.state.mode.ordinal)

                // Destroy the loading spinner and show device page to user
                view.findViewById<View>(R.id.dp_loading).visibility = View.GONE
                view.findViewById<View>(R.id.dp_main).visibility = View.VISIBLE
            }

            temperatureView.text =
                getString(R.string.temperature_format).format(event.state.temperature)
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_device, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_device_settings) {
            findNavController().navigate(R.id.action_devicePageFragment_to_deviceSettingsFragment)
            return true
        }
        return false
    }
}