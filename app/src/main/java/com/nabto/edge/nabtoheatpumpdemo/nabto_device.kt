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
