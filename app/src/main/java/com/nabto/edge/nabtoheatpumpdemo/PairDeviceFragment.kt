package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.nabto.edge.client.NabtoRuntimeException
import com.nabto.edge.iamutil.DeviceDetails
import com.nabto.edge.iamutil.IamError
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUtil
import com.nabto.edge.iamutil.ktx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

data class PairingData(
    val productId: String,
    val deviceId: String,
    val password: String
) {
    companion object {
        fun unwrapBundle(data: Bundle?): PairingData {
            return PairingData(
                productId = data?.getString("productId") ?: "",
                deviceId = data?.getString("deviceId") ?: "",
                password = data?.getString("password") ?: ""
            )
        }

        fun makeBundle(productId: String, deviceId: String, password: String): Bundle {
            return bundleOf(
                "productId" to productId,
                "deviceId" to deviceId,
                "password" to password
            )
        }
    }
}

private class PairDeviceViewModelFactory(
    private val manager: NabtoConnectionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(NabtoConnectionManager::class.java).newInstance(manager)
    }
}

private sealed class PairingResult {
    data class Success(val alreadyPaired: Boolean, val dev: Device) : PairingResult()
    object FailedNotHeatPump : PairingResult()
    object FailedUsernameExists : PairingResult()
    object Failed : PairingResult()
}

private class PairDeviceViewModel(private val manager: NabtoConnectionManager) : ViewModel() {
    private val TAG = "PairDeviceViewModel"
    var pairingData = PairingData("", "", "")
    private var password = ""
    private lateinit var handle: ConnectionHandle
    private val iam by lazy {
        IamUtil.create()
    }

    private val _pairingResult = MutableLiveData<PairingResult>()
    val pairingResult: LiveData<PairingResult>
        get() = _pairingResult

    private suspend fun passwordAuthenticate(pw: String) {
        password = pw
        withContext(Dispatchers.IO) {
            manager.getConnection(handle).passwordAuthenticate("", password)
        }
    }

    private suspend fun isCurrentUserPaired(): Boolean {
        return iam.awaitIsCurrentUserPaired(manager.getConnection(handle))
    }

    private suspend fun getPairingDetails(): DeviceDetails {
        val connection = manager.getConnection(handle)
        return iam.awaitGetDeviceDetails(connection)
    }

    private suspend fun getDeviceDetails(friendlyDeviceName: String): Device {
        val connection = manager.getConnection(handle)
        val details = iam.awaitGetDeviceDetails(connection)
        val user = iam.awaitGetCurrentUser(connection)
        return Device(
            details.productId,
            details.deviceId,
            user.sct,
            details.appName ?: "",
            friendlyDeviceName
        )
    }

    private suspend fun pairLocalOpen(desiredUsername: String, friendlyDeviceName: String): Device {
        iam.awaitPairLocalOpen(manager.getConnection(handle), desiredUsername)
        return getDeviceDetails(friendlyDeviceName)
    }

    suspend fun pairLocalPassword(desiredUsername: String, friendlyDeviceName: String): Device {
        iam.awaitPairPasswordOpen(manager.getConnection(handle), desiredUsername, password)
        return getDeviceDetails(friendlyDeviceName)
    }

    private fun pairAndUpdateDevice(username: String, friendlyName: String) {
        viewModelScope.launch {
            try {
                val pairingDetails = getPairingDetails()
                if (pairingDetails.appName != "HeatPump") {
                    _pairingResult.postValue(PairingResult.FailedNotHeatPump)
                    return@launch
                }

                if (isCurrentUserPaired()) {
                    val dev = getDeviceDetails(friendlyName)
                    _pairingResult.postValue(PairingResult.Success(true, dev))
                    return@launch
                }

                val dev = if (pairingData.password != "") {
                    passwordAuthenticate(pairingData.password)
                    pairLocalPassword(username, friendlyName)
                } else {
                    pairLocalOpen(username, friendlyName)
                }

                _pairingResult.postValue(PairingResult.Success(false, dev))
            } catch (e: IamException) {
                // You could carry some extra information in PairingResult.Failed using the info in the exception
                // to give a better update to the end user
                if (e.error == IamError.USERNAME_EXISTS) {
                    _pairingResult.postValue(PairingResult.FailedUsernameExists)
                } else {
                    _pairingResult.postValue(PairingResult.Failed)
                }
                Log.i(TAG, "Attempted pairing failed with $e")
            } catch (e: NabtoRuntimeException) {
                _pairingResult.postValue(PairingResult.Failed)
                Log.i(TAG, "Attempted pairing failed with $e")
            } catch (e: CancellationException) {
                manager.releaseHandle(handle)
                _pairingResult.postValue(PairingResult.Failed)
            }
        }
    }

    fun initiatePairing(username: String, friendlyName: String) {
        viewModelScope.launch {
            val device = Device(
                productId = pairingData.productId,
                deviceId = pairingData.deviceId,
                "", "", ""
            )

            handle = manager.requestConnection(device) { event, _ ->
                when (event) {
                    NabtoConnectionEvent.CONNECTED -> {
                        viewModelScope.launch { pairAndUpdateDevice(username, friendlyName) }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (this::handle.isInitialized) {
            manager.releaseHandle(handle)
        }
    }
}

class PairDeviceFragment : Fragment() {
    private val TAG = "PairDeviceFragment"
    private val database: DeviceDatabase by inject()
    private val manager: NabtoConnectionManager by inject()
    private val model: PairDeviceViewModel by viewModels {
        PairDeviceViewModelFactory(manager)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_pair_device, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.pairingData = PairingData.unwrapBundle(arguments)

        model.pairingResult.observe(viewLifecycleOwner, Observer { result ->
            val stringIdentifier = when (result) {
                is PairingResult.Success -> {
                    // Success, update the local database of devices
                    lifecycleScope.launch(Dispatchers.IO) {
                        val dao = database.deviceDao()
                        dao.insertOrUpdate(result.dev)
                    }
                    if (result.alreadyPaired) {
                        R.string.pair_device_already_paired
                    } else {
                        R.string.pair_device_success
                    }
                }

                is PairingResult.FailedNotHeatPump -> R.string.pair_device_failed_not_heatpump
                is PairingResult.FailedUsernameExists -> R.string.pair_device_failed_username_exists
                is PairingResult.Failed -> R.string.pair_device_failed
            }

            Snackbar.make(
                view,
                getString(stringIdentifier),
                Snackbar.LENGTH_LONG
            ).show()

            // @TODO: Only send the user back if they successfully paired.
            findNavController().navigate(R.id.action_nav_return_home)
        })

        val etUsername = view.findViewById<EditText>(R.id.pair_device_username)
        val etFriendlyName = view.findViewById<EditText>(R.id.pair_device_friendlyname)
        view.findViewById<Button>(R.id.complete_pairing).setOnClickListener { button ->
            button.isEnabled = false
            model.initiatePairing(etUsername.text.toString(), etFriendlyName.text.toString())
        }
    }
}