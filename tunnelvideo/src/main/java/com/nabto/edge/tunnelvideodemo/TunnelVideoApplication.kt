package com.nabto.edge.tunnelvideodemo

import android.util.Log
import com.nabto.edge.sharedcode.NabtoAndroidApplication

class TunnelVideoApplication : NabtoAndroidApplication() {
    external fun nativeGetGStreamerInfo(): String

    init {
        System.loadLibrary("gstreamer_android")
        System.loadLibrary("tunnel-video")
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("GStreamer", nativeGetGStreamerInfo())
        initializeNabtoApplication(NabtoConfig)
    }
}