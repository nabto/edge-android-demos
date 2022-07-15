/**
 * nabto_heatpump.kt
 *   This file holds several classes related to the nabto heat pump demo.
 *   Most importantly it holds the HeatPumpConnection class which
 *   is an abstraction over a nabto client connection to a device
 *   allowing a nicer API for interacting with the heat pump.
 *
 *   HeatPumpViewModel is a ViewModel class meant to be used
 *   by fragments and activities, and has to be constructed with
 *   HeatPumpViewModelFactory.
 */

package com.nabto.edge.nabtoheatpumpdemo

import com.nabto.edge.client.Coap
import com.nabto.edge.client.Connection
import com.nabto.edge.client.ConnectionEventsCallback
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.ktx.connectAsync
import com.nabto.edge.client.ktx.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.json.JSONObject
import kotlin.collections.MutableMap
import kotlin.collections.forEach
import kotlin.collections.mutableMapOf
import kotlin.collections.set

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

private fun decodeHeatPumpStateFromCBOR(cbor: ByteArray): HeatPumpState {
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

class HeatPumpConnection(
    private val repo: NabtoRepository,
    private val device: Device,
    private val connectionService: NabtoConnectionService
) :
    DeviceConnection {
    enum class State {
        CLOSED,
        CONNECTING,
        CONNECTED
    }

    private var nextIndex = 0
    private val subscribers: MutableMap<SubscriberId, (e: DeviceConnectionEvent) -> Unit> =
        mutableMapOf()

    private val invalidState = HeatPumpState(HeatPumpMode.UNKNOWN, false, 0.0, 0.0, false)
    private lateinit var connection: Connection
    private var connectionState = State.CLOSED

    override fun subscribe(callback: (e: DeviceConnectionEvent) -> Unit): SubscriberId {
        val id = SubscriberId(nextIndex++)
        subscribers[id] = callback
        return id
    }

    override fun unsubscribe(id: SubscriberId) {
        subscribers.remove(id)
    }

    private fun publish(event: DeviceConnectionEvent) {
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
        connection = connectionService.createConnection()
        publish(DeviceConnectionEvent.CONNECTING)
        connection.addConnectionEventsListener(object : ConnectionEventsCallback() {
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
        })

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

    private suspend fun <T> safeCall(errorVal: T, code: suspend () -> T): T {
        if (connectionState != State.CONNECTED) {
            return errorVal
        }
        return try {
            code()
        } catch (e: NabtoRuntimeException) {
            // @TODO: Log errors here
            withContext(Dispatchers.IO) {
                connection.close()
            }
            errorVal
        }
    }

    override suspend fun close() {
        safeCall({}) {
            publish(DeviceConnectionEvent.CLOSED)
            withContext(Dispatchers.IO) {
                connection.close()
            }
        }
    }

    suspend fun getState(): HeatPumpState {
        return safeCall(invalidState) {
            val coap = connection.createCoap("GET", "/heat-pump")
            coap.executeAsync()
            val data = coap.responsePayload
            val statusCode = coap.responseStatusCode

            if (statusCode != 205) {
                return@safeCall invalidState
            }

            return@safeCall decodeHeatPumpStateFromCBOR(data)
        }
    }

    suspend fun setMode(mode: HeatPumpMode) {
        safeCall({}) {
            val coap = connection.createCoap("POST", "/heat-pump/mode")
            val cbor = Cbor.encodeToByteArray(mode.string)
            coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
            coap.executeAsync()
            if (coap.responseStatusCode != 204) {
                // @TODO: Better error handling
                throw(Exception("Failed to set heat pump power state"))
            }
        }
    }

    suspend fun setPower(power: Boolean) {
        safeCall({}) {
            val coap = connection.createCoap("POST", "/heat-pump/power")
            val cbor = Cbor.encodeToByteArray(power)
            coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
            coap.executeAsync()
            if (coap.responseStatusCode != 204) {
                // @TODO: Better error handling
                throw(Exception("Failed to set heat pump power state"))
            }
        }
    }

    suspend fun setTarget(target: Double) {
        safeCall({}) {
            val coap = connection.createCoap("POST", "/heat-pump/target")
            val cbor = Cbor.encodeToByteArray(target)
            coap.setRequestPayload(Coap.ContentFormat.APPLICATION_CBOR, cbor)
            coap.executeAsync()
            if (coap.responseStatusCode != 204) {
                // @TODO: Better error handling
                throw(Exception("Failed to set heat pump target temperature"))
            }
        }
    }
}
