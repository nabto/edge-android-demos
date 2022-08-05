package com.nabto.edge.nabtoheatpumpdemo

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.navigation.navDeepLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
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
    private val service: NabtoConnectionService
): ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(NabtoRepository::class.java, NabtoConnectionService::class.java).newInstance(repo, service)
    }
}

private data class PairingResult(
    val wasAlreadyPaired: Boolean,
    val device: Device = Device("", "", "", "", "")
)

private class PairDeviceViewModel(
    private val repo: NabtoRepository,
    private val service: NabtoConnectionService
) : ViewModel() {
    private val TAG = "PairDeviceViewModel"
    var pairingData = PairingData("", "", "")

    private val _pairingResult = MutableLiveData<PairingResult>()
    val pairingResult: LiveData<PairingResult>
        get() = _pairingResult


    private suspend fun pairAndUpdateDevice(connection: UnpairedDeviceConnection, username: String, friendlyName: String) {
        try {
            if (connection.isCurrentUserPaired()) {
                val dev = connection.getDeviceDetails(friendlyName)
                _pairingResult.postValue(PairingResult(true, dev))
                return
            }

            val dev = if (pairingData.password != "") {
                connection.passwordAuthenticate(pairingData.password)
                connection.pairLocalPassword(username, friendlyName)
            } else {
                connection.pairLocalOpen(username, friendlyName)
            }

            _pairingResult.postValue(PairingResult(false, dev))
        } catch(e: CancellationException) {
            connection.close()
        }
    }

    fun initiatePairing(username: String, friendlyName: String) {
        viewModelScope.launch {
            val device = Device(
                productId = pairingData.productId,
                deviceId = pairingData.deviceId,
                "", "", ""
            )
            Log.i(TAG, device.toString())
            val connection = UnpairedDeviceConnection(repo, device, service)

            connection.subscribe { when (it) {
                DeviceConnectionEvent.CONNECTED -> {
                    viewModelScope.launch { pairAndUpdateDevice(connection, username, friendlyName) }
                }
                else -> {}
            }}

            try {
                connection.connect()
            } catch(e: CancellationException) {
                // We've been cancelled for some reason, just stop the connection.
                connection.close()
            }
        }
    }
}

class PairDeviceFragment : Fragment() {
    private val TAG = "PairDeviceFragment"
    private val database: DeviceDatabase by inject()
    private val service: NabtoConnectionService by inject()
    private val repo: NabtoRepository by inject()
    private val model: PairDeviceViewModel by viewModels {
        PairDeviceViewModelFactory(repo, service)
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