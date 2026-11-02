import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.xingshi.ai.plugins.stt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xingshi.ai.plugins.stt"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    (android as com.android.build.api.dsl.ApplicationExtension).sourceSets.apply {
        getByName("main") {
            kotlin.directories.add(".")
            manifest.srcFile("AndroidManifest.xml")
        }
        getByName("test") {
            kotlin.directories.add("../../tests/plugins-stt")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

configurations.matching { it.name == "debugRuntimeClasspath" || it.name == "releaseRuntimeClasspath" }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(project(":plugins:framework"))
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":plugins:framework"))
}

val pluginVersion = "1.0.0"

val extractPluginDex = tasks.register("extractPluginDex") {
    dependsOn("assembleDebug")
    val outDir = layout.buildDirectory.dir("plugin/dex")
    val apkDir = layout.buildDirectory.dir("outputs/apk/debug")
    outputs.dir(outDir)
    inputs.dir(apkDir)
    doLast {
        val apk = apkDir.get().asFile.listFiles()?.firstOrNull { it.extension == "apk" }
            ?: error("插件 APK 未找到")
        val dexDir = outDir.get().asFile
        dexDir.deleteRecursively()
        dexDir.mkdirs()
        ZipFile(apk).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                .sortedBy { it.name }
                .toList()
            require(dexEntries.isNotEmpty()) { "classes.dex 未找到" }
            dexEntries.forEach { entry ->
                val dexTarget = File(outDir.get().asFile, entry.name)
                dexTarget.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> dexTarget.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }
}

tasks.register<Zip>("pluginPackage") {
    dependsOn(extractPluginDex)
    archiveFileName.set("stt-$pluginVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("plugin"))
    from(layout.buildDirectory.dir("plugin/dex"))
    from("plugin.json")
}
