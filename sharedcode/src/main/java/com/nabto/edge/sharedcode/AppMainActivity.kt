package com.nabto.edge.sharedcode

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
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
    var actionBarTitle: String
        get() = supportActionBar?.let { it.title.toString() } ?: ""
        set(title)  { supportActionBar?.title = title }

    var actionBarSubtitle: String
        get() = supportActionBar?.let { it.subtitle.toString() } ?: ""
        set(title) { supportActionBar?.subtitle = title }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var navController: NavController

    fun setNavigationListener(owner: LifecycleOwner, callback: () -> Boolean) {
        toolbar.setNavigationOnClickListener {
            val started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!(started && callback())) {
                navController.navigateUp()
            }
        }

        onBackPressedDispatcher.addCallback(owner) {
            if (!callback()) {
                navController.popBackStack()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.app_main_activity)

        // These destinations should have display the bottom bar and have no back button
        val navDestinations = setOf(
            R.id.homeFragment,
            R.id.pairLandingFragment,
            R.id.appSettingsFragment
        )

        toolbar = findViewById(R.id.toolbar)
        this.setSupportActionBar(toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        navController = navHostFragment.navController
        val appBarConfiguration = AppBarConfiguration.Builder(navDestinations).build()
        setupActionBarWithNavController(navController, appBarConfiguration)
        toolbar.setupWithNavController(navController, appBarConfiguration)

        val bottomMenu = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomMenu.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            bottomMenu.visibility = if (navDestinations.contains(destination.id)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }
}