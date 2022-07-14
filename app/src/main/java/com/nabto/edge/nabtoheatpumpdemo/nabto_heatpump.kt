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

import android.util.Log
import androidx.lifecycle.*
import com.nabto.edge.client.Coap
import com.nabto.edge.client.Connection
import com.nabto.edge.client.ConnectionEventsCallback
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.client.ktx.connectAsync
import com.nabto.edge.client.ktx.executeAsync
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.cbor.Cbor
import org.json.JSONObject

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

data class HeatPumpConnectionData(
    val client_private_key: String,
    val nabto_server_key: String
)

class HeatPumpConnection(private val data: HeatPumpConnectionData, private val device: Device) :
    DeviceConnection {
    enum class State {
        CLOSED,
        CONNECTING,
        CONNECTED
    }

    private var nextIndex = 0
    private val subscribers: MutableMap<SubscriberId, (e: DeviceConnectionEvent) -> Unit> = mutableMapOf()

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
        connection = NabtoHeatPumpApplication.nabtoClient.createConnection()
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
        options.put("ServerKey", data.nabto_server_key)
        options.put("PrivateKey", data.client_private_key)
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

class HeatPumpViewModelFactory(
    private val data: HeatPumpConnectionData,
    private val device: Device,
    private val scope: CoroutineScope
) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(
            HeatPumpConnectionData::class.java,
            Device::class.java,
            CoroutineScope::class.java
        ).newInstance(data, device, scope)
    }
}

class HeatPumpViewModel(
    connection_data: HeatPumpConnectionData,
    device: Device,
    private val applicationScope: CoroutineScope
) : ViewModel() {
    sealed class HeatPumpEvent {
        class Update(val state: HeatPumpState): HeatPumpEvent()
        object LostConnection: HeatPumpEvent()
        object FailedToConnect: HeatPumpEvent()
    }

    private val heatPumpConnection: HeatPumpConnection = HeatPumpConnection(connection_data, device)
    private val heatPumpEvent: MutableLiveData<HeatPumpEvent> = MutableLiveData()

    private val TAG = this.javaClass.simpleName
    private var isConnected = false
    private val updatesPerSecond = 10.0

    init {
        viewModelScope.launch {
            heatPumpConnection.subscribe { onConnectionChanged(it) }
        }

        viewModelScope.launch {
            heatPumpConnection.connect()
        }
    }

    private fun onConnectionChanged(state: DeviceConnectionEvent) {
        Log.i(TAG, "Device connection state changed to: $state")
        when (state) {
            DeviceConnectionEvent.CONNECTED -> onConnected()
            DeviceConnectionEvent.DEVICE_DISCONNECTED -> onDeviceDisconnected()
            DeviceConnectionEvent.FAILED_TO_CONNECT -> heatPumpEvent.postValue(HeatPumpEvent.FailedToConnect)
            else -> {}
        }
    }

    private fun onConnected() {
        viewModelScope.launch {
            isConnected = true
            updateLoop()
        }
    }

    private fun onDeviceDisconnected() {
        viewModelScope.launch {
            isConnected = false
            heatPumpEvent.postValue(HeatPumpEvent.LostConnection)
        }
    }

    private suspend fun updateLoop() {
        while (isConnected) {
            updateHeatPumpState()
            val delayTime = (1.0 / updatesPerSecond * 1000.0).toLong()
            delay(delayTime)
        }
    }

    fun getHeatPumpEventQueue(): LiveData<HeatPumpEvent> {
        return heatPumpEvent
    }

    fun setPower(toggled: Boolean) {
        if (!isConnected) return
        viewModelScope.launch {
            heatPumpConnection.setPower(toggled)
            updateHeatPumpState()
        }
    }

    fun setMode(mode: HeatPumpMode) {
        if (!isConnected) return
        viewModelScope.launch {
            heatPumpConnection.setMode(mode)
            updateHeatPumpState()
        }
    }

    fun setTarget(value: Double) {
        if (!isConnected) return
        viewModelScope.launch {
            heatPumpConnection.setTarget(value)
            updateHeatPumpState()
        }
    }

    private suspend fun updateHeatPumpState() {
        val state = heatPumpConnection.getState()
        heatPumpEvent.postValue(HeatPumpEvent.Update(state))
    }

    override fun onCleared() {
        super.onCleared()
        applicationScope.launch {
            heatPumpConnection.close()
        }
    }
}
