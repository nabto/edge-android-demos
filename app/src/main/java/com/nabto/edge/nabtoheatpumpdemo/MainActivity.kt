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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.android.ext.android.inject
import com.nabto.edge.client.ktx.*
import com.nabto.edge.iamutil.ktx.*
import com.nabto.edge.iamutil.IamUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


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
    private val service: NabtoConnectionService by inject()
    private val repo: NabtoRepository by inject()
    private val database: DeviceDatabase by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val action = intent?.action
        val uri = intent?.data

        Log.i("MainActivity", uri.toString())
        if (action ==  Intent.ACTION_VIEW && uri != null && uri.pathSegments.size == 4) {
            // uri: nabto://androiddemo/pair_password_open/deviceId/productId/password
            if (uri.pathSegments[0] == "pair_password_open") {
                val productId = uri.pathSegments[1]
                val deviceId = uri.pathSegments[2]
                val password = uri.pathSegments[3]

                lifecycleScope.launch {
                    val connection = service.createConnection()
                    val options = JSONObject()
                    options.put("ProductId", productId)
                    options.put("DeviceId", deviceId)
                    options.put("ServerKey", repo.getServerKey())
                    options.put("PrivateKey", repo.getClientPrivateKey())
                    connection.updateOptions(options.toString())

                    try {
                        connection.awaitConnect()
                        val iam = IamUtil.create()
                        connection.passwordAuthenticate("", password)
                        val isPaired = iam.awaitIsCurrentUserPaired(connection)

                        if (!isPaired) {
                            iam.pairPasswordOpen(connection, "someuser", password)
                            val user = iam.getCurrentUser(connection)
                            val details = iam.getDeviceDetails(connection)
                            val updatedDevice = Device(
                                details.productId,
                                details.deviceId,
                                user.sct,
                                details.appName ?: "",
                                ""
                            )
                            withContext(Dispatchers.IO) {
                                val dao = database.deviceDao()
                                // @TODO: Let the user choose a friendly name for the device before inserting
                                dao.insertOrUpdate(updatedDevice)
                            }
                        }
                    } catch (e: Exception) {
                        Log.i("MainActivity", "Failed: $e")
                    }
                }
            }
        }

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