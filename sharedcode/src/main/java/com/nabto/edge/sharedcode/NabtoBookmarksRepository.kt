package com.nabto.edge.sharedcode

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitIsCurrentUserPaired
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class BookmarkStatus {
    ONLINE,
    CONNECTING,
    OFFLINE,
    UNPAIRED
}

data class DeviceBookmark(
    val device: Device,
    val status: BookmarkStatus
)

interface NabtoBookmarksRepository {
    fun getDevices(): LiveData<List<DeviceBookmark>>

    fun getStatus(device: Device): BookmarkStatus

    fun synchronize()

    fun reconnect()

    fun releaseAllExcept(devices: List<Device>)

    fun releaseAll()
}

class NabtoBookmarksRepositoryImpl(
    private val database: DeviceDatabase,
    private val manager: NabtoConnectionManager,
    private val scope: CoroutineScope
): NabtoBookmarksRepository {
    private val TAG = NabtoBookmarksRepository::class.java.simpleName

    private val _deviceList = MutableLiveData<List<DeviceBookmark>>()
    val deviceList: LiveData<List<DeviceBookmark>>
        get() = _deviceList

    private val connections = mutableMapOf<Device, ConnectionHandle>()
    private val status = mutableMapOf<Device, BookmarkStatus>()

    private var devices: List<Device> = listOf()
    private var isSynchronized = false

    init {
        scope.launch {
            database.deviceDao().getAll().collect {
                devices = it
                if (isSynchronized) {
                    startDeviceConnections()
                }
            }
        }
    }

    private fun postDevices() {
        val list = devices.map {
            DeviceBookmark(it, status.getOrDefault(it, BookmarkStatus.OFFLINE))
        }
        _deviceList.postValue(list)
    }

    private fun startDeviceConnections() {
        for (key in devices) {
            status[key] = status[key] ?: BookmarkStatus.OFFLINE
            connections[key] = connections[key] ?: manager.requestConnection(key) { event, handle ->
                scope.launch { onDeviceEvent(key, event, handle) }
            }
        }

        postDevices()
    }

    private suspend fun onDeviceEvent(device: Device, event: NabtoConnectionEvent, handle: ConnectionHandle) {
        status[device] = when (event) {
            NabtoConnectionEvent.CONNECTING -> BookmarkStatus.CONNECTING
            NabtoConnectionEvent.CONNECTED -> {
                val iam = IamUtil.create()
                try {
                    val paired = iam.awaitIsCurrentUserPaired(manager.getConnection(handle))
                    if (paired) {
                        BookmarkStatus.ONLINE
                    } else {
                        BookmarkStatus.UNPAIRED
                    }
                } catch (e: IamException) {
                    Log.i(TAG, "onDeviceEvent caught exception $e")
                    BookmarkStatus.OFFLINE
                }
            }
            NabtoConnectionEvent.DEVICE_DISCONNECTED -> BookmarkStatus.OFFLINE
            NabtoConnectionEvent.FAILED_TO_CONNECT -> BookmarkStatus.OFFLINE
            NabtoConnectionEvent.PAUSED -> BookmarkStatus.ONLINE
            NabtoConnectionEvent.UNPAUSED -> BookmarkStatus.ONLINE
            NabtoConnectionEvent.CLOSED -> BookmarkStatus.OFFLINE
        }
        postDevices()
    }

    override fun getDevices(): LiveData<List<DeviceBookmark>> {
        return deviceList
    }

    override fun getStatus(device: Device): BookmarkStatus {
        return status.getOrDefault(device, BookmarkStatus.OFFLINE)
    }

    override fun synchronize() {
        isSynchronized = true
        startDeviceConnections()
    }

    override fun reconnect() {
        for ((_, handle) in connections) {
            manager.reconnect(handle)
        }
    }

    override fun releaseAllExcept(devices: List<Device>) {
        isSynchronized = false
        for ((key, handle) in connections) {
            if (!devices.contains(key)) {
                manager.releaseHandle(handle)
            }
        }
        connections.clear()
        status.clear()
    }

    override fun releaseAll() {
        isSynchronized = false
        for ((_, handle) in connections) {
            manager.releaseHandle(handle)
        }
        connections.clear()
        status.clear()
    }

}

