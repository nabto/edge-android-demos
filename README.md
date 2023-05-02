# Nabto Edge Android Demo Repository
This repository contains several example Android apps that each showcase a different usage scenario for Nabto Edge.

# Structure
The repository is split into several modules. The [sharedcode](sharedcode) module contains common functionality shared between the apps. This includes UI layout files and code for the device overview page that shows all online and offline paired devices as well as device discovery and device pairing. 

## Thermostat
Thermostat demo is in the [thermostat](thermostat) folder. There is a tutorial on how to set up a device for this demo at the [Nabto Edge documentation](https://docs.nabto.com/developer/platforms/android/thermostat.html). This demo showcases a client UI For interacting with the [thermostat example application](https://github.com/nabto/nabto-embedded-sdk/tree/master/examples/thermostat).

## Tunnel Video
Tunnel Video demo is in the [tunnelvideo](tunnelvideo) folder. This demo showcases how you can retrieve an RTSP stream through a TCP tunnel using GStreamer. There is a tutorial on how to set up a device for this demo at the [Nabto Edge documentation](https://docs.nabto.com/developer/platforms/android/video.html). Tunnel Video demo is meant to be used with [tcp_tunnel_device](https://github.com/nabto/nabto-embedded-sdk/tree/master/apps/tcp_tunnel_device). Building Tunnel Video requires extra steps to include GStreamer binaries. See [Building Tunnel Video](#building-tunnel-video).

## Tunnel HTTP
Tunnel HTTP demo is in the [tunnelhttp](tunnelhttp) folder. This demo showcases opening a TCP tunnel and subsequently viewing websites that are served by a device. Your webserver should be running [tcp_tunnel_device](https://github.com/nabto/nabto-embedded-sdk/tree/master/apps/tcp_tunnel_device). There is currently no tutorial for setting up a device with Tunnel HTTP, but it is very similar to setting up for Tunnel Video.

# Building and running
You can open the project in Android Studio as-is. If you try to build it may complain about missing a `GSTREAMER_ROOT_ANDROID` environment variable. This environment variable is only relevant if you intend to build Tunnel Video. If you intend to build one of the other projects that do not use GStreamer you may simply set this variable to `/dev/null` or similar, it will not be used. See the next section if you intend to build Tunnel Video.

You may also build and install one of the apps on the commandline. You can use `installDebug` to install directly to a connected phone, or `assembleDebug` to create a debug APK. See the following example to build thermostat.
```sh
# Remember to set GSTREAMER_ROOT_ANDROID to something so Gradle doesn't complain
export GSTREAMER_ROOT_ANDROID=/dev/null
gradlew :thermostat:build
gradlew :thermostat:assembleDebug
```

# Building Tunnel Video
Tunnel Video relies on GStreamer for displaying video streams.
* First download [GStreamer's prebuilt binaries for Android](https://gstreamer.freedesktop.org/data/pkg/android/). The latest version that has been tested to work is 1.22.1
* Unzip the compressed archive into any folder of your choice. The folder should contain subdirectories named after the supported platforms such as `x86_64`, `armv7`, `arm64` etc.
* Set a `GSTREAMER_ROOT_ANDROID` environment variable to point to the folder you unzipped to. This environment variable must be available at build time for Gradle to determine where to find GStreamer's binaries.

Once these steps are complete you may build Tunnel Video in the same way as the other demos, in Android Studio or  e.g. on commandline
```sh
gradlew :tunnelvideo:build
```

# How to make add a new app
If you want to use the same skeleton framework in the sharedcode module to build an app, you may follow the [Making a new app using the sharedcode skeleton](making_new_apps.md) tutorial

Please note that making a new app in this way is **NOT** necessary to use Nabto Edge. It is simply how the project files for these demos are laid out. You may want to take a look at our more simple examples to see standalone short examples of using various Nabto Edge functionality.

* [Edge Android Simple CoAP](https://github.com/nabto/edge-android-simplecoap)
* [Edge Android Simple Tunnel](https://github.com/nabto/edge-android-simpletunnel)
