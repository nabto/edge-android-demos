package com.nabto.edge.nabtoheatpumpdemo

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

