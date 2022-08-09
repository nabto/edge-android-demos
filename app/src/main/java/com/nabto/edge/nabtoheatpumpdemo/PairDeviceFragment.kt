package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
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
            //return bundleOf("pairing_data" to PairingData(productId, deviceId, password))
            return bundleOf(
                "productId" to productId,
                "deviceId" to deviceId,
                "password" to password
            )
        }
    }
}

private class PairDeviceViewModelFactory(
    private val repo: NabtoRepository,
    private val manager: NabtoConnectionManager
): ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(NabtoRepository::class.java, NabtoConnectionManager::class.java).newInstance(repo, manager)
    }
}

private data class PairingResult(
    val wasAlreadyPaired: Boolean,
    val device: Device = Device("", "", "", "", "")
)

private class PairDeviceViewModel(
    private val repo: NabtoRepository,
    private val manager: NabtoConnectionManager
) : ViewModel() {
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
                if (isCurrentUserPaired()) {
                    val dev = getDeviceDetails(friendlyName)
                    _pairingResult.postValue(PairingResult(true, dev))
                    return@launch
                }

                val dev = if (pairingData.password != "") {
                    passwordAuthenticate(pairingData.password)
                    pairLocalPassword(username, friendlyName)
                } else {
                    pairLocalOpen(username, friendlyName)
                }

                _pairingResult.postValue(PairingResult(false, dev))
            } catch (e: CancellationException) {
                manager.releaseHandle(handle)
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

            handle = manager.requestConnection(device) { when (it) {
                NabtoConnectionEvent.CONNECTED -> {
                    viewModelScope.launch { pairAndUpdateDevice(username, friendlyName) }
                }
                else -> {}
            }}
        }
    }

    override fun onCleared() {
        super.onCleared()
        manager.releaseHandle(handle)
    }
}

class PairDeviceFragment : Fragment() {
    private val TAG = "PairDeviceFragment"
    private val database: DeviceDatabase by inject()
    private val manager: NabtoConnectionManager by inject()
    private val repo: NabtoRepository by inject()
    private val model: PairDeviceViewModel by viewModels {
        PairDeviceViewModelFactory(repo, manager)
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
        Log.i(TAG, model.pairingData.toString())

        model.pairingResult.observe(viewLifecycleOwner, Observer { result ->
            lifecycleScope.launch(Dispatchers.IO) {
                val dao = database.deviceDao()
                dao.insertOrUpdate(result.device)
            }
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