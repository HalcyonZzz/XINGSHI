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
        // 厂商 Push SDK 仓库：华为 HMS
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

// 作者主题代码模块：themes/<name>/ 下带 build.gradle.kts 的目录自动纳入构建，
// 新增作者只需登记自己的目录与打包约定，不再改宿主脚本。
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
