package com.nabto.edge.tunnelhttpdemo

import com.nabto.edge.sharedcode.*

class TunnelHTTPApplication : NabtoAndroidApplication() {
    override fun onCreate() {
        super.onCreate()
        initializeNabtoApplication(NabtoConfig)
    }
}