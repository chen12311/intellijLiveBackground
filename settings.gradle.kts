import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "live-background"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
    }
}

plugins {
    // 本机没有 JDK 21 时，Gradle 会自动下载一个用来编译
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
