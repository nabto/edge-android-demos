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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.nabto.edge.client.Coap
import com.nabto.edge.client.ErrorCode
import com.nabto.edge.client.ErrorCodes
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
    private val device: Device,
    private val connectionManager: NabtoConnectionManager,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(
            Device::class.java,
            NabtoConnectionManager::class.java
        ).newInstance(device, connectionManager)
    }
}

enum class HeatPumpConnectionState {
    INITIAL_CONNECTING,
    CONNECTED,
    DISCONNECTED
}

enum class HeatPumpConnectionEvent {
    RECONNECTED,
    FAILED_RECONNECT,
    FAILED_INITIAL_CONNECT
}

class HeatPumpViewModel(
    device: Device,
    private val connectionManager: NabtoConnectionManager
) : ViewModel() {
    private val TAG = this.javaClass.simpleName

    private val _heatPumpState: MutableLiveData<HeatPumpState> = MutableLiveData()
    val state: LiveData<HeatPumpState>
        get() = _heatPumpState

    private val _heatPumpConnState: MutableLiveData<HeatPumpConnectionState> = MutableLiveData(HeatPumpConnectionState.INITIAL_CONNECTING)
    val connectionState: LiveData<HeatPumpConnectionState>
        get() = _heatPumpConnState.distinctUntilChanged()

    private val _heatPumpConnEvent: MutableLiveEvent<HeatPumpConnectionEvent> = MutableLiveEvent()
    val connectionEvent: LiveEvent<HeatPumpConnectionEvent>
        get() = _heatPumpConnEvent

    private var isPaused = false
    private var updateLoopJob: Job? = null
    private val handle = connectionManager.requestConnection(device) { onConnectionChanged(it) }

    // How many times per second should we request a state update from the device?
    private val updatesPerSecond = 10.0

    private fun onConnectionChanged(state: NabtoConnectionEvent) {
        Log.i(TAG, "Device connection state changed to: $state")
        when (state) {
            NabtoConnectionEvent.CONNECTED -> {
                isPaused = false
                updateLoopJob = viewModelScope.launch {
                    updateLoop()
                }
                if (_heatPumpConnState.value == HeatPumpConnectionState.DISCONNECTED) {
                    _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.RECONNECTED)
                }
                _heatPumpConnState.postValue(HeatPumpConnectionState.CONNECTED)
            }

            NabtoConnectionEvent.DEVICE_DISCONNECTED -> {
                updateLoopJob?.cancel()
                updateLoopJob = null
                _heatPumpConnState.postValue(HeatPumpConnectionState.DISCONNECTED)
            }

            NabtoConnectionEvent.FAILED_TO_CONNECT -> {
                if (_heatPumpConnState.value == HeatPumpConnectionState.INITIAL_CONNECTING) {
                    _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_INITIAL_CONNECT)
                } else {
                    _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_RECONNECT)
                }
            }

            NabtoConnectionEvent.CLOSED -> {
                updateLoopJob?.cancel()
                updateLoopJob = null
            }

            NabtoConnectionEvent.PAUSED -> {
                isPaused = true
            }

            NabtoConnectionEvent.UNPAUSED -> {
                isPaused = false
            }
            else -> {}
        }
    }

    private suspend fun updateLoop() {
        withContext(Dispatchers.IO) {
            while (true) {
                if (isPaused) {
                    // the device is still connected but we need to stop querying it
                    delay(500)
                    continue
                }
                updateHeatPumpState()
                val delayTime = (1.0 / updatesPerSecond * 1000.0).toLong()
                delay(delayTime)
            }
        }
    }

    fun tryReconnect() {
        if (_heatPumpConnState.value == HeatPumpConnectionState.DISCONNECTED) {
            connectionManager.reconnect(handle)
        } else {
            _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.RECONNECTED)
        }
    }

    private suspend fun <T> safeCall(errorVal: T, code: suspend () -> T): T {
        return try {
            code()
        } catch (e: NabtoRuntimeException) {
            errorVal
        } catch (e: CancellationException) {
            Log.i(TAG, "safeCall was cancelled")
            errorVal
        }
    }

    fun setPower(toggled: Boolean) {
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
        // @TODO: We probably dont need invalidState
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
            _heatPumpState.postValue(state)
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.releaseHandle(handle)
    }
}

class DevicePageFragment : Fragment(), MenuProvider {
    private val model: HeatPumpViewModel by navGraphViewModels(R.id.device_graph) {
        val connectionManager: NabtoConnectionManager by inject()
        HeatPumpViewModelFactory(
            requireArguments().get("device") as Device,
            connectionManager
        )
    }

    private val TAG = this.javaClass.simpleName

    private lateinit var mainLayout: View
    private lateinit var lostConnectionBar: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var loadingSpinner: View

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
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        mainLayout = view.findViewById(R.id.dp_main)
        swipeRefreshLayout = view.findViewById(R.id.dp_swiperefresh)
        lostConnectionBar = view.findViewById(R.id.dp_lost_connection_bar)
        loadingSpinner =  view.findViewById(R.id.dp_loading)
        temperatureView = view.findViewById(R.id.dp_temperature)
        modeSpinnerView = view.findViewById(R.id.dp_mode_spinner)
        powerSwitchView = view.findViewById(R.id.dp_power_switch)
        targetSliderView = view.findViewById(R.id.dp_target_slider)

        model.state.observe(viewLifecycleOwner, Observer { state -> onStateChanged(view, state) })
        model.connectionState.observe(viewLifecycleOwner, Observer { state -> onConnectionStateChanged(view, state) })
        model.connectionEvent.observe(viewLifecycleOwner) { event -> onConnectionEvent(view, event) }

        swipeRefreshLayout.setOnRefreshListener {
            model.tryReconnect()
        }

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

    private fun onStateChanged(view: View, state: HeatPumpState) {
        powerSwitchView.isChecked = state.power
        targetSliderView.value = state.target.toFloat()
        modeSpinnerView.setSelection(state.mode.ordinal)
        temperatureView.text = getString(R.string.temperature_format).format(state.temperature)
    }

    private fun onConnectionStateChanged(view: View, state: HeatPumpConnectionState) {
        Log.i(TAG, "View state changed to $state")

        fun setViewsEnabled(enable: Boolean) {
            powerSwitchView.isEnabled = enable
            targetSliderView.isEnabled = enable
            modeSpinnerView.isEnabled = enable
            temperatureView.isEnabled = enable
        }

        when (state) {
            HeatPumpConnectionState.INITIAL_CONNECTING -> {
                swipeRefreshLayout.visibility = View.INVISIBLE
                loadingSpinner.visibility = View.VISIBLE
                setViewsEnabled(false)
            }

            HeatPumpConnectionState.CONNECTED -> {
                loadingSpinner.visibility = View.INVISIBLE
                lostConnectionBar.visibility = View.GONE
                swipeRefreshLayout.visibility = View.VISIBLE
                setViewsEnabled(true)

                lostConnectionBar.animate()
                    .translationY(-lostConnectionBar.height.toFloat())
                mainLayout.animate()
                    .translationY(0f)
            }

            HeatPumpConnectionState.DISCONNECTED -> {
                lostConnectionBar.visibility = View.VISIBLE
                lostConnectionBar.animate()
                    .translationY(0f)
                mainLayout.animate()
                    .translationY(lostConnectionBar.height.toFloat())
                setViewsEnabled(false)
            }
        }
    }

    private fun onConnectionEvent(view: View, event: HeatPumpConnectionEvent) {
        Log.i(TAG, "Got event: $event")
        when (event) {
            HeatPumpConnectionEvent.RECONNECTED -> {
                swipeRefreshLayout.isRefreshing = false
            }

            HeatPumpConnectionEvent.FAILED_RECONNECT -> {
                swipeRefreshLayout.isRefreshing = false
            }

            HeatPumpConnectionEvent.FAILED_INITIAL_CONNECT -> {
                val snackbar = Snackbar.make(
                    view,
                    "Failed to connect to device",
                    Snackbar.LENGTH_LONG
                )
                snackbar.show()
                findNavController().popBackStack()
            }
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