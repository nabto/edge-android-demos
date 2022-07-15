@file:OptIn(ExperimentalSerializationApi::class)

package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.*
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

// @TODO: Clicking on a device should dispatch to a correct fragment for that device
//        E.g. click on heatpump device -> navigate to a heatpump fragment
//        Currently we always navigate to DevicePageFragment which is just a heatpump fragment

class HeatPumpViewModel(
    private val repo: NabtoRepository,
    device: Device,
    connectionService: NabtoConnectionService,
) : ViewModel() {
    sealed class HeatPumpEvent {
        class Update(val state: HeatPumpState): HeatPumpEvent()
        object LostConnection: HeatPumpEvent()
        object FailedToConnect: HeatPumpEvent()
    }

    private val heatPumpConnection: HeatPumpConnection = HeatPumpConnection(repo, device, connectionService)
    private val heatPumpEvent: MutableLiveData<HeatPumpEvent> = MutableLiveData()

    private val TAG = this.javaClass.simpleName
    private var isConnected = false
    private val updatesPerSecond = 10.0

    init {
        viewModelScope.launch {
            heatPumpConnection.subscribe { onConnectionChanged(it) }
        }

        viewModelScope.launch {
            heatPumpConnection.connect()
        }
    }

    private fun onConnectionChanged(state: DeviceConnectionEvent) {
        Log.i(TAG, "Device connection state changed to: $state")
        when (state) {
            DeviceConnectionEvent.CONNECTED -> onConnected()
            DeviceConnectionEvent.DEVICE_DISCONNECTED -> onDeviceDisconnected()
            DeviceConnectionEvent.FAILED_TO_CONNECT -> heatPumpEvent.postValue(HeatPumpEvent.FailedToConnect)
            else -> {}
        }
    }

    private fun onConnected() {
        viewModelScope.launch {
            isConnected = true
            updateLoop()
        }
    }

    private fun onDeviceDisconnected() {
        viewModelScope.launch {
            isConnected = false
            heatPumpEvent.postValue(HeatPumpEvent.LostConnection)
        }
    }

    private suspend fun updateLoop() {
        while (isConnected) {
            updateHeatPumpState()
            val delayTime = (1.0 / updatesPerSecond * 1000.0).toLong()
            delay(delayTime)
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
        heatPumpEvent.postValue(HeatPumpEvent.Update(state))
    }

    override fun onCleared() {
        super.onCleared()
        repo.getApplicationScope().launch {
            heatPumpConnection.close()
        }
    }
}

class DevicePageFragment : Fragment() {
    private var hasLoaded = false
    private val mainViewModel: MainViewModel by activityViewModels()
    private val model: HeatPumpViewModel by viewModel {
        val repo: NabtoRepository by inject()
        val service: NabtoConnectionService by inject()
        parametersOf(
            repo,
            requireArguments().get("device") as Device,
            service
        )
    }
    private lateinit var device: Device

    private val temperatureView: TextView by lazy {
        requireView().findViewById(R.id.dp_temperature)
    }

    private val modeSpinnerView: Spinner by lazy {
        requireView().findViewById(R.id.dp_mode_spinner)
    }

    private val powerSwitchView: Switch by lazy {
        requireView().findViewById(R.id.dp_power_switch)
    }

    private val targetSliderView: Slider by lazy {
        requireView().findViewById(R.id.dp_target_slider)
    }

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

        mainViewModel.setTitle(device.friendlyName.ifEmpty {
            getString(R.string.unnamed_device)
        })

        view.findViewById<TextView>(R.id.dp_info_name).text =
            device.friendlyName.ifEmpty { getString(R.string.unnamed_device) }
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

            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
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

            temperatureView.text = getString(R.string.temperature_format).format(event.state.temperature)
        }
    }
}