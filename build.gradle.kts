import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 2025.1 自带的 Kotlin 标准库是 2.1，不能用到更新的标准库 API
        apiVersion = KotlinVersion.KOTLIN_2_1
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        phpstorm(providers.gradleProperty("platformVersion"))
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
}

val localPhpStorm = providers.gradleProperty("localPhpStorm").map { file(it) }.orNull

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null } // 不设版本上限
        }
    }
    pluginVerification {
        ides {
            // 平时：最低版本 + 本机最新版。P3 上架前换成 recommended()，把 2025.1 到最新 EAP 全跑一遍
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("platformVersion"))
            if (localPhpStorm != null && localPhpStorm.exists()) local(localPhpStorm)
        }
    }
}

// ./gradlew runPhpStorm：在最新版 PhpStorm 里实测（runIde 跑的是编译用的 2025.1）
intellijPlatformTesting.runIde.register("runPhpStorm") {
    if (localPhpStorm != null && localPhpStorm.exists()) {
        localPath = localPhpStorm
    } else {
        type = IntelliJPlatformType.PhpStorm
        version = providers.gradleProperty("runPhpStormVersion")
    }
}
