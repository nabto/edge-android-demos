package com.nabto.edge.sharedcode

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
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
        setupActionBarWithNavController(navController)
        toolbar.setupWithNavController(navController)

        val bottomMenu = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomMenu.setOnItemSelectedListener {
            true
        }
    }
}