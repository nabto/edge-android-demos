package com.nabto.edge.thermostatdemo

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.nabto.edge.client.*
import com.nabto.edge.client.ktx.awaitConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class NabtoConnectionState {
    CLOSED,
    CONNECTING,
    CONNECTED
}

enum class NabtoConnectionEvent {
    /** the device is in the process of being connected to */
    CONNECTING,

    /** the device has been connected to, always comes after [CONNECTING] */
    CONNECTED,

    /** the device has been disconnected for some reason */
    DEVICE_DISCONNECTED,

    /** failed to connect to the device, always comes after [CONNECTING] */
    FAILED_TO_CONNECT,

    /**
     * the connection is paused, it is still connected but the connection may close soon
     * serves as a warning for subscribers to stop using the connection
     */
    PAUSED,

    /**
     * the connection has gone from being paused to unpaused
     * which means it will not close down and may still be used
     * always comes after [PAUSED]
     */
    UNPAUSED,

    /**
     * the connection is closed and should not be used anymore
     */
    CLOSED
}

data class ConnectionHandle(
    val productId: String,
    val deviceId: String
)

fun interface ConnectionEventListener {
    fun onConnectionEvent(event: NabtoConnectionEvent, handle: ConnectionHandle)
}

/**
 * Manager whose responsibility is keeping track of connections to Nabto Edge devices.
 * This is the most important interface with its corresponding class [NabtoConnectionManagerImpl]
 * Can be injected using Koin
 */
interface NabtoConnectionManager {
    /**
     * Request a connection to a device. Will return a [ConnectionHandle] and will attempt
     * to connect to the device, but it is not guaranteed that connection will succeed.
     * Use a [ConnectionEventListener] to wait for [NabtoConnectionEvent.CONNECTED], if
     * connection fails then the listener will get [NabtoConnectionEvent.FAILED_TO_CONNECT]
     *
     * Use [releaseHandle] to close the connection and invalidate the [ConnectionHandle]
     *
     * @param[device] The [Device] to which a connection should be established
     * @param[listener] A [ConnectionEventListener] that will receive [NabtoConnectionEvent] events.
     * @return A [ConnectionHandle] that represents a connection to [device]
     */
    fun requestConnection(device: Device, listener: ConnectionEventListener? = null): ConnectionHandle

    /**
     * Reconnected a closed connection. If the connection is already connected, this function
     * does not do anything.
     *
     * @param[handle] The [ConnectionHandle] to reconnect
     */
    fun reconnect(handle: ConnectionHandle)

    /**
     * Release a [ConnectionHandle]. The underlying connection is closed, subscribers are
     * unsubscribed and the handle is invalidated.
     *
     * @param[handle] The [ConnectionHandle] to be released
     */
    fun releaseHandle(handle: ConnectionHandle)

    /**
     * Subscribes to [NabtoConnectionEvent] events for the given [handle], the events are delivered to
     * [listener]
     *
     * @param[handle] The [ConnectionHandle] to subscribe for events to
     * @param[listener] A listener that will receive connection events from [handle]
     */
    fun subscribe(handle: ConnectionHandle, listener: ConnectionEventListener)

    /**
     * Unsubscribes [listener] so that it will not receive any more [NabtoConnectionEvent] events
     * from [handle]
     *
     * @param[handle] The handle to unsubscribe from
     * @param[listener] the listener that was subscribed
     */
    fun unsubscribe(handle: ConnectionHandle, listener: ConnectionEventListener)

    /**
     * Get a raw [Connection] object from the Nabto SDK.
     * You should only call this when you actually need the underlying [Connection]
     * and you should not save the reference.
     *
     * @param[handle] The handle that represents the desired [Connection]
     * @return A [Connection] object for [handle]
     *
     * @throws[IllegalStateException] If [handle] is not valid.
     */
    fun getConnection(handle: ConnectionHandle): Connection

    /**
     * Get a [Coap] object for the underlying connection which can be used to make coap requests.
     *
     * @param[handle] Handle for a connection.
     * @param[method] The coap method for the request (e.g. GET or POST).
     * @param[path] The coap path for the request.
     * @return A [Coap] object with the specified method and path.
     *
     * @throws[IllegalStateException] If [handle] is not valid.
     */
    fun createCoap(handle: ConnectionHandle, method: String, path: String): Coap

    /**
     * Get a [LiveData] object holding a [NabtoConnectionState] representing the connection
     * state of [handle].
     *
     * @param[handle] A valid [ConnectionHandle].
     * @return A [LiveData] object holding a [NabtoConnectionState] object, or null if
     * [handle] is invalid.
     */
    fun getConnectionState(handle: ConnectionHandle): LiveData<NabtoConnectionState>?
}

class NabtoConnectionManagerImpl(
    private val app: Application,
    private val repo: NabtoRepository,
    private val client: NabtoClient
): NabtoConnectionManager, LifecycleEventObserver {
    data class ConnectionData(
        var connection: Connection,
        val state: MutableLiveData<NabtoConnectionState>,
        val connectionEventsCallback: ConnectionEventsCallback,
        val options: String, // json string
        val subscribers: MutableList<ConnectionEventListener> = mutableListOf()
    )

    private val TAG = "NabtoConnectionManager"
    private val connectionMap = ConcurrentHashMap<ConnectionHandle, ConnectionData>()
    private var isAppInBackground = false

    // If the app goes into the background, how long do we wait before killing connections?
    private val keepAliveTimeoutSeconds = 5L

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun subscribe(handle: ConnectionHandle, listener: ConnectionEventListener) {
        connectionMap[handle]?.subscribers?.let {
            if (!it.contains(listener)) {
                it.add(listener)
            }
        }
    }

    override fun unsubscribe(handle: ConnectionHandle, listener: ConnectionEventListener) {
        connectionMap[handle]?.subscribers?.remove(listener)
    }

    private fun publish(data: ConnectionData?, event: NabtoConnectionEvent, handle: ConnectionHandle) {
        data?.state?.postValue(when (event) {
            NabtoConnectionEvent.CONNECTED -> NabtoConnectionState.CONNECTED
            NabtoConnectionEvent.CONNECTING -> NabtoConnectionState.CONNECTING
            NabtoConnectionEvent.DEVICE_DISCONNECTED -> NabtoConnectionState.CLOSED
            NabtoConnectionEvent.FAILED_TO_CONNECT -> NabtoConnectionState.CLOSED
            NabtoConnectionEvent.CLOSED -> NabtoConnectionState.CLOSED
            NabtoConnectionEvent.PAUSED -> NabtoConnectionState.CONNECTED
            NabtoConnectionEvent.UNPAUSED -> NabtoConnectionState.CONNECTED
        })

        data?.subscribers?.forEach {
            it.onConnectionEvent(event, handle)
        }
    }

    private fun publish(handle: ConnectionHandle, event: NabtoConnectionEvent) {
        publish(connectionMap[handle], event, handle)
    }

    private fun connect(handle: ConnectionHandle, makeNewConnection: Boolean = false) {
        connectionMap[handle]?.let {
            if (it.state.value != NabtoConnectionState.CLOSED) {
                // no-op if we're already connected
                return
            }

            if (makeNewConnection) {
                it.connection.removeConnectionEventsListener(it.connectionEventsCallback)
                it.connection = client.createConnection()
            }

            publish(handle, NabtoConnectionEvent.CONNECTING)
            it.connection.updateOptions(it.options)
            it.connection.addConnectionEventsListener(it.connectionEventsCallback)

            repo.getApplicationScope().launch(Dispatchers.IO) {
                try {
                    it.connection.awaitConnect()
                } catch (e: Exception) {
                    if (e is NabtoRuntimeException || e is NabtoNoChannelsException) {
                        Log.i(TAG, "Failed to connect, $e")
                        withContext(Dispatchers.Main) {
                            publish(handle, NabtoConnectionEvent.FAILED_TO_CONNECT)
                        }
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    override fun reconnect(handle: ConnectionHandle) {
        connect(handle, true)
    }

    private fun requestConnectionInternal(device: Device, listeners: MutableList<ConnectionEventListener>): ConnectionHandle {
        val handle = ConnectionHandle(device.productId, device.deviceId)
        if (connectionMap.containsKey(handle)) {
            // there is already an existing connection, just return the handle as-is
            Log.i(TAG, "Requested connection for ${device.deviceId} but a connection already exists")
            listeners.forEach { subscribe(handle, it) }
            return handle
        }

        val connection = client.createConnection()

        val connectionEventsCallback = object : ConnectionEventsCallback() {
            override fun onEvent(event: Int) {
                when (event) {
                    CLOSED -> {
                        if (connectionMap[handle]?.state?.value == NabtoConnectionState.CONNECTED) {
                            publish(handle, NabtoConnectionEvent.DEVICE_DISCONNECTED)
                        }
                    }
                    CONNECTED -> {
                        publish(handle, NabtoConnectionEvent.CONNECTED)
                    }
                }
            }
        }

        val options = JSONObject()
        options.put("ProductId", device.productId)
        options.put("DeviceId", device.deviceId)
        options.put("ServerKey", NabtoConfig.SERVER_KEY)
        options.put("PrivateKey", repo.getClientPrivateKey())
        options.put("ServerConnectToken", device.SCT)
        options.put("KeepAliveInterval", 2000)
        options.put("KeepAliveRetryInterval", 2000)
        options.put("KeepAliveMaxRetries", 5)

        // add new connection and subscribe to it
        connectionMap[handle] = ConnectionData(
            connection,
            MutableLiveData(NabtoConnectionState.CLOSED),
            connectionEventsCallback,
            options.toString()
        )
        listeners.forEach { subscribe(handle, it) }

        connect(handle)
        return handle
    }

    override fun requestConnection(device: Device, listener: ConnectionEventListener?): ConnectionHandle {
        val list = mutableListOf<ConnectionEventListener>()
        if (listener != null) {
            list.add(listener)
        }
        return requestConnectionInternal(device, list)
    }

    // closes the connection but does not release the handle
    private fun close(handle: ConnectionHandle) {
        connectionMap[handle]?.let { data ->
            if (data.state.value != NabtoConnectionState.CLOSED) {
                publish(handle, NabtoConnectionEvent.CLOSED)
                repo.getApplicationScope().launch(Dispatchers.IO) {
                    data.connection.close()
                    data.connection.removeConnectionEventsListener(data.connectionEventsCallback)
                }
            }
        }
    }

    override fun releaseHandle(handle: ConnectionHandle) {
        connectionMap.remove(handle)?.let { data ->
            if (data.state.value != NabtoConnectionState.CLOSED) {
                publish(data, NabtoConnectionEvent.CLOSED, handle)
                repo.getApplicationScope().launch(Dispatchers.IO) {
                    if (data.state.value == NabtoConnectionState.CONNECTED) data.connection.close()
                    data.connection.removeConnectionEventsListener(data.connectionEventsCallback)
                }
            }
        }
    }

    override fun getConnection(handle: ConnectionHandle): Connection {
        return connectionMap[handle]?.connection ?: run {
            throw IllegalStateException("Attempted to get Connection for invalid handle!")
        }
    }

    override fun createCoap(handle: ConnectionHandle, method: String, path: String): Coap {
        return connectionMap[handle]?.connection?.createCoap(method, path) ?: run {
            throw IllegalStateException("Attempted to create COAP object for invalid handle!")
        }
    }

    override fun getConnectionState(handle: ConnectionHandle): LiveData<NabtoConnectionState>? {
        return connectionMap[handle]?.state
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                isAppInBackground = false
                connectionMap.forEach { (handle, data) ->
                    if (data.state.value == NabtoConnectionState.CONNECTED) {
                        publish(handle, NabtoConnectionEvent.UNPAUSED)
                    }
                    connect(handle, true)
                }
            }
            Lifecycle.Event.ON_STOP -> {
                isAppInBackground = true
                connectionMap.forEach { (handle, data) ->
                    if (data.state.value != NabtoConnectionState.CLOSED) {
                        publish(handle, NabtoConnectionEvent.PAUSED)
                    }

                    repo.getApplicationScope().launch {
                        delay(keepAliveTimeoutSeconds * 1000)
                        if (isAppInBackground) {
                            close(handle)
                        }
                    }
                }
            }
            else -> {}
        }
    }
}
