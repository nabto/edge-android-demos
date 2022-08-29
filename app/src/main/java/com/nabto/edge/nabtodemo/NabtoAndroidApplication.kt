package com.nabto.edge.nabtodemo

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.*
import androidx.room.Room
import com.nabto.edge.client.*
import com.nabto.edge.client.ktx.awaitConnect
import kotlinx.coroutines.*
import org.json.JSONObject
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap

interface NabtoRepository {
    fun getClientPrivateKey(): String
    fun resetClientPrivateKey()
    fun getServerKey(): String
    fun getScannedDevices(): LiveData<List<MdnsDeviceInfo>>
    fun getApplicationScope(): CoroutineScope
    fun getDisplayName(): LiveData<String>
    fun setDisplayName(displayName: String)
}

data class MdnsDeviceInfo(
    val productId: String,
    val deviceId: String
)

class NabtoDeviceScanner(nabtoClient: NabtoClient) {
    private val deviceMap = HashMap<String, MdnsDeviceInfo>()
    private val _devices: MutableLiveData<List<MdnsDeviceInfo>> = MutableLiveData()
    val devices: LiveData<List<MdnsDeviceInfo>>
        get() = _devices

    init {
        nabtoClient.addMdnsResultListener({ result ->
            when (result?.action) {
                MdnsResult.Action.ADD,
                MdnsResult.Action.UPDATE -> {
                    deviceMap[result.serviceInstanceName] =
                        MdnsDeviceInfo(result.productId, result.deviceId)
                }
                MdnsResult.Action.REMOVE -> {
                    deviceMap.remove(result.serviceInstanceName)
                }
                else -> {}
            }
            _devices.postValue(ArrayList(deviceMap.values))
        }, NabtoConfig.MDNS_SUB_TYPE)

    }
}

private class NabtoRepositoryImpl(
    private val context: Context,
    private val nabtoClient: NabtoClient,
    private val scope: CoroutineScope,
    private val scanner: NabtoDeviceScanner
) : NabtoRepository {
    private val _displayName = MutableLiveData<String>()
    private val pref = context.getSharedPreferences(
        NabtoConfig.SHARED_PREFERENCES,
        Context.MODE_PRIVATE
    )

    init {
        run {
            // Store a client private key to be used for connections.
            val key = NabtoConfig.PRIVATE_KEY_PREF
            if (!pref.contains(key)) {
                val pk = nabtoClient.createPrivateKey()
                with(pref.edit()) {
                    putString(key, pk)
                    apply()
                }
            }
        }


        run {
            val key = NabtoConfig.DISPLAY_NAME_PREF
            if (!pref.contains(key)) {
                val name = Settings.Secure.getString(context.contentResolver, "bluetooth_name");
                with(pref.edit()) {
                    putString(key, name)
                    apply()
                }
            }

            pref.getString(key, null)?.let {
                _displayName.postValue(it)
            }
        }
    }

    override fun getClientPrivateKey(): String {
        val key = NabtoConfig.PRIVATE_KEY_PREF
        if (pref.contains(key)) {
            return pref.getString(key, null)!!
        } else {
            // @TODO: Replace this with an exception of our own that can have more context
            throw RuntimeException("Attempted to access client's private key, but it was not found.")
        }
    }

    override fun resetClientPrivateKey() {
        val key = NabtoConfig.PRIVATE_KEY_PREF
        val pk = nabtoClient.createPrivateKey()
        with(pref.edit()) {
            putString(key, pk)
            apply()
        }
    }

    override fun getServerKey(): String {
        return NabtoConfig.SERVER_KEY
    }

    // @TODO: Let application scope be injected instead of having to go through NabtoRepository?
    override fun getApplicationScope(): CoroutineScope {
        return scope
    }

    override fun getDisplayName(): LiveData<String> {
        return _displayName
    }

    override fun setDisplayName(displayName: String) {
        _displayName.postValue(displayName)
        val key = NabtoConfig.DISPLAY_NAME_PREF
        with(pref.edit()) {
            putString(key, displayName)
            apply()
        }
    }

    override fun getScannedDevices(): LiveData<List<MdnsDeviceInfo>> {
        return scanner.devices
    }
}

enum class NabtoConnectionState {
    CLOSED,
    CONNECTING,
    CONNECTED
}

enum class NabtoConnectionEvent {
    // the device is in the process of being connected to
    CONNECTING,

    // the device has been connected to, always follows a CONNECTING
    CONNECTED,

    // the device has been disconnected for some reason
    DEVICE_DISCONNECTED,

    // failed to connect to the device, always follows a CONNECTING
    FAILED_TO_CONNECT,

    // the connection is paused, it is still connected but the connection may close soon
    // serves as a warning for subscribers to stop using the connection
    PAUSED,

    // the connection has gone from being paused to unpaused
    // which means it will not close down and may still be used
    // always follows a PAUSED event
    UNPAUSED,

    // the connection is closed and should not be used anymore
    // CLOSED
    CLOSED
}

data class ConnectionHandle(
    val productId: String,
    val deviceId: String
)

fun interface ConnectionEventListener {
    fun onConnectionEvent(event: NabtoConnectionEvent, handle: ConnectionHandle)
}

interface NabtoConnectionManager {
    fun requestConnection(device: Device, listener: ConnectionEventListener? = null): ConnectionHandle
    fun reconnect(handle: ConnectionHandle)
    fun releaseHandle(handle: ConnectionHandle)
    fun subscribe(handle: ConnectionHandle, listener: ConnectionEventListener)
    fun unsubscribe(handle: ConnectionHandle, listener: ConnectionEventListener)

    fun getConnection(handle: ConnectionHandle): Connection
    fun createCoap(handle: ConnectionHandle, method: String, path: String): Coap
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
                        Log.w(TAG, "Failed to connect, $e")
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
        options.put("ServerKey", repo.getServerKey())
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

private fun appModule(client: NabtoClient, scanner: NabtoDeviceScanner, scope: CoroutineScope) =
    module {
        single {
            Room.databaseBuilder(
                androidApplication(),
                DeviceDatabase::class.java,
                "device-database"
            ).build()
        }

        single<NabtoRepository> { NabtoRepositoryImpl(androidApplication(), client, scope, scanner) }

        single<NabtoConnectionManager> {
            NabtoConnectionManagerImpl(androidApplication(), get(), client)
        }
    }

class NabtoAndroidApplication : Application() {
    private val nabtoClient: NabtoClient by lazy { NabtoClient.create(this) }
    private val scanner: NabtoDeviceScanner by lazy { NabtoDeviceScanner(nabtoClient) }
    private val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger()
            androidContext(this@NabtoAndroidApplication)
            modules(appModule(nabtoClient, scanner, applicationScope))
        }
    }
}