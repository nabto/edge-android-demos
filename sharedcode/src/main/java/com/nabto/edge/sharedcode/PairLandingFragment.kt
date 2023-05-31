package com.nabto.edge.sharedcode

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.navigation.fragment.findNavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Fragment for fragment_pair_landing.xml
 * It displays an option to either go to the list of mDNS discovered devices ([PairNewFragment])
 * or let the user input a pairing string.
 */
class PairLandingFragment : Fragment() {
    val etPairingString: EditText by lazy { requireView().findViewById(R.id.pair_landing_et_string) }

    val scanner = registerForActivityResult(ScanContract()) {
        if (it.contents == null) {
            // @TODO: Error
        } else {
            etPairingString.setText(it.contents)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_pair_landing, container, false)
    }

    private fun parsePairingString(pairingString: String): Device {
        val map = mutableMapOf<String, String>()
        val withoutWhitespace = pairingString.filter { !it.isWhitespace() }
        val pairs = withoutWhitespace.split(",")
        for (pair in pairs) {
            val parts = pair.split("=")
            if (parts.size == 2) {
                val key = parts[0]
                val value = parts[1]
                map[key] = value
            }
        }

        return Device(
            productId = map.getOrDefault("p", ""),
            deviceId = map.getOrDefault("d", ""),
            password = map.getOrDefault("pwd", ""),
            SCT = map.getOrDefault("sct", "")
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val discoverButton = view.findViewById<Button>(R.id.pair_landing_discover_button)
        discoverButton.setOnClickListener {
            findNavController().navigate(R.id.action_pairLandingFragment_to_pairNewFragment)
        }

        val pairButton = view.findViewById<Button>(R.id.pair_landing_pair_button)
        pairButton.setOnClickListener {
            val device = parsePairingString(etPairingString.text.toString())
            if (device.productId.isNotEmpty() && device.deviceId.isNotEmpty()) {
                findNavController().navigate(AppRoute.pairDevice(device.productId, device.deviceId, device.password, device.SCT))
            } else {
                etPairingString.error = "Pairing string does not match pattern."
            }
            clearFocusAndHideKeyboard()
        }

        val scanButton = view.findViewById<Button>(R.id.pair_landing_qr_button)
        scanButton.setOnClickListener {
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan a QR code for a device.")
                scanner.launch(this)
            }
        }
    }
}