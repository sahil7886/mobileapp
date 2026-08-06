import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}


kotlin {
    iosArm64()

    iosSimulatorArm64()
}
