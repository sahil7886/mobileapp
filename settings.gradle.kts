import java.util.Properties

val properties = Properties()
if (file("local.properties").exists()) {
    file("local.properties").inputStream().use { properties.load(it) }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "libpebbleroot"

// This fork is an iPhone-only distribution. Android sources remain in the repository for
// reference, but Android projects are deliberately outside the product graph.

include(":libpebble3")
include(":blobdbgen")
include(":blobannotations")
include(":composeApp")
include(":pebble")
include(":util")
include(":mcp")
include(":index-ai")
include(":resampler")
include(":cactus")
include(":libindex")
include(":experimental")
include(":krisp-stubs")
