package com.nabto.edge.nabtodemo

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.navigation.fragment.findNavController

class PairLandingFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_pair_landing, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val discoverButton = view.findViewById<Button>(R.id.pair_landing_discover_button)
        discoverButton.setOnClickListener {
            findNavController().navigate(R.id.action_pairLandingFragment_to_pairNewFragment)
        }

        val pattern = "pr=(.+),de=(.+)".toRegex()
        val etPairingString = view.findViewById<EditText>(R.id.pair_landing_et_string)
        val pairButton = view.findViewById<Button>(R.id.pair_landing_pair_button)
        pairButton.setOnClickListener {
            // remove all whitespace and check that the pairing string matches the pattern
            val pairingString = etPairingString.text.filter { !it.isWhitespace() }
            if (pattern.matches(pairingString)) {
                val match = pattern.matchEntire(pairingString)
                if (match != null) {
                    val productId = match.groups[1]?.value ?: ""
                    val deviceId = match.groups[2]?.value ?: ""
                    val bundle = PairingData.makeBundle(productId, deviceId, "")
                    findNavController().navigate(R.id.action_nav_pairDeviceFragment, bundle)
                }

            } else {
                etPairingString.error = "Pairing string does not match pattern."
            }
            clearFocusAndHideKeyboard()
        }
    }
}