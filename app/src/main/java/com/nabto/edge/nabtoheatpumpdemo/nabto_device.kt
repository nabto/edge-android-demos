package com.nabto.edge.nabtoheatpumpdemo

import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.findFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

@Entity(tableName = "devices", primaryKeys = ["productId", "deviceId"])
@Parcelize
data class Device(
    val productId: String,
    val deviceId: String,
    val SCT: String,
    val appName: String,
    val friendlyName: String
) : Parcelable {
    fun getDeviceNameOrElse(default: String = ""): String {
        return friendlyName.ifEmpty { appName.ifEmpty { default } }
    }
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun getAll(): Flow<List<Device>>

    @Query("SELECT EXISTS(SELECT * FROM devices WHERE productId = :productId AND productId = :deviceId)")
    fun exists(productId: String, deviceId: String): Boolean

    @Update
    fun update(device: Device)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(device: Device)

    @Delete
    fun delete(device: Device)
}

@Database(entities = [Device::class], version = 1)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}

class DeviceListAdapter : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {
    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        lateinit var device: Device
        val title: TextView = view.findViewById(R.id.home_device_item_title)
        val status: TextView = view.findViewById(R.id.home_device_item_subtitle)
    }

    private var dataSet: List<Device> = listOf()

    fun submitDeviceList(devices: List<Device>) {
        dataSet = devices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.home_device_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // @TODO: Inject context to get string here
        holder.title.text = dataSet[position].getDeviceNameOrElse("Unnamed Device")
        holder.status.text = dataSet[position].deviceId
        holder.view.setOnClickListener {
            it.findFragment<HomeFragment>().onDeviceClick(dataSet[position])
        }
    }

    override fun getItemCount() = dataSet.size
}
