import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}


kotlin {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    jvm()

    val xcfName = "libpebble-annotations"

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }
}
