package com.nabto.edge.nabtoheatpumpdemo

import androidx.lifecycle.LiveData

interface DeviceConnection {
    enum class State {
        CLOSED,
        CONNECTING,
        CONNECTED
    }

    fun getConnectionState(): LiveData<State>
    fun getCurrentConnectionState(): State
    suspend fun connect()
    suspend fun close()
}
