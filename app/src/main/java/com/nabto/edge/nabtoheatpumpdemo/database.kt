package com.nabto.edge.nabtoheatpumpdemo

import android.os.Parcelable
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.parcelize.Parcelize

@Entity(tableName = "devices", primaryKeys = ["productId", "deviceId"])
@Parcelize
data class Device(
    val productId: String,
    val deviceId: String,
    val friendlyName: String
) : Parcelable

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun getAll(): Flow<List<Device>>

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


