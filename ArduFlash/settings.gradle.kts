pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // necessário p/ usb-serial-for-android
    }
}

rootProject.name = "ArduFlash"
include(":app")
