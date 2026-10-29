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
            // 发布前加 -PverifyAll，把 2025.1 到最新 EAP 全跑一遍（要下载好几个 IDE）；平时只跑最低版本 + 本机最新版
            if (providers.gradleProperty("verifyAll").isPresent) {
                recommended()
            } else {
                create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("platformVersion"))
                if (localPhpStorm != null && localPhpStorm.exists()) local(localPhpStorm)
            }
        }
    }
    // ./gradlew publishPlugin 发到 JetBrains Marketplace；token 在 Marketplace 个人页的 My Tokens 里生成。
    // 第一个版本要在网页上手动上传
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
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
