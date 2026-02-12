// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Note: Using buildscript block instead of plugins block due to repository resolution constraints
// in certain build environments. This is functionally equivalent and more compatible.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
    }
}
