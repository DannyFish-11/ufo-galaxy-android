// 手机端测(V2 docs/DEVICE_SELF_DESCRIPTION_DESIGN.md 6.3):独立的 JVM 构建,不进 App 的构建。
// 用法见 tools/eval/README.md;CI 里跑 `./gradlew -p tools/eval test`。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "galaxy-phone-eval"
