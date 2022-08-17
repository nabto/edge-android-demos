package com.nabto.edge.nabtoheatpumpdemo

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.navigation.navGraphViewModels
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.koin.android.ext.android.inject

class DeviceSettingsFragment : Fragment() {
    private val device by lazy { requireArguments().get("device") as Device }
    private val model: HeatPumpViewModel by navGraphViewModels(R.id.device_graph) {
        val connectionManager: NabtoConnectionManager by inject()
        HeatPumpViewModelFactory(
            device,
            connectionManager
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_device_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }
}