package com.nabto.edge.nabtoheatpumpdemo

import android.util.Log
import com.nabto.edge.client.Connection
import com.nabto.edge.client.ConnectionEventsCallback
import com.nabto.edge.client.NabtoNoChannelsException
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class DeviceConnectionEvent {
    CONNECTING,
    CONNECTED,
    DEVICE_DISCONNECTED,
    FAILED_TO_CONNECT,
    CLOSED
}

data class SubscriberId(val index: Int)

interface DeviceConnection {
    fun subscribe(callback: (e: DeviceConnectionEvent) -> Unit): SubscriberId
    fun unsubscribe(id: SubscriberId)
    suspend fun connect()
    suspend fun close()
}

abstract class DeviceConnectionBase(
    private val repo: NabtoRepository, // needed for server key and client private key
    private val device: Device,
    private val connectionService: NabtoConnectionService
) : DeviceConnection {
    enum class State {
        CLOSED,
        CONNECTING,
        CONNECTED
    }

    private var nextIndex = AtomicInteger(0)
    private val subscribers: MutableMap<SubscriberId, (e: DeviceConnectionEvent) -> Unit> =
        mutableMapOf()

    protected lateinit var connection: Connection
        private set
    protected var connectionState = State.CLOSED
        private set
    private lateinit var listener: ConnectionEventsCallback


    override fun subscribe(callback: (e: DeviceConnectionEvent) -> Unit): SubscriberId {
        val id = SubscriberId(nextIndex.incrementAndGet())
        subscribers[id] = callback
        return id
    }

    override fun unsubscribe(id: SubscriberId) {
        subscribers.remove(id)
    }

    fun publish(event: DeviceConnectionEvent) {
        connectionState = when (event) {
            DeviceConnectionEvent.CONNECTED -> State.CONNECTED
            DeviceConnectionEvent.CONNECTING -> State.CONNECTING
            DeviceConnectionEvent.DEVICE_DISCONNECTED -> State.CLOSED
            DeviceConnectionEvent.FAILED_TO_CONNECT -> State.CLOSED
            DeviceConnectionEvent.CLOSED -> State.CLOSED
        }

        subscribers.forEach { cb ->
            cb.value(event)
        }
    }

    override suspend fun connect() {
        if (connectionState == State.CONNECTED) {
            // no-op if we're already connected
            return
        }

        connection = connectionService.createConnection()
        publish(DeviceConnectionEvent.CONNECTING)
        listener = object : ConnectionEventsCallback() {
            override fun onEvent(event: Int) {
                when (event) {
                    CLOSED -> {
                        if (connectionState == State.CONNECTED) {
                            // HeatPumpConnection.close() sets state to CLOSED
                            // So we only get here if the device itself has disconnected
                            publish(DeviceConnectionEvent.DEVICE_DISCONNECTED)
                        }
                    }
                    CONNECTED -> publish(DeviceConnectionEvent.CONNECTED)
                }
            }
        }
        connection.addConnectionEventsListener(listener);

        val options = JSONObject()
        options.put("ProductId", device.productId)
        options.put("DeviceId", device.deviceId)
        options.put("ServerKey", repo.getServerKey())
        options.put("PrivateKey", repo.getClientPrivateKey())
        options.put("ServerConnectToken", device.SCT)
        connection.updateOptions(options.toString())

        try {
            // @TODO: awaitConnect would set a callback and wait
            //        for that callback to respond, if we time out then
            //        the device might connect but we never respond to the callback?
            //        Unsure if this is actually the case, needs further investigation
            withTimeout(5000) {
                connection.connect()
            }
        } catch (e: NabtoNoChannelsException) {
            publish(DeviceConnectionEvent.FAILED_TO_CONNECT)
        }
    }

    // It's safe to call close() multiple times
    override suspend fun close() {
        val conn = connection
        if (connectionState == State.CONNECTED) {
            publish(DeviceConnectionEvent.CLOSED)
            conn.removeConnectionEventsListener(listener)
            withContext(Dispatchers.IO) {
                conn.close()
            }
        }
    }
}

/**
 * Helper class for pairing new users
 */
class UnpairedDeviceConnection(
    private val repo: NabtoRepository,
    private val device: Device,
    private val connectionService: NabtoConnectionService
) : DeviceConnectionBase(repo, device, connectionService) {
    private var password = ""

    private val iam by lazy {
        IamUtil.create()
    }

    suspend fun passwordAuthenticate(pw: String) {
        password = pw
        withContext(Dispatchers.IO) {
            connection.passwordAuthenticate("", password)
        }
    }

    suspend fun isCurrentUserPaired(): Boolean {
        return iam.awaitIsCurrentUserPaired(connection)
    }

    suspend fun getDeviceDetails(friendlyDeviceName: String): Device {
        val details = iam.awaitGetDeviceDetails(connection)
        val user = iam.awaitGetCurrentUser(connection)
        return Device(
            details.productId,
            details.deviceId,
            user.sct,
            details.appName ?: "",
            friendlyDeviceName
        )
    }

    suspend fun pairLocalOpen(desiredUsername: String, friendlyDeviceName: String): Device {
        iam.awaitPairLocalOpen(connection, desiredUsername)
        return getDeviceDetails(friendlyDeviceName)
    }

    suspend fun pairLocalPassword(desiredUsername: String, friendlyDeviceName: String): Device {
        iam.awaitPairPasswordOpen(connection, desiredUsername, password)
        return getDeviceDetails(friendlyDeviceName)
    }
}
