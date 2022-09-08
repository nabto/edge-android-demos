package com.nabto.edge.thermostatdemo

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.*
import androidx.room.Room
import com.nabto.edge.client.*
import kotlinx.coroutines.*
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Interface for getting Nabto-relevant data.
 *
 * Upon first running the app a private key is created and stored in the shared preferences of
 * the android phone. This private key can be retrieved with [getClientPrivateKey]
 */
interface NabtoRepository {

    /**
     * Get the private key of the client that was created using NabtoClient.createPrivateKey.
     */
    fun getClientPrivateKey(): String

    /**
     * Deletes the currently stored private key and generates a new one to replace it.
     */
    fun resetClientPrivateKey()

    /**
     * Returns a list of Nabto devices that have been discovered through mDNS
     */
    fun getScannedDevices(): LiveData<List<Device>>

    /**
     * Returns an application-wide CoroutineScope
     */
    fun getApplicationScope(): CoroutineScope

    /**
     * Returns the display name of the user as LiveData.
     */
    fun getDisplayName(): LiveData<String>
    fun setDisplayName(displayName: String)
}

class NabtoDeviceScanner(nabtoClient: NabtoClient) {
    private val deviceMap = HashMap<String, Device>()
    private val _devices: MutableLiveData<List<Device>> = MutableLiveData()
    val devices: LiveData<List<Device>>
        get() = _devices

    init {
        nabtoClient.addMdnsResultListener({ result ->
            when (result?.action) {
                MdnsResult.Action.ADD,
                MdnsResult.Action.UPDATE -> {
                    deviceMap[result.serviceInstanceName] =
                        Device(result.productId, result.deviceId)
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

    override fun getScannedDevices(): LiveData<List<Device>> {
        return scanner.devices
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