pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}

rootProject.name = "星识"
include(":app")
project(":app").projectDir = file("app/android")
include(":core:domain")
include(":core:common")
include(":core:database")
include(":core:network")
include(":feature:companion")
include(":feature:chat")
include(":feature:groupchat")
include(":feature:moments")
include(":feature:notification")
include(":feature:profile")
include(":feature:settings")
include(":feature:backup")
include(":plugins:framework")
include(":themes:framework")
include(":themes:official")



file("themes").listFiles()?.sortedBy { it.name }?.forEach { dir ->
    if (dir.isDirectory && dir.name !in setOf("framework", "official") && dir.resolve("build.gradle.kts").exists()) {
        include(":themes:${dir.name}")
    }
}

file("plugins").listFiles()?.sortedBy { it.name }?.forEach { dir ->
    if (dir.isDirectory && dir.name != "framework" && dir.resolve("build.gradle.kts").exists()) {
        include(":plugins:${dir.name}")
    }
}
