package com.nabto.edge.tunnelvideodemo

import com.nabto.edge.sharedcode.NabtoAndroidApplication

class TunnelVideoApplication : NabtoAndroidApplication() {
    override fun onCreate() {
        super.onCreate()
        initializeNabtoApplication(NabtoConfig)
    }
}