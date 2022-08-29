package com.nabto.edge.nabtodemo

import android.os.Parcelable
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.parcelize.Parcelize

/**
 * Data class that holds information about a device.
 * DeviceDatabase holds a table of Devices represented by this class.
 *
 * @property[productId]] product ID that the device belongs to
 * @property[deviceId] ID of the device
 * @property[SCT] Server Connect Token
 * @property[appName] the app name from GET /iam/pairing
 * @property[friendlyName] a friendly name that the user can give to the device.
 */
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

/**
 * Device Data Access Object
 * A DeviceDao can be retrieved from a DeviceDatabase and
 * can be used to interact with the database.
 */
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

    @Query("DELETE FROM devices")
    fun deleteAll()
}

/**
 * DeviceDatabase is a Room database class. The app stores remembered devices in this database.
 * You can use Koin to inject a DeviceDatabase into e.g. a Fragment by doing
 *
 * val database: DeviceDatabase by inject()
 */
@Database(entities = [Device::class], version = 1)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}
