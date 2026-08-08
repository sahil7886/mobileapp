import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.nativeCocoaPods)
    alias(libs.plugins.kotlinx.atomicfu)
}


kotlin {
    val xcodeExists = providers.exec {
        isIgnoreExitValue = true
        commandLine("which", "xcode-select")
    }.result.get().exitValue == 0
    val xcodeDir = if (xcodeExists) {
        providers.exec {
            commandLine("xcode-select", "-p")
        }.standardOutput.asText.get().trim()
    } else {
        ""
    }

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    // Make xcode invoke gradle from the right place
    tasks.register("fixXcodeProject") {
        val xcodeProjectFile = project.file("../iosApp/Pods/Pods.xcodeproj/project.pbxproj")
        val rootProjectPath = rootProject.projectDir.absolutePath
        doLast {
            if (xcodeProjectFile.exists()) {
                var content = xcodeProjectFile.readText()
                content = content.replace("gradlew\\\" -p \\\"\$REPO_ROOT\\\"", "gradlew\\\" -p \\\"$rootProjectPath\\\"")
                xcodeProjectFile.writeText(content)
            } else {
                logger.warn("Xcode project file not found, skipping fix: ${xcodeProjectFile.path}")
            }
        }
    }
    tasks.named("podInstall") {
        finalizedBy("fixXcodeProject")
    }

    cocoapods {
        version = "1.0"
        summary = "Core App"
        homepage = "https://github.com/coredevices/CoreApp"
        license = "proprietary"
        ios.deploymentTarget = "15.6"
        podfile = project.file("../iosApp/Podfile")


        framework {
            baseName = "ComposeApp"
        }
    }

    buildList {
        // idea.sync.active is set by the IDE during sync only. The simulator target doubles the
        // pod/cinterop work sync waits on; command-line and Xcode builds still configure it.
        val ideSync = providers.systemProperty("idea.sync.active").orNull.toBoolean()
        if (!ideSync) add(iosSimulatorArm64())
        add(iosArm64())
    }.forEach {
        it.binaries.all {
            freeCompilerArgs += listOf(
                "-Xdisable-phases=DevirtualizationAnalysis,DCEPhase"
            )
            linkerOpts.addAll(listOf("-framework", "Accelerate"))
            val osName =
                if (target.konanTarget.name.contains("simulator")) "iphonesimulator" else "iphoneos"
            if (xcodeExists) {
                linkerOpts.addAll(listOf(
                    "-weak_framework", "CoreML",
                    "-L$xcodeDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$osName"
                ))
            }
        }
    }
    
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("kotlinx.cinterop.BetaInteropApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.io.okio)
            implementation(libs.kermit)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.ui)
            implementation(libs.backhandler)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.serialization)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coil)
            implementation(libs.coil.svg)


            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.coroutines)
            implementation(project(":pebble"))
            implementation(project(":util"))
            implementation(libs.kmpio)
            implementation(project(":libpebble3"))
            implementation(project(":libindex"))
            implementation(project(":index-ai"))
            api(project(":mcp"))
            implementation(libs.health.kmp)
        }
    }
}

compose.resources {
    packageOfResClass = "coreapp.composeapp.generated.resources"
}
