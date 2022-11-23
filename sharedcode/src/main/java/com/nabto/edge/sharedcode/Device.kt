package com.nabto.edge.sharedcode

import android.os.Parcelable
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.parcelize.Parcelize

/**
 * Data class that holds information about a device. Everything except [productId] and [deviceId]
 * may be empty strings.
 */
@Parcelize
data class Device(
    val productId: String,
    val deviceId: String,
    val fingerprint: String = "",
    val SCT: String = "",
    val appName: String = "",
    val friendlyName: String = "",
    val password: String = ""
): Parcelable {
    override fun equals(other: Any?): Boolean {
        return (other is Device) &&
                other.productId == this.productId &&
                other.deviceId == this.deviceId
    }

    override fun hashCode(): Int {
        var result = productId.hashCode()
        result = 31 * result + deviceId.hashCode()
        return result
    }

    fun getDeviceNameOrElse(default: String = ""): String {
        return friendlyName.ifEmpty { appName.ifEmpty { default } }
    }
}

/**
 * [DeviceDatabase] holds a table of devices represented by this class.
 *
 * @property[productId]] product ID that the device belongs to
 * @property[deviceId] ID of the device
 * @property[SCT] Server Connect Token
 * @property[appName] the app name from GET /iam/pairing
 * @property[friendlyName] a friendly name that the user can give to the device.
 */
@Entity(tableName = "devices", primaryKeys = ["productId", "deviceId"])
data class DatabaseDevice(
    val productId: String,
    val deviceId: String,
    val fingerprint: String,
    val SCT: String,
    val appName: String,
    val friendlyName: String
)

data class DatabasePrimaryKey(
    val productId: String,
    val deviceId: String
)

fun convertEntryToDevice(entry: DatabaseDevice): Device {
    return Device(
        productId = entry.productId,
        deviceId = entry.deviceId,
        fingerprint = entry.fingerprint,
        SCT = entry.SCT,
        appName =  entry.appName,
        friendlyName = entry.friendlyName,
        password = ""
    )
}

fun convertDeviceToEntry(dev: Device): DatabaseDevice {
    return DatabaseDevice(
        productId = dev.productId,
        deviceId = dev.deviceId,
        fingerprint = dev.fingerprint,
        SCT = dev.SCT,
        appName = dev.appName,
        friendlyName = dev.friendlyName
    )
}

/**
 * Device Data Access Object
 * A DeviceDao can be retrieved from a [DeviceDatabase] and
 * can be used to interact with the database.
 */
@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun _getAll(): Flow<List<DatabaseDevice>>

    @Query("SELECT * FROM devices WHERE productId = :productId AND deviceId = :deviceId")
    fun _get(productId: String, deviceId: String): DatabaseDevice

    @Query("SELECT EXISTS(SELECT * FROM devices WHERE productId = :productId AND deviceId = :deviceId)")
    fun exists(productId: String, deviceId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun _insertOrUpdate(device: DatabaseDevice)

    @Delete(entity = DatabaseDevice::class)
    fun _delete(key: DatabasePrimaryKey)

    @Query("DELETE FROM devices")
    fun deleteAll()
}

fun DeviceDao.get(productId: String, deviceId: String): Device {
    return convertEntryToDevice(_get(productId, deviceId))
}

fun DeviceDao.getAll(): Flow<List<Device>> {
    val flow = _getAll()
    return flow.map {
        it.map {
            convertEntryToDevice(it)
        }
    }
}

fun DeviceDao.insertOrUpdate(device: Device) {
    _insertOrUpdate(convertDeviceToEntry(device))
}

fun DeviceDao.delete(device: Device) {
    _delete(
        DatabasePrimaryKey(
            productId = device.productId,
            deviceId = device.deviceId
        )
    )
}

fun DeviceDao.delete(productId: String, deviceId: String) {
    _delete(DatabasePrimaryKey(productId, deviceId))
}

/**
 * Subclass of [RoomDatabase]. The app stores remembered devices in this database.
 * You can use Koin to inject a DeviceDatabase into e.g. a Fragment by doing
 *
 * val database: DeviceDatabase by inject()
 */
@Database(
    entities = [DatabaseDevice::class], version = 2,
    autoMigrations = [
        AutoMigration (from = 1, to = 2)
    ]
)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}
