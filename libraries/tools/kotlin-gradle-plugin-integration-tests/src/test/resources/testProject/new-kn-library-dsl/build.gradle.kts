import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.*
import org.jetbrains.kotlin.gradle.targets.native.tasks.artifact.library
import org.jetbrains.kotlin.gradle.targets.native.tasks.artifact.nativeLibrary
import org.jetbrains.kotlin.konan.target.KonanTarget.*

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

plugins {
    kotlin("multiplatform").apply(false)
}

//:shared:assembleDebugSharedMylibLinuxX64
nativeLibrary("mylib") {
    targets = setOf(LINUX_X64)
    artifact = library
    from(project(":shared"))
}

//:shared:assembleDebugStaticMyslibLinuxX64
nativeLibrary("myslib") {
    targets = setOf(LINUX_X64)
    artifact = library
    modes = setOf(DEBUG)
    isStatic = true
    from(
        project(":shared"),
        project(":lib")
    )
}