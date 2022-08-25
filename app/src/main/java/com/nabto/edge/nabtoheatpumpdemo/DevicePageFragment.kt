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
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nabto.edge.client.Coap
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.ktx.awaitExecute
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUser
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitGetCurrentUser
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.koin.android.ext.android.inject
import kotlin.math.roundToInt

// @TODO: Closing the app before the connection manages to close will not shut down the connection

enum class HeatPumpMode(val string: String) {
    COOL("COOL"),
    HEAT("HEAT"),
    FAN("FAN"),
    DRY("DRY")
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
    FAILED_INITIAL_CONNECT,
    FAILED_NOT_HEAT_PUMP,
    FAILED_TO_UPDATE,
    FAILED_UNKNOWN,
    FAILED_NOT_PAIRED
}

class HeatPumpViewModel(
    device: Device,
    private val connectionManager: NabtoConnectionManager
) : ViewModel() {
    private val TAG = this.javaClass.simpleName

    private val _serverState: MutableLiveData<HeatPumpState> = MutableLiveData()
    val serverState: LiveData<HeatPumpState>
        get() = _serverState

    private var lastClientUpdate = System.nanoTime()
    private val _clientState = MutableLiveData<HeatPumpState>()
    val clientState: LiveData<HeatPumpState>
        get() = _clientState

    private val _heatPumpConnState: MutableLiveData<HeatPumpConnectionState> = MutableLiveData(HeatPumpConnectionState.INITIAL_CONNECTING)
    val connectionState: LiveData<HeatPumpConnectionState>
        get() = _heatPumpConnState.distinctUntilChanged()

    private val _heatPumpConnEvent: MutableLiveEvent<HeatPumpConnectionEvent> = MutableLiveEvent()
    val connectionEvent: LiveEvent<HeatPumpConnectionEvent>
        get() = _heatPumpConnEvent

    private val _currentUser: MutableLiveData<IamUser> = MutableLiveData()
    val currentUser: LiveData<IamUser>
        get() = _currentUser

    private var isPaused = false
    private var updateLoopJob: Job? = null
    private val handle = connectionManager.requestConnection(device) { event, _ -> onConnectionChanged(event) }

    // How many times per second should we request a state update from the device?
    private val updatesPerSecond = 10.0

    private fun onConnectionChanged(state: NabtoConnectionEvent) {
        Log.i(TAG, "Device connection state changed to: $state")
        when (state) {
            NabtoConnectionEvent.CONNECTED -> {
                isPaused = false

                updateLoopJob = viewModelScope.launch {
                    val iam = IamUtil.create()

                    try {
                        val isPaired =
                            iam.isCurrentUserPaired(connectionManager.getConnection(handle))
                        if (!isPaired) {
                            Log.w(TAG, "User connected to device but is not paired!")
                            _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_NOT_PAIRED)
                            return@launch
                        }

                        val details = iam.getDeviceDetails(connectionManager.getConnection(handle))
                        if (details.appName != "HeatPump") {
                            Log.w(TAG, "The app name of the connected device is ${details.appName} instead of HeatPump!")
                            _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_NOT_HEAT_PUMP)
                            return@launch
                        }

                        val heatPumpState = getHeatPumpStateFromDevice()
                        _clientState.postValue(heatPumpState)

                        if (_heatPumpConnState.value == HeatPumpConnectionState.DISCONNECTED) {
                            _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.RECONNECTED)
                        }
                        _heatPumpConnState.postValue(HeatPumpConnectionState.CONNECTED)

                        val user = iam.awaitGetCurrentUser(connectionManager.getConnection(handle))
                        _currentUser.postValue(user)

                        updateLoop()
                    } catch (e: IamException) {
                        _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_UNKNOWN)
                    } catch (e: NabtoRuntimeException) {
                        _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_UNKNOWN)
                    } catch (e: CancellationException) {
                        _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_UNKNOWN)
                    }
                }
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
                val state = getHeatPumpStateFromDevice()
                if (state.valid) {
                    _serverState.postValue(state)
                }

                // we wait until 3 seconds have passed since the user last changed something before programmatically changing values
                val currentTime = System.nanoTime()
                val guardTime = 1e+9 * 3
                if (currentTime > (lastClientUpdate + guardTime)) {
                    val merged = state.copy(temperature = _clientState.value?.temperature ?: 0.0)
                    if (merged != _clientState.value) {
                        _clientState.postValue(state)
                    }
                }

                // delay until the next update is needed
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
            lastClientUpdate = System.nanoTime()
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, "POST", "/heat-pump/power")
                val cbor = Cbor.encodeToByteArray(toggled)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_TO_UPDATE)
                }
            }
            _clientState.value?.let {
                val state = it.copy()
                state.power = toggled
                _clientState.postValue(state)
            }
        }
    }

    fun setMode(mode: HeatPumpMode) {
        viewModelScope.launch {
            lastClientUpdate = System.nanoTime()
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, "POST", "/heat-pump/mode")
                val cbor = Cbor.encodeToByteArray(mode.string)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_TO_UPDATE)
                }
            }
            _clientState.value?.let {
                val state = it.copy()
                state.mode = mode
                _clientState.postValue(state)
            }
        }
    }

    fun setTarget(target: Double) {
        viewModelScope.launch {
            lastClientUpdate = System.nanoTime()
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, "POST", "/heat-pump/target")
                val cbor = Cbor.encodeToByteArray(target)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _heatPumpConnEvent.postEvent(HeatPumpConnectionEvent.FAILED_TO_UPDATE)
                }
            }
            _clientState.value?.let {
                val state = it.copy()
                state.target = target
                _clientState.postValue(state)
            }
        }
    }

    private suspend fun getHeatPumpStateFromDevice(): HeatPumpState {
        val invalidState = HeatPumpState(HeatPumpMode.COOL, false, 0.0, 0.0, false)
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
        return state
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
        connectionManager.releaseHandle(handle)
    }
}

class DevicePageFragment : Fragment(), MenuProvider {
    private val TAG = this.javaClass.simpleName

    private val model: HeatPumpViewModel by navGraphViewModels(R.id.device_graph) {
        val connectionManager: NabtoConnectionManager by inject()
        HeatPumpViewModelFactory(
            device,
            connectionManager
        )
    }

    // we need this to disable programmatic update of the slider when the user is interacting
    private var isTouchingTemperatureSlider = false
    private lateinit var device: Device

    private lateinit var mainLayout: View
    private lateinit var lostConnectionBar: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var loadingSpinner: View
    private lateinit var temperatureView: TextView
    private lateinit var modeSpinnerView: Spinner
    private lateinit var powerSwitchView: SwitchMaterial
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

        model.clientState.observe(viewLifecycleOwner, Observer { state -> onStateChanged(view, state) })
        model.serverState.observe(viewLifecycleOwner, Observer { state ->
            temperatureView.text = getString(R.string.temperature_format, state.temperature)
        })

        val targetTextView = view.findViewById<TextView>(R.id.dp_target_temperature)
        targetTextView.text = getString(R.string.target_format, targetSliderView.value.roundToInt())
        targetSliderView.addOnChangeListener { slider, value, fromUser ->
            targetTextView.text = getString(R.string.target_format, value.roundToInt())
        }

        model.connectionState.observe(viewLifecycleOwner, Observer { state -> onConnectionStateChanged(view, state) })
        model.connectionEvent.observe(viewLifecycleOwner) { event -> onConnectionEvent(view, event) }

        swipeRefreshLayout.setOnRefreshListener {
            refresh()
        }

        view.findViewById<TextView>(R.id.dp_info_appname).text = device.appName
        view.findViewById<TextView>(R.id.dp_info_devid).text = device.deviceId
        view.findViewById<TextView>(R.id.dp_info_proid).text = device.productId

        model.currentUser.observe(viewLifecycleOwner, Observer {
            view.findViewById<TextView>(R.id.dp_info_userid).text = it.username
        })

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

        modeSpinnerView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos >= 0 && pos < HeatPumpMode.values().size) {
                    val mode = HeatPumpMode.values()[pos]
                    model.setMode(mode)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }

        targetSliderView.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isTouchingTemperatureSlider = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                model.setTarget(slider.value.toDouble())
                isTouchingTemperatureSlider = false
            }

        })
    }

    private fun refresh() {
        model.tryReconnect()
    }

    private fun onStateChanged(view: View, state: HeatPumpState) {
        if (isTouchingTemperatureSlider) return
        powerSwitchView.isChecked = state.power
        var target = state.target.toFloat()
        val min = targetSliderView.valueFrom
        val max = targetSliderView.valueTo
        if (target > max) {
            target = max
            model.setTarget(target.toDouble())
        } else if (target < min) {
            target = min
            model.setTarget(target.toDouble())
        }
        targetSliderView.value = target
        modeSpinnerView.setSelection(state.mode.ordinal)
    }

    private fun onConnectionStateChanged(view: View, state: HeatPumpConnectionState) {
        Log.i(TAG, "Connection state changed to $state")

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
        Log.i(TAG, "Received connection event $event")
        when (event) {
            HeatPumpConnectionEvent.RECONNECTED -> {
                swipeRefreshLayout.isRefreshing = false
            }

            HeatPumpConnectionEvent.FAILED_RECONNECT -> {
                swipeRefreshLayout.isRefreshing = false
                view.snack(getString(R.string.failed_reconnect))
            }

            HeatPumpConnectionEvent.FAILED_INITIAL_CONNECT -> {
                view.snack(getString(R.string.device_page_failed_to_connect))
                findNavController().popBackStack()
            }

            HeatPumpConnectionEvent.FAILED_NOT_HEAT_PUMP -> {
                view.snack(getString(R.string.device_page_not_heatpump))
                findNavController().popBackStack()
            }

            HeatPumpConnectionEvent.FAILED_TO_UPDATE -> {
                view.snack(getString(R.string.device_page_failed_update))
            }

            HeatPumpConnectionEvent.FAILED_NOT_PAIRED -> {
                view.snack(getString(R.string.device_failed_not_paired))
                findNavController().popBackStack()
            }

            HeatPumpConnectionEvent.FAILED_UNKNOWN -> { }
         }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_device, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_device_refresh) {
            swipeRefreshLayout.isRefreshing = true
            refresh()
            return true
        }
        return false
    }
}