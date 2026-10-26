// 在电脑上用 JVM 跑 App **自己的** VlmPlanner / VlmGroundingEngine(同一套提示词和解析),连电脑上的 llama-server、
// 载同一份 GGUF —— 测的是 App 真实的链路,不另写一份评测代码。这几个文件是纯 Kotlin,不碰 Android,
// 构建时从 app 源码里原样拷进来编译。
plugins {
    kotlin("jvm") version "1.9.21"
    application
}

val appSources = listOf(
    "planner/VlmPlanner.kt",
    "grounding/VlmGroundingEngine.kt",
    "inference/LocalPlannerService.kt",
    "inference/LocalGroundingService.kt",
    "inference/WarmupResult.kt",
    "inference/VisionContextBudget.kt",
)

val copyAppSources by tasks.registering(Sync::class) {
    from("../../app/src/main/java/com/ufo/galaxy") { include(appSources) }
    into(layout.buildDirectory.dir("appsrc/com/ufo/galaxy"))
}

sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("appsrc"))
}

tasks.named("compileKotlin") { dependsOn(copyAppSources) }

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("com.ufo.galaxy.eval.EvalMainKt")
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
