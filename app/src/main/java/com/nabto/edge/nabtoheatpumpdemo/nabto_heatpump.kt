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
    private val invalidState = HeatPumpState(HeatPumpMode.UNKNOWN, false, 0.0, 0.0, false)
    private lateinit var connection: Connection
    private var connectionState = MutableLiveData(DeviceConnection.State.CLOSED)

    override fun getConnectionState(): LiveData<DeviceConnection.State> {
        return connectionState
    }

    override fun getCurrentConnectionState(): DeviceConnection.State {
        return connectionState.value ?: DeviceConnection.State.CLOSED
    }

    override suspend fun connect() {
        connection = NabtoHeatPumpApplication.nabtoClient.createConnection()
        connectionState.postValue(DeviceConnection.State.CONNECTING)
        connection.addConnectionEventsListener(object : ConnectionEventsCallback() {
            override fun onEvent(event: Int) {
                when (event) {
                    CLOSED -> connectionState.postValue(DeviceConnection.State.CLOSED)
                    CONNECTED -> connectionState.postValue(DeviceConnection.State.CONNECTED)
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
            connection.connectAsync()
        } catch (e: NabtoRuntimeException) {
            connectionState.postValue(DeviceConnection.State.CLOSED)
            Log.i("DeviceDebug", e.message.toString())
            // @TODO: Print to log?
        }
    }

    private suspend fun <T> safeCall(errorVal: T, code: suspend () -> T): T {
        if (getCurrentConnectionState() != DeviceConnection.State.CONNECTED) {
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
            connectionState.postValue(DeviceConnection.State.CLOSED)
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
    private val heatPumpConnection: HeatPumpConnection = HeatPumpConnection(connection_data, device)
    private val heatPumpState: MutableLiveData<HeatPumpState> = MutableLiveData()

    private val TAG = this.javaClass.simpleName
    private var isConnected = false
    private val updatesPerSecond = 10.0

    init {
        viewModelScope.launch {
            heatPumpConnection.getConnectionState().asFlow().collect { state ->
                onConnectionChanged(state)
            }
        }

        viewModelScope.launch {
            heatPumpConnection.connect()
        }
    }

    private fun onConnectionChanged(state: DeviceConnection.State) {
        Log.i(TAG, "Device connection state changed to: ${state.name}")
        when (state) {
            DeviceConnection.State.CLOSED -> viewModelScope.launch {
                if (isConnected) {
                    isConnected = false

                    // Update state here to send out an invalid state that the
                    // fragments can react to
                    updateHeatPumpState()
                }
            }
            DeviceConnection.State.CONNECTING -> {}
            DeviceConnection.State.CONNECTED -> viewModelScope.launch {
                if (!isConnected) {
                    isConnected = true
                    updateLoop()
                }
            }
        }
    }

    private suspend fun updateLoop() {
        while (isConnected) {
            updateHeatPumpState()
            val delayTime = (1.0 / updatesPerSecond * 1000.0).toLong()
            delay(delayTime)
        }
    }

    fun getHeatPumpState(): LiveData<HeatPumpState> {
        return heatPumpState
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
        heatPumpState.postValue(state)
    }

    override fun onCleared() {
        super.onCleared()
        applicationScope.launch {
            heatPumpConnection.close()
        }
    }
}
