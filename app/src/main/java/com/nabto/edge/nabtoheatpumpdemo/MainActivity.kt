package com.nabto.edge.nabtoheatpumpdemo

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.*
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.android.ext.android.inject
import com.nabto.edge.client.ktx.*
import com.nabto.edge.iamutil.ktx.*
import com.nabto.edge.iamutil.IamUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// @TODO:
//   * Arrow instead of home button on device page
//   * Just remove the side menu?
//   * Update UI from device state when the user is not interacting
//     * Only allow the UI to be updated programmatically if the user is not interacting
//   * What should be in the device settings menu?
//     * User management?
//     * Current user's profile management?
//     * Friendly device name?

class DeviceConnectionService : Service() {
    inner class DeviceConnectionBinder : Binder() {
        fun getService(): DeviceConnectionService = this@DeviceConnectionService
    }

    private val binder = DeviceConnectionBinder()

    override fun onCreate() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_STOP -> Log.i("Service", "STOPPED")
                    Lifecycle.Event.ON_RESUME -> Log.i("Service", "RESUMED")
                    else -> {}
                }
            }
        })
    }

    override fun onBind(p0: Intent?): IBinder? {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i("Service", "I am being removed")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

class MainActivity : AppCompatActivity() {
    private var service: DeviceConnectionService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            this@MainActivity.service = (service as DeviceConnectionService.DeviceConnectionBinder?)?.getService()
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            this@MainActivity.service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startService(Intent(baseContext, DeviceConnectionService::class.java))

        val ret = bindService(
            Intent(this, DeviceConnectionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        val drawer = findViewById<DrawerLayout>(R.id.main_drawer_layout)
        val content = findViewById<LinearLayout>(R.id.main_layout)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        val navController = navHostFragment.navController
        setupActionBarWithNavController(navController)
        toolbar.setupWithNavController(navController, drawer)

        drawer.setScrimColor(Color.TRANSPARENT)
        drawer.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                content.translationX = drawerView.width * slideOffset
            }

            override fun onDrawerOpened(drawerView: View) {}
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })
    }
}