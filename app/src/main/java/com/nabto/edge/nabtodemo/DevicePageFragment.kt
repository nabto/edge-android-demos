@file:OptIn(ExperimentalSerializationApi::class)

package com.nabto.edge.nabtodemo

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
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.TcpTunnel
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUser
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitGetCurrentUser
import kotlinx.coroutines.*
import kotlinx.serialization.*
import org.koin.android.ext.android.inject

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
    private val _connState: MutableLiveData<AppConnectionState> = MutableLiveData(AppConnectionState.INITIAL_CONNECTING)
    private val _connEvent: MutableLiveEvent<AppConnectionEvent> = MutableLiveEvent()
    private val _currentUser: MutableLiveData<IamUser> = MutableLiveData()

    private var isPaused = false
    private var updateLoopJob: Job? = null
    private val handle = connectionManager.requestConnection(device) { event, _ -> onConnectionChanged(event) }
    private lateinit var tunnel: TcpTunnel

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

                if (_connState.value == AppConnectionState.DISCONNECTED) {
                    _connEvent.postEvent(AppConnectionEvent.RECONNECTED)
                }

                Log.i(TAG, "Attempting to open tunnel service...")
                tunnel = connectionManager.openTunnelService(handle, "rtsp")
                _connState.postValue(AppConnectionState.CONNECTED)

                val user = iam.awaitGetCurrentUser(connectionManager.getConnection(handle))
                _currentUser.postValue(user)
            } catch (e: IamException) {
                _connEvent.postEvent(AppConnectionEvent.FAILED_UNKNOWN)
            } catch (e: NabtoRuntimeException) {
                Log.i(TAG, e.toString())
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

    fun setMediaSourceForPlayer(player: ExoPlayer) {
        player.apply {
            // @TODO:
            //val rtsp = "rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mp4"
            val rtsp = "rtsp://127.0.0.1:${tunnel.localPort}/video"
            Log.i(TAG, "Using url $rtsp")
            val source = RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(rtsp))
            setMediaSource(source)
            prepare()
        }
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

    private lateinit var device: Device

    private lateinit var mainLayout: View
    private lateinit var lostConnectionBar: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var loadingSpinner: View

    private lateinit var videoPlayer: StyledPlayerView
    private lateinit var exoPlayer: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = requireArguments().get("device") as Device

        exoPlayer = ExoPlayer.Builder(requireActivity()).apply {
            val loadControl = DefaultLoadControl.Builder().apply {
                setBufferDurationsMs(1000, 2000, 1000, 1000)
            }.build()
            setLoadControl(loadControl)
        }.build()
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
        videoPlayer = view.findViewById(R.id.video_player)

        videoPlayer.player = exoPlayer

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
    }

    private fun refresh() {
        model.tryReconnect()
    }

    private fun onConnectionStateChanged(view: View, state: AppConnectionState) {
        Log.i(TAG, "Connection state changed to $state")

        when (state) {
            AppConnectionState.INITIAL_CONNECTING -> {
                swipeRefreshLayout.visibility = View.INVISIBLE
                loadingSpinner.visibility = View.VISIBLE
            }

            AppConnectionState.CONNECTED -> {
                loadingSpinner.visibility = View.INVISIBLE
                lostConnectionBar.visibility = View.GONE
                swipeRefreshLayout.visibility = View.VISIBLE

                lostConnectionBar.animate()
                    .translationY(-lostConnectionBar.height.toFloat())
                mainLayout.animate()
                    .translationY(0f)

                model.setMediaSourceForPlayer(exoPlayer)
            }

            AppConnectionState.DISCONNECTED -> {
                lostConnectionBar.visibility = View.VISIBLE
                lostConnectionBar.animate()
                    .translationY(0f)
                mainLayout.animate()
                    .translationY(lostConnectionBar.height.toFloat())

                exoPlayer.stop()
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