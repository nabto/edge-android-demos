package com.nabto.edge.nabtodemo

/**
 * Config object that holds compile-time constants for configuring the App with.
 */
object NabtoConfig {
    /** App name that is returned from GET /iam/pairing (https://docs.nabto.com/developer/api-reference/coap/iam/pairing.html) */
    const val DEVICE_APP_NAME = "HeatPump"

    /** MDNS sub type that NabtoDeviceScanner will use to search for devices on the local network */
    const val MDNS_SUB_TYPE = "heatpump"

    /** Shared preferences file where e.g. client's private key and display name is stored */
    const val SHARED_PREFERENCES = "com.nabto.edge.nabtodemo.nabto_shared_preferences"

    // Shared preferences keys
    const val PRIVATE_KEY_PREF = "client_private_key"
    const val DISPLAY_NAME_PREF = "nabto_display_name"

    /** Nabto server key, this is retrieved from the App page in the Nabto cloud console. */
    const val SERVER_KEY = "sk-d8254c6f790001003d0c842d1b63b134"
}