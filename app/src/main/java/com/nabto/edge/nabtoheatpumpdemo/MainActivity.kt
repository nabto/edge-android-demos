package com.nabto.edge.nabtoheatpumpdemo

import android.graphics.Color
import android.os.Bundle
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


class MainViewModel : ViewModel() {
    private  val _title = MutableLiveData<String>("")
    val title: LiveData<String>
    get() = _title

    fun setTitle(newTitle: String) {
        _title.postValue(newTitle)
    }
}

class MainActivity : AppCompatActivity() {
    val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainViewModel.title.observe(this) { title ->
            supportActionBar?.title = title
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        val drawer = findViewById<DrawerLayout>(R.id.main_drawer_layout)
        val content = findViewById<LinearLayout>(R.id.main_layout)
        drawer.setScrimColor(Color.TRANSPARENT)

        val toggle = object : ActionBarDrawerToggle(this, drawer, toolbar, R.string.title_home, R.string.title_home) {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                super.onDrawerSlide(drawerView, slideOffset)
                content.translationX = drawerView.width * slideOffset
            }

            override fun onDrawerOpened(drawerView: View) {
                super.onDrawerOpened(drawerView)
                invalidateOptionsMenu()
            }
        }

        drawer.addDrawerListener(toggle)
        toggle.isDrawerIndicatorEnabled = true
        toggle.syncState()
    }
}