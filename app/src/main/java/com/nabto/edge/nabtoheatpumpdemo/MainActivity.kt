package com.nabto.edge.nabtoheatpumpdemo

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
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

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        val drawer = findViewById<DrawerLayout>(R.id.main_drawer_layout)
        val content = findViewById<LinearLayout>(R.id.main_layout)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        val navController = navHostFragment.navController
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