package com.nabto.edge.tunnelvideodemo

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.freedesktop.gstreamer.GStreamer

class VideoController(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    surfaceView: SurfaceView
) : SurfaceHolder.Callback, DefaultLifecycleObserver
{
    private val TAG = this::class.java.simpleName

    private external fun nativeInit()
    private external fun nativeFinalize()
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeSetUri(uri: String)
    // @TODO: Avoid using Any here.
    private external fun nativeSurfaceInit(surface: Surface)
    private external fun nativeSurfaceFinalize()
    private external fun nativeClassInit(): Boolean

    private var native_custom_data: Long = 0
    private var is_playing_desired: Boolean = true

    companion object {
        init {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("tunnel-video")
        }
    }

    init {
        nativeClassInit()

        try {
            GStreamer.init(context)
        } catch (e: Exception) {
            Log.e(TAG, "GStreamer init failed: ${e.message}")
        }

        lifecycleOwner.lifecycle.addObserver(this)
        surfaceView.holder.addCallback(this)
        nativeInit()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        nativeFinalize()
        super.onDestroy(owner)
    }

    // Called from native code once pipeline is created and main loop is running.
    // @TODO: Find a better way to handle calling init functions from C code.
    private fun onGStreamerInitialized() {
        Log.i(TAG, "Gstreamer initialized.")
        if (is_playing_desired) {
            nativePlay()
        } else {
            nativePause()
        }
    }

    fun setMediaUri(uri: Uri) {
        nativeSetUri(uri.toString())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        nativeSurfaceInit(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        nativeSurfaceFinalize()
    }
}