package com.nabto.edge.sharedcode

import kotlin.reflect.KProperty

open class NabtoConfiguration(
    /** App name that is returned from GET /iam/pairing (https://docs.nabto.com/developer/api-reference/coap/iam/pairing.html) */
    val DEVICE_APP_NAME: String,

    /** MDNS sub type that NabtoDeviceScanner will use to search for devices on the local network */
    val MDNS_SUB_TYPE: String,

    // Shared preferences keys
    val PRIVATE_KEY_PREF: String,
    val DISPLAY_NAME_PREF: String,

    /** Nabto server key, this is retrieved from the App page in the Nabto cloud console. */
    val SERVER_KEY: String
)

class ConfigDelegate {
    private var config: NabtoConfiguration? = null

    operator fun getValue(thisRef: Any?, property: KProperty<*>): NabtoConfiguration {
        return config ?: run {
            throw Exception("Shared code module's NabtoConfiguration is not set! Have you forgotten to call SetNabtoConfiguration?")
        }
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: NabtoConfiguration) {
        config = value
    }
}

internal var internalConfig: NabtoConfiguration by ConfigDelegate()

fun SetNabtoConfiguration(config: NabtoConfiguration) {
    internalConfig = config
}
