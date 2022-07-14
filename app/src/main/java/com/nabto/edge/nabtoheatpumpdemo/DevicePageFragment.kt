@file:OptIn(ExperimentalSerializationApi::class)

package com.nabto.edge.nabtoheatpumpdemo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import kotlinx.serialization.*

/**
 * DevicePageFragment.kt
 * @TODO:
 *   * This file holds a lot more than just DevicePageFragment class
 *     rename and/or move things around?
 *   * Program _will_ crash if the device is unavailable
 *     This is because we try to access the device all the time
 *     without checking for exceptions. To be fixed later.
 *   * Thorough documentation is required
 */

class DevicePageFragment : Fragment() {
    private var hasLoaded = false
    private lateinit var device: Device
    private lateinit var factory: HeatPumpViewModelFactory

    private val temperatureView: TextView by lazy {
        requireView().findViewById(R.id.dp_temperature)
    }

    private val modeSpinnerView: Spinner by lazy {
        requireView().findViewById(R.id.dp_mode_spinner)
    }

    private val powerSwitchView: Switch by lazy {
        requireView().findViewById(R.id.dp_power_switch)
    }

    private val targetSliderView: Slider by lazy {
        requireView().findViewById(R.id.dp_target_slider)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = requireArguments().get("device") as Device

        val connectionData = HeatPumpConnectionData(
            NabtoHeatPumpApplication.getClientPrivateKey(requireContext()),
            getString(R.string.nabto_server_key)
        )
        val scope = (requireActivity().application as NabtoHeatPumpApplication).applicationScope
        factory = HeatPumpViewModelFactory(connectionData, device, scope)
        ViewModelProvider(this, factory)[HeatPumpViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_device_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.dp_info_name).text =
            device.friendlyName.ifEmpty { getString(R.string.unnamed_device) }
        view.findViewById<TextView>(R.id.dp_info_devid).text = device.deviceId
        view.findViewById<TextView>(R.id.dp_info_proid).text = device.productId

        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.modes_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modeSpinnerView.adapter = adapter
        }

        val model = ViewModelProvider(viewModelStore, factory)[HeatPumpViewModel::class.java]
        model.getHeatPumpState()
            .observe(viewLifecycleOwner, Observer { state -> updateViewFromState(view, state) })

        powerSwitchView.setOnClickListener {
            model.setPower(powerSwitchView.isChecked)
        }

        targetSliderView.addOnChangeListener { _, value, _ ->
            model.setTarget(value.toDouble())
        }

        modeSpinnerView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val mode = HeatPumpMode.values()[pos]
                model.setMode(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
    }

    private fun updateViewFromState(view: View, state: HeatPumpState) {
        if (!state.valid) {
            val snackbar = Snackbar.make(
                view,
                "Lost connection to device",
                Snackbar.LENGTH_LONG
            )
            snackbar.show()
            findNavController().popBackStack()
            return
        }

        if (!hasLoaded) {
            hasLoaded = true
            powerSwitchView.isChecked = state.power
            targetSliderView.value = state.target.toFloat()
            modeSpinnerView.setSelection(state.mode.ordinal)

            // Destroy the loading spinner and show device page to user
            view.findViewById<View>(R.id.dp_loading).visibility = View.GONE
            view.findViewById<View>(R.id.dp_main).visibility = View.VISIBLE
        }

        temperatureView.text = getString(R.string.temperature_format).format(state.temperature)
    }
}