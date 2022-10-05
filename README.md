# Nabto Edge Android Demo Repository
This repository contains several Android apps that each showcase different usage scenarios for Nabto Edge. The repository is split up into several modules, with the `sharedcode` module being a dependency that provides the basic app framework for all the examples.

## Thermostat
The `thermostat` demo showcases a client UI for interacting with the [thermostat example application](https://github.com/nabto/nabto-embedded-sdk/tree/master/examples/thermostat).

## Tunnel Video
The `tunnelvideo` demo showcases how you can retrieve an RTSP stream through a TCP tunnel using ExoPlayer. `tunnelvideo` is meant to be used with [tcp_tunnel_device](https://github.com/nabto/nabto-embedded-sdk/tree/master/apps/tcp_tunnel_device).

# Building and running
You can open the project in Android Studio as-is and run the app on an emulator or physical phone.
Alternatively if you do not want to use Android Studio, you can build and install the app on your phone/emulator using Gradle
```
gradlew build
gradlew installDebug
```
