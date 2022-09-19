@file:OptIn(ExperimentalSerializationApi::class)

package com.nabto.edge.thermostatdemo

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
import com.nabto.edge.sharedcode.*

/**
 * Enum that represent the states that the thermostat can be in
 */
enum class AppMode(val string: String) {
    COOL("COOL"),
    HEAT("HEAT"),
    FAN("FAN"),
    DRY("DRY")
}

/**
 * AppState is a dumb data class meant to hold the data that is received
 * from a GET procedure to the COAP path that holds the app's state
 */
data class AppState(
    var mode: AppMode,
    var power: Boolean,
    var target: Double,
    var temperature: Double,
    val valid: Boolean = true
)

internal fun decodeAppStateFromCBOR(cbor: ByteArray): AppState {
    @Serializable
    data class AppCoapState(
        @Required @SerialName("Mode") val mode: String,
        @Required @SerialName("Power") val power: Boolean,
        @Required @SerialName("Target") val target: Double,
        @Required @SerialName("Temperature") val temperature: Double
    )

    val state = Cbor.decodeFromByteArray<AppCoapState>(cbor)
    return AppState(
        AppMode.valueOf(state.mode),
        state.power,
        state.target,
        state.temperature,
        true
    )
}

class DevicePageViewModelFactory(
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

/**
 * Represents different states that the [DevicePageFragment] can be in
 * [INITIAL_CONNECTING] is used for when the user moves from [HomeFragment] to [DevicePageFragment]
 * where we initially display a loading spinner while a connection is being made.
 */
enum class AppConnectionState {
    INITIAL_CONNECTING,
    CONNECTED,
    DISCONNECTED
}

/**
 * Represents different events that might happen while the user is interacting with the device
 */
enum class AppConnectionEvent {
    RECONNECTED,
    FAILED_RECONNECT,
    FAILED_INITIAL_CONNECT,
    FAILED_INCORRECT_APP,
    FAILED_TO_UPDATE,
    FAILED_UNKNOWN,
    FAILED_NOT_PAIRED
}

/**
 * [ViewModel] that manages data for [DevicePageFragment].
 * This class is responsible for interacting with [NabtoConnectionManager] to do
 * COAP calls for getting or setting device state as well as requesting and releasing
 * connection handles from the manager.
 */
class DevicePageViewModel(
    device: Device,
    private val connectionManager: NabtoConnectionManager
) : ViewModel() {
    private val TAG = this.javaClass.simpleName

    // COAP paths that the ViewModel will use for getting/setting data
    data class CoapPath(val method: String, val path: String) {}
    private val powerCoap = CoapPath("POST", "/thermostat/power")
    private val modeCoap = CoapPath("POST", "/thermostat/mode")
    private val targetCoap = CoapPath("POST", "/thermostat/target")
    private val appStateCoap = CoapPath("GET", "/thermostat")

    private var lastClientUpdate = System.nanoTime()
    private val _serverState: MutableLiveData<AppState> = MutableLiveData()
    private val _clientState = MutableLiveData<AppState>()
    private val _connState: MutableLiveData<AppConnectionState> = MutableLiveData(AppConnectionState.INITIAL_CONNECTING)
    private val _connEvent: MutableLiveEvent<AppConnectionEvent> = MutableLiveEvent()
    private val _currentUser: MutableLiveData<IamUser> = MutableLiveData()

    private var isPaused = false
    private var updateLoopJob: Job? = null
    private val handle = connectionManager.requestConnection(device) { event, _ -> onConnectionChanged(event) }

    // how many times per second should we request a state update from the device?
    private val updatesPerSecond = 10.0

    /**
     * serverState is a LiveData object that holds an AppState that was recently retrieved
     * from the device. The contained AppState is not shown to the user directly but is
     * merged with clientState whenever the user is not directly interacting with the UI.
     */
    val serverState: LiveData<AppState>
        get() = _serverState

    /**
     * clientState is a LiveData object that holds the AppState data that the DevicePageFragment
     * uses for updating UI. clientState is updated whenever the user interacts with the UI
     * and is also updated from the serverState LiveData whenever the user is not interacting.
     *
     * This is to allow for the UI to represent the "latest" state of the device without
     * impacting user experience by programmatically changing UI when the user is interacting.
     */
    val clientState: LiveData<AppState>
        get() = _clientState

    /**
     * connectionState contains the current state of the connection that the DevicePageFragment
     * uses for updating UI.
     */
    val connectionState: LiveData<AppConnectionState>
        get() = _connState.distinctUntilChanged()

    /**
     * connectionEvent sends out an event when something happens to the connection.
     * DevicePageFragment uses this to act correspondingly with the event in cases of
     * connection being lost or other such error states.
     */
    val connectionEvent: LiveEvent<AppConnectionEvent>
        get() = _connEvent

    /**
     * The user info of the currently connected user. DevicePageFragment can use this to
     * represent information about the user in the UI.
     */
    val currentUser: LiveData<IamUser>
        get() = _currentUser

    init {
        // We're already connected from the home page.
        if (connectionManager.getConnectionState(handle)?.value == NabtoConnectionState.CONNECTED) {
            startup()
        }
    }

    private fun startup() {
        isPaused = false

        updateLoopJob = viewModelScope.launch {
            val iam = IamUtil.create()

            try {
                val isPaired =
                    iam.isCurrentUserPaired(connectionManager.getConnection(handle))
                if (!isPaired) {
                    Log.i(TAG, "User connected to device but is not paired!")
                    _connEvent.postEvent(AppConnectionEvent.FAILED_NOT_PAIRED)
                    return@launch
                }

                val details = iam.getDeviceDetails(connectionManager.getConnection(handle))
                if (details.appName != NabtoConfig.DEVICE_APP_NAME) {
                    Log.i(TAG, "The app name of the connected device is ${details.appName} instead of ${NabtoConfig.DEVICE_APP_NAME}!")
                    _connEvent.postEvent(AppConnectionEvent.FAILED_INCORRECT_APP)
                    return@launch
                }

                val appState = getAppStateFromDevice()
                _clientState.postValue(appState)

                if (_connState.value == AppConnectionState.DISCONNECTED) {
                    _connEvent.postEvent(AppConnectionEvent.RECONNECTED)
                }
                _connState.postValue(AppConnectionState.CONNECTED)

                val user = iam.awaitGetCurrentUser(connectionManager.getConnection(handle))
                _currentUser.postValue(user)

                updateLoop()
            } catch (e: IamException) {
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: NabtoRuntimeException) {
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: CancellationException) {
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            }
        }
    }

    private fun onConnectionChanged(state: NabtoConnectionEvent) {
        Log.i(TAG, "Device connection state changed to: $state")
        when (state) {
            NabtoConnectionEvent.CONNECTED -> {
                startup()
            }

            NabtoConnectionEvent.DEVICE_DISCONNECTED -> {
                updateLoopJob?.cancel()
                updateLoopJob = null
                _connState.postValue(AppConnectionState.DISCONNECTED)
            }

            NabtoConnectionEvent.FAILED_TO_CONNECT -> {
                if (_connState.value == AppConnectionState.INITIAL_CONNECTING) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_INITIAL_CONNECT)
                } else {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_RECONNECT)
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
                val state = getAppStateFromDevice()
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

    /**
     * Try to reconnect a disconnected connection.
     *
     * If the connection is not disconnected, a RECONNECTED event will be sent out.
     */
    fun tryReconnect() {
        if (_connState.value == AppConnectionState.DISCONNECTED) {
            connectionManager.reconnect(handle)
        } else {
            _connEvent.postEvent(AppConnectionEvent.RECONNECTED)
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

    /**
     * Set the power state on the device and update corresponding LiveData variables.
     */
    fun setPower(toggled: Boolean) {
        viewModelScope.launch {
            lastClientUpdate = System.nanoTime()
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, powerCoap.method, powerCoap.path)
                val cbor = Cbor.encodeToByteArray(toggled)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_TO_UPDATE)
                }
            }
            _clientState.value?.let {
                val state = it.copy()
                state.power = toggled
                _clientState.postValue(state)
            }
        }
    }

    /**
     * Set the mode on the device and update corresponding LiveData variables.
     */
    fun setMode(mode: AppMode) {
        viewModelScope.launch {
            lastClientUpdate = System.nanoTime()
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, modeCoap.method, modeCoap.path)
                val cbor = Cbor.encodeToByteArray(mode.string)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_TO_UPDATE)
                }
            }
            _clientState.value?.let {
                val state = it.copy()
                state.mode = mode
                _clientState.postValue(state)
            }
        }
    }

    /**
     * Set the target temperature on the device and update corresponding LiveData variables.
     */
    fun setTarget(target: Double) {
        viewModelScope.launch {
            lastClientUpdate = System.nanoTime()
            safeCall({}) {
                val coap = connectionManager.createCoap(handle, targetCoap.method, targetCoap.path)
                val cbor = Cbor.encodeToByteArray(target)
                coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
                coap.awaitExecute()
                if (coap.responseStatusCode != 204) {
                    _connEvent.postEvent(AppConnectionEvent.FAILED_TO_UPDATE)
                }
            }
            _clientState.value?.let {
                val state = it.copy()
                state.target = target
                _clientState.postValue(state)
            }
        }
    }

    private suspend fun getAppStateFromDevice(): AppState {
        val invalidState = AppState(AppMode.COOL, false, 0.0, 0.0, false)
        val state = safeCall(invalidState) {
            val coap = connectionManager.createCoap(handle, appStateCoap.method, appStateCoap.path)
            coap.awaitExecute()
            val data = coap.responsePayload
            val statusCode = coap.responseStatusCode

            if (statusCode != 205) {
                return@safeCall invalidState
            }

            return@safeCall decodeAppStateFromCBOR(data)
        }
        return state
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.cancel()
        connectionManager.releaseHandle(handle)
    }
}

/**
 * Fragment for fragment_device_page.xml.
 * Responsible for letting the user interact with their thermostat device.
 *
 * [DevicePageFragment] sets observers on [LiveData] received from [DevicePageViewModel]
 * and receives [AppConnectionEvent] updates.
 */
class DevicePageFragment : Fragment(), MenuProvider {
    private val TAG = this.javaClass.simpleName

    private val model: DevicePageViewModel by navGraphViewModels(R.id.device_graph) {
        val connectionManager: NabtoConnectionManager by inject()
        DevicePageViewModelFactory(
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
                if (pos >= 0 && pos < AppMode.values().size) {
                    val mode = AppMode.values()[pos]
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

    private fun onStateChanged(view: View, state: AppState) {
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

    private fun onConnectionStateChanged(view: View, state: AppConnectionState) {
        Log.i(TAG, "Connection state changed to $state")

        fun setViewsEnabled(enable: Boolean) {
            powerSwitchView.isEnabled = enable
            targetSliderView.isEnabled = enable
            modeSpinnerView.isEnabled = enable
            temperatureView.isEnabled = enable
        }

        when (state) {
            AppConnectionState.INITIAL_CONNECTING -> {
                swipeRefreshLayout.visibility = View.INVISIBLE
                loadingSpinner.visibility = View.VISIBLE
                setViewsEnabled(false)
            }

            AppConnectionState.CONNECTED -> {
                loadingSpinner.visibility = View.INVISIBLE
                lostConnectionBar.visibility = View.GONE
                swipeRefreshLayout.visibility = View.VISIBLE
                setViewsEnabled(true)

                lostConnectionBar.animate()
                    .translationY(-lostConnectionBar.height.toFloat())
                mainLayout.animate()
                    .translationY(0f)
            }

            AppConnectionState.DISCONNECTED -> {
                lostConnectionBar.visibility = View.VISIBLE
                lostConnectionBar.animate()
                    .translationY(0f)
                mainLayout.animate()
                    .translationY(lostConnectionBar.height.toFloat())
                setViewsEnabled(false)
            }
        }
    }

    private fun onConnectionEvent(view: View, event: AppConnectionEvent) {
        Log.i(TAG, "Received connection event $event")
        when (event) {
            AppConnectionEvent.RECONNECTED -> {
                swipeRefreshLayout.isRefreshing = false
            }

            AppConnectionEvent.FAILED_RECONNECT -> {
                swipeRefreshLayout.isRefreshing = false
                view.snack(getString(R.string.failed_reconnect))
            }

            AppConnectionEvent.FAILED_INITIAL_CONNECT -> {
                view.snack(getString(R.string.device_page_failed_to_connect))
                findNavController().popBackStack()
            }

            AppConnectionEvent.FAILED_INCORRECT_APP -> {
                view.snack(getString(R.string.device_page_incorrect_app))
                findNavController().popBackStack()
            }

            AppConnectionEvent.FAILED_TO_UPDATE -> {
                view.snack(getString(R.string.device_page_failed_update))
            }

            AppConnectionEvent.FAILED_NOT_PAIRED -> {
                view.snack(getString(R.string.device_failed_not_paired))
                findNavController().popBackStack()
            }

            AppConnectionEvent.FAILED_UNKNOWN -> { }
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