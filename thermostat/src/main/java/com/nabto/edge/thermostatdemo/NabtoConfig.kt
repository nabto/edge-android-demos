package com.nabto.edge.thermostatdemo

import com.nabto.edge.sharedcode.NabtoConfiguration

/**
 * Config object that holds compile-time constants for configuring the App with.
 */
object NabtoConfig : NabtoConfiguration(
    DEVICE_APP_NAME = "Thermostat",
    MDNS_SUB_TYPE = "thermostat",
    PRIVATE_KEY_PREF = "client_private_key",
    DISPLAY_NAME_PREF = "nabto_display_name",
    SERVER_KEY = "sk-d8254c6f790001003d0c842d1b63b134"
)