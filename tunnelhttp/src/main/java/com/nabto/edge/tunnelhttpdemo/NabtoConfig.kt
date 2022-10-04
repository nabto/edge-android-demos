package com.nabto.edge.tunnelhttpdemo

import com.nabto.edge.sharedcode.NabtoConfiguration

/**
 * Config object that holds compile-time constants for configuring the App with.
 */
object NabtoConfig : NabtoConfiguration(
    DEVICE_APP_NAME = "Tcp Tunnel",
    MDNS_SUB_TYPE = "tcptunnel",
    SHARED_PREFERENCES = "com.nabto.edge.tunnelvideodemo.nabto_shared_preferences",
    PRIVATE_KEY_PREF = "client_private_key",
    DISPLAY_NAME_PREF = "nabto_display_name",
    SERVER_KEY = "sk-d8254c6f790001003d0c842d1b63b134"
) {
    const val RTSP_ENDPOINT = "/video"
}