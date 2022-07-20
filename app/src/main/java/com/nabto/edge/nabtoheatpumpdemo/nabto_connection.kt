package com.nabto.edge.nabtoheatpumpdemo

import com.nabto.edge.client.Connection
import com.nabto.edge.client.ConnectionEventsCallback
import com.nabto.edge.client.ktx.connectAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

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
    private val repo: NabtoRepository,
    private val device: Device,
    private val connectionService: NabtoConnectionService
) : DeviceConnection {
    enum class State {
        CLOSED,
        CONNECTING,
        CONNECTED
    }

    private var nextIndex = 0
    private val subscribers: MutableMap<SubscriberId, (e: DeviceConnectionEvent) -> Unit> =
        mutableMapOf()

    protected lateinit var connection: Connection
        private set
    protected var connectionState = State.CLOSED
        private set
    private lateinit var listener: ConnectionEventsCallback


    override fun subscribe(callback: (e: DeviceConnectionEvent) -> Unit): SubscriberId {
        val id = SubscriberId(nextIndex++)
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
        connection.updateOptions(options.toString())

        try {
            // @TODO: connectAsync would set a callback and wait
            //        for that callback to respond, if we time out then
            //        the device might connect but we never respond to the callback?
            //        Unsure if this is actually the case, needs further investigation
            withTimeout(2000) {
                connection.connectAsync()
            }
        } catch (e: Exception) {
            publish(DeviceConnectionEvent.FAILED_TO_CONNECT)
        }
    }

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
