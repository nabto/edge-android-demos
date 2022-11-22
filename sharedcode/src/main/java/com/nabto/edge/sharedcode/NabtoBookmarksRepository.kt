package com.nabto.edge.sharedcode

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.asFlow
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.awaitIsCurrentUserPaired
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

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

    private val connections = ConcurrentHashMap<Device, ConnectionHandle>()
    private val status = ConcurrentHashMap<Device, BookmarkStatus>()
    private val jobs = mutableMapOf<Device, Job>()

    private var devices: List<Device> = listOf()
    private var isSynchronized = false

    init {
        scope.launch {
            database.deviceDao().getAll().collect {
                releaseAllExcept(it)
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

    private suspend fun getConnectedStatus(handle: ConnectionHandle): BookmarkStatus {
        val iam = IamUtil.create()
        return try {
            val paired = iam.awaitIsCurrentUserPaired(manager.getConnection(handle))
            if (paired) {
                BookmarkStatus.ONLINE
            } else {
                BookmarkStatus.UNPAIRED
            }
        } catch (e: IamException) {
            Log.i(TAG, "getConnectedStatus caught exception $e")
            BookmarkStatus.OFFLINE
        }
    }

    private fun startDeviceConnections() {
        for (key in devices) {
            connections[key] = manager.requestConnection(key)
            connections[key]?.let { handle ->
                val job = scope.launch {
                    manager.getConnectionState(handle)?.asFlow()?.collect {
                        status[key] = when (it) {
                            NabtoConnectionState.CLOSED -> BookmarkStatus.OFFLINE
                            NabtoConnectionState.CONNECTING -> BookmarkStatus.CONNECTING
                            NabtoConnectionState.CONNECTED -> getConnectedStatus(handle)
                        }
                        postDevices()
                    }
                }
                jobs[key] = job
            }
            connections[key]?.let { manager.connect(it) }
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
            manager.connect(handle)
        }
    }

    override fun releaseAllExcept(devices: List<Device>) {
        for ((key, handle) in connections) {
            if (!devices.contains(key)) {
                manager.releaseHandle(handle)
                connections.remove(key)
                status.remove(key)
                jobs.remove(key)?.cancel()
            }
        }
    }

    override fun releaseAll() {
        connections.forEach {(_, h) -> manager.releaseHandle(h)}
        jobs.forEach {(_, v) -> v.cancel()}
        connections.clear()
        status.clear()
    }

}

