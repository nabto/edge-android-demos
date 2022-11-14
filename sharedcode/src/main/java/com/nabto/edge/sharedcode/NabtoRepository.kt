package com.nabto.edge.sharedcode

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.nabto.edge.client.NabtoClient
import kotlinx.coroutines.CoroutineScope

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
     * Returns an application-wide CoroutineScope.
     */
    fun getApplicationScope(): CoroutineScope

    /**
     * Returns the display name of the user as LiveData.
     */
    fun getDisplayName(): LiveData<String>

    /**
     * Set the display name of the user.
     */
    fun setDisplayName(displayName: String)
}

class NabtoRepositoryImpl(
    private val context: Context,
    private val nabtoClient: NabtoClient,
    private val scope: CoroutineScope,
    private val scanner: NabtoDeviceScanner
) : NabtoRepository {
    private val _displayName = MutableLiveData<String>()
    private val pref = context.getSharedPreferences(
        internalConfig.SHARED_PREFERENCES,
        Context.MODE_PRIVATE
    )

    init {
        run {
            // Store a client private key to be used for connections.
            val key = internalConfig.PRIVATE_KEY_PREF
            if (!pref.contains(key)) {
                val pk = nabtoClient.createPrivateKey()
                with(pref.edit()) {
                    putString(key, pk)
                    apply()
                }
            }
        }


        run {
            val key = internalConfig.DISPLAY_NAME_PREF
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
        val key = internalConfig.PRIVATE_KEY_PREF
        if (pref.contains(key)) {
            return pref.getString(key, null)!!
        } else {
            // @TODO: Replace this with an exception of our own that can have more context
            throw RuntimeException("Attempted to access client's private key, but it was not found.")
        }
    }

    override fun resetClientPrivateKey() {
        val key = internalConfig.PRIVATE_KEY_PREF
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
        val key = internalConfig.DISPLAY_NAME_PREF
        with(pref.edit()) {
            putString(key, displayName)
            apply()
        }
    }

    override fun getScannedDevices(): LiveData<List<Device>> {
        return scanner.devices
    }
}
