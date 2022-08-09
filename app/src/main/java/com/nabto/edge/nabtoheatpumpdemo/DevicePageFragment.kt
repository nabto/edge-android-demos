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
import com.nabto.edge.client.Coap
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.ktx.awaitExecute
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
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

enum class HeatPumpMode(val string: String) {
    COOL("COOL"),
    HEAT("HEAT"),
    FAN("FAN"),
    DRY("DRY"),
    UNKNOWN("")
}

data class HeatPumpState(
    var mode: HeatPumpMode,
    var power: Boolean,
    var target: Double,
    var temperature: Double,
    val valid: Boolean = true
)

internal fun decodeHeatPumpStateFromCBOR(cbor: ByteArray): HeatPumpState {
    @Serializable
    data class HeatPumpCoapState(
        @Required @SerialName("Mode") val mode: String,
        @Required @SerialName("Power") val power: Boolean,
        @Required @SerialName("Target") val target: Double,
        @Required @SerialName("Temperature") val temperature: Double
    )

    val state = Cbor.decodeFromByteArray<HeatPumpCoapState>(cbor)
    return HeatPumpState(
        HeatPumpMode.valueOf(state.mode),
        state.power,
        state.target,
        state.temperature,
        true
    )
}

class HeatPumpViewModelFactory(
    private val repo: NabtoRepository,
    private val device: Device,
    private val connectionManager: NabtoConnectionManager,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(
            NabtoRepository::class.java,
            Device::class.java,
            NabtoConnectionManager::class.java
        ).newInstance(repo, device, connectionManager)
    }
}

class HeatPumpViewModel(
    private val repo: NabtoRepository,
    device: Device,
    private val connectionManager: NabtoConnectionManager
) : ViewModel() {
    sealed class HeatPumpEvent {
        class Update(val state: HeatPumpState) : HeatPumpEvent()
        object LostConnection : HeatPumpEvent()
        object FailedToConnect : HeatPumpEvent()
    }

    private val heatPumpEvent: MutableLiveData<HeatPumpEvent> = MutableLiveData()

    private val TAG = this.javaClass.simpleName
    private var isConnected = false
    private var isPaused = false
    private lateinit var updateLoopJob: Job
    private val handle = connectionManager.requestConnection(device, { onConnectionChanged(it) })

    // How many times per second should we request a state update from the device?
    private val updatesPerSecond = 10.0

    private fun onConnectionChanged(state: NabtoConnectionEvent) {
        Log.i(TAG, "Device connection state changed to: $state")
        when (state) {
            NabtoConnectionEvent.CONNECTED -> onConnected()
            NabtoConnectionEvent.DEVICE_DISCONNECTED -> onDeviceDisconnected()
            NabtoConnectionEvent.FAILED_TO_CONNECT -> heatPumpEvent.postValue(HeatPumpEvent.FailedToConnect)
            NabtoConnectionEvent.CLOSED -> onConnectionClosed()
            NabtoConnectionEvent.PAUSED -> { isPaused = true }
            NabtoConnectionEvent.UNPAUSED -> { isPaused = false }
            else -> {}
        }
    }

    private fun onConnected() {
        isConnected = true
        isPaused = false
        updateLoopJob = viewModelScope.launch {
            updateLoop()
        }
    }

    private fun onConnectionClosed() {
        isConnected = false
        updateLoopJob.cancel()
    }

    private fun onDeviceDisconnected() {
        isConnected = false
        updateLoopJob.cancel()
        viewModelScope.launch {
            heatPumpEvent.postValue(HeatPumpEvent.LostConnection)
        }
    }

    private suspend fun updateLoop() {
        withContext(Dispatchers.IO) {
            while (isConnected) {
                if (isPaused) continue
                updateHeatPumpState()
                val delayTime = (1.0 / updatesPerSecond * 1000.0).toLong()
                delay(delayTime)
            }
        }
    }

    fun getHeatPumpEventQueue(): LiveData<HeatPumpEvent> {
        return heatPumpEvent
    }

    private suspend fun <T> safeCall(errorVal: T, code: suspend () -> T): T {
        return try {
            code()
        } catch (e: NabtoRuntimeException) {
            // @TODO: Log errors here
            errorVal
        }
    }

    fun setPower(toggled: Boolean) {
        if (!isConnected) return
        viewModelScope.launch {
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, "POST", "/heat-pump/power")
                val cbor = Cbor.encodeToByteArray(toggled)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    // @TODO: Better error handling
                    throw(Exception("Failed to set heat pump power state"))
                }
            }
            updateHeatPumpState()
        }
    }

    fun setMode(mode: HeatPumpMode) {
        if (!isConnected) return
        viewModelScope.launch {
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, "POST", "/heat-pump/mode")
                val cbor = Cbor.encodeToByteArray(mode.string)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    // @TODO: Better error handling
                    throw(Exception("Failed to set heat pump power state"))
                }
            }
            updateHeatPumpState()
        }
    }

    fun setTarget(target: Double) {
        if (!isConnected) return
        viewModelScope.launch {
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, "POST", "/heat-pump/target")
                val cbor = Cbor.encodeToByteArray(target)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    // @TODO: Better error handling
                    throw(Exception("Failed to set heat pump target temperature"))
                }
            }
            updateHeatPumpState()
        }
    }

    private suspend fun updateHeatPumpState() {
        val invalidState = HeatPumpState(HeatPumpMode.UNKNOWN, false, 0.0, 0.0, false)
        val state = safeCall(invalidState) {
            val coap = connectionManager.createCoap(handle, "GET", "/heat-pump")
            coap.awaitExecute()
            val data = coap.responsePayload
            val statusCode = coap.responseStatusCode

            if (statusCode != 205) {
                return@safeCall invalidState
            }

            return@safeCall decodeHeatPumpStateFromCBOR(data)
        }
        if (state.valid) {
            heatPumpEvent.postValue(HeatPumpEvent.Update(state))
        } else {
            heatPumpEvent.postValue(HeatPumpEvent.LostConnection)
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.releaseHandle(handle)
    }
}

class DevicePageFragment : Fragment(), MenuProvider {
    private val model: HeatPumpViewModel by navGraphViewModels(R.id.device_graph) {
        val repo: NabtoRepository by inject()
        val connectionManager: NabtoConnectionManager by inject()
        HeatPumpViewModelFactory(
            repo,
            requireArguments().get("device") as Device,
            connectionManager
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