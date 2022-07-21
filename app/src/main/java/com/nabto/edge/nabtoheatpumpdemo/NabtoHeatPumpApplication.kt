package com.nabto.edge.nabtoheatpumpdemo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import com.nabto.edge.client.Connection
import com.nabto.edge.client.MdnsResult
import com.nabto.edge.client.NabtoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

interface NabtoRepository {
    fun getClientPrivateKey(): String
    fun getServerKey(): String
    fun getScannedDevices(): LiveData<List<MdnsDeviceInfo>>
    fun getApplicationScope(): CoroutineScope
}

interface NabtoConnectionService {
    fun createConnection(): Connection
}

data class MdnsDeviceInfo(
    val productId: String,
    val deviceId: String
)

class NabtoDeviceScanner(nabtoClient: NabtoClient) {
    private val deviceMap = HashMap<String, MdnsDeviceInfo>()
    val devices: MutableLiveData<List<MdnsDeviceInfo>> = MutableLiveData()

    init {
        nabtoClient.addMdnsResultListener { result ->
            when (result?.action) {
                MdnsResult.Action.ADD,
                MdnsResult.Action.UPDATE -> {
                    deviceMap[result.serviceInstanceName] =
                        MdnsDeviceInfo(result.productId, result.deviceId)
                }
                MdnsResult.Action.REMOVE -> {
                    deviceMap.remove(result.serviceInstanceName)
                }
                else -> {}
            }
            devices.postValue(ArrayList(deviceMap.values))
        }
    }
}

private class NabtoRepositoryImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scanner: NabtoDeviceScanner
) : NabtoRepository {
    override fun getClientPrivateKey(): String {
        val pref: SharedPreferences =
            context.getSharedPreferences(
                context.getString(R.string.nabto_shared_preferences),
                Context.MODE_PRIVATE
            )

        val key = context.getString(R.string.nabto_client_private_key_pref)
        if (pref.contains(key)) {
            return pref.getString(key, null)!!
        } else {
            // @TODO: Replace this with an exception of our own that can have more context
            throw RuntimeException("Attempted to access client's private key, but it was not found.")
        }
    }

    override fun getServerKey(): String {
        return context.getString(R.string.nabto_server_key)
    }

    // @TODO: Let application scope be injected instead of having to go through NabtoRepository?
    override fun getApplicationScope(): CoroutineScope {
        return scope
    }

    override fun getScannedDevices(): LiveData<List<MdnsDeviceInfo>> {
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

        single<NabtoRepository> { NabtoRepositoryImpl(androidApplication(), scope, scanner) }

        single<NabtoConnectionService> {
            object : NabtoConnectionService {
                override fun createConnection(): Connection {
                    return client.createConnection()
                }
            }
        }

        viewModel { HomeViewModel(get()) }
        viewModel { HeatPumpViewModel(get(), get(), get()) }
    }

class NabtoHeatPumpApplication : Application() {
    private val nabtoClient: NabtoClient by lazy { NabtoClient.create(this) }
    private val scanner: NabtoDeviceScanner by lazy { NabtoDeviceScanner(nabtoClient) }
    private val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val pref = this.getSharedPreferences(
            getString(R.string.nabto_shared_preferences),
            Context.MODE_PRIVATE
        )

        // Store a client private key to be used for connections.
        val key = getString(R.string.nabto_client_private_key_pref)
        if (!pref.contains(key)) {
            val pk = nabtoClient.createPrivateKey()
            with(pref.edit()) {
                putString(key, pk)
                apply()
            }
        }

        startKoin {
            androidLogger()
            androidContext(this@NabtoHeatPumpApplication)
            modules(appModule(nabtoClient, scanner, applicationScope))
        }
    }
}