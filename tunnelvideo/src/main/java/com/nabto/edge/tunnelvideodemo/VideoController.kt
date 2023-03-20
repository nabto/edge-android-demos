package com.nabto.edge.tunnelvideodemo

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil
import androidx.annotation.RequiresApi
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

    // gstContextPointer holds an address for the GStreamer context that is used in C code
    private val gstContextPointer: Long = 0

    // Extern functions to call GStreamer functionality.
    private external fun gstInit()
    private external fun gstFinalize()
    private external fun gstPlay()
    private external fun gstPause()
    private external fun gstSetMediaUri(uri: String)
    private external fun gstSurfaceInit(surface: Surface)
    private external fun gstSurfaceFinalize()
    private external fun gstClassInit(): Boolean

    private var isPlayingDesired: Boolean = true

    companion object {
        init {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("tunnel-video")
        }
    }

    init {
        gstClassInit()

        try {
            GStreamer.init(context)
        } catch (e: Exception) {
            Log.e(TAG, "GStreamer init failed: ${e.message}")
        }

        lifecycleOwner.lifecycle.addObserver(this)
        surfaceView.holder.addCallback(this)
        gstInit()
    }

    // Called from native code once pipeline is created and main loop is running.
    private fun onGstInitialized() {
        Log.i(TAG, "GStreamer initialized.")
        if (isPlayingDesired) {
            gstPlay()
        } else {
            gstPause()
        }
    }

    // @TODO: For now just informs about mime type.
    //        If in the future we want more stream info, make a StreamInfo class or similar
    //        and pass it instead of adding arguments.
    private fun onGstStreamInfo(mimeType: String) {
    }

    override fun onDestroy(owner: LifecycleOwner) {
        gstFinalize()
        super.onDestroy(owner)
    }

    fun setMediaUri(uri: Uri) {
        gstSetMediaUri(uri.toString())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        gstSurfaceInit(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        gstSurfaceFinalize()
    }
}