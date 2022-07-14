package com.nabto.edge.nabtoheatpumpdemo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import com.nabto.edge.client.Connection
import com.nabto.edge.client.MdnsResult
import com.nabto.edge.client.NabtoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.inject

class NabtoDeviceScanner(nabtoClient: NabtoClient) {
    private val deviceMap = HashMap<String, Device>()
    val devices: MutableLiveData<List<Device>> = MutableLiveData()

    init {
        nabtoClient.addMdnsResultListener { result ->
            when (result?.action) {
                MdnsResult.Action.ADD,
                MdnsResult.Action.UPDATE -> {
                    deviceMap[result.serviceInstanceName] =
                        Device(result.productId, result.deviceId, "")
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

private val appModule = module {
    single{
    }
}

class NabtoHeatPumpApplication : Application() {
    companion object {
        lateinit var nabtoClient: NabtoClient private set
        lateinit var scanner: NabtoDeviceScanner private set
        lateinit var deviceDatabase: DeviceDatabase private set

        private const val client_private_key_pref_key = "client_private_key"

        fun getClientPrivateKey(context: Context): String {
            val pref: SharedPreferences =
                context.getSharedPreferences(
                    context.getString(R.string.nabto_shared_preferences),
                    Context.MODE_PRIVATE)

            if (pref.contains(client_private_key_pref_key)) {
                return pref.getString(client_private_key_pref_key, null)!!
            }
            else {
                // @TODO: Replace this with an exception of our own that can have more context
                throw RuntimeException("Attempted to access client's private key, but it was not found.")
            }
        }

        fun createConnectionToDevice(context: Context, device: Device): Connection {
            val connection = nabtoClient.createConnection()
            val options = JSONObject()
            options.put("ProductId", device.productId)
            options.put("DeviceId", device.deviceId)
            options.put("ServerKey", context.getString(R.string.nabto_server_key))
            options.put("PrivateKey", getClientPrivateKey(context))
            connection.updateOptions(options.toString())
            return connection
        }
    }

    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        nabtoClient = NabtoClient.create(this)
        scanner = NabtoDeviceScanner(nabtoClient)
        deviceDatabase = Room.databaseBuilder(this, DeviceDatabase::class.java, "device-database").build()

        val pref = this.getSharedPreferences(
            getString(R.string.nabto_shared_preferences),
            Context.MODE_PRIVATE)

        // Store a client private key to be used for connections.
        if (!pref.contains(client_private_key_pref_key)) {
            val pk = nabtoClient.createPrivateKey()
            with (pref.edit()) {
                putString(client_private_key_pref_key, pk)
                apply()
            }
        }

        startKoin {
            androidLogger()
            androidContext(this@NabtoHeatPumpApplication)
            modules(appModule)
        }
    }
}