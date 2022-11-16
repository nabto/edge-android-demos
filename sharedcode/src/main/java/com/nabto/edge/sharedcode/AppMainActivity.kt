package com.nabto.edge.sharedcode

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Single Activity that swaps between Fragments.
 */
class AppMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.app_main_activity)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        this.setSupportActionBar(toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        val navController = navHostFragment.navController
        val appBarConfiguration = AppBarConfiguration.Builder(setOf(
            R.id.app_nav,
            R.id.nav_home,
            R.id.nav_settings,
            R.id.nav_pairing
        )).build()
        setupActionBarWithNavController(navController, appBarConfiguration)
        toolbar.setupWithNavController(navController, appBarConfiguration)

        val bottomMenu = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomMenu.setupWithNavController(navController)
    }
}