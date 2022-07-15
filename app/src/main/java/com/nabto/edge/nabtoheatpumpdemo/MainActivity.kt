package com.nabto.edge.nabtoheatpumpdemo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.viewModels
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
        setSupportActionBar(findViewById(R.id.toolbar))

        mainViewModel.title.observe(this) { title ->
            supportActionBar?.title = title
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
}