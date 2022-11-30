package com.nabto.edge.thermostatdemo

import com.nabto.edge.sharedcode.NabtoAndroidApplication

class ThermostatApplication : NabtoAndroidApplication() {
    override fun onCreate() {
        super.onCreate()
        initializeNabtoApplication(NabtoConfig)
    }
}