import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xingshi.ai.plugins.samplevision"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xingshi.ai.plugins.samplevision"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
            kotlin.directories.add("../../tests/plugins-sample-vision")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

configurations.matching {
    it.name == "debugRuntimeClasspath" || it.name == "releaseRuntimeClasspath"
}.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(project(":plugins:framework"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.activity.compose)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(project(":plugins:framework"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.compose.material3)
}

val pluginVersion = "1.0.7"

val extractPluginDex = tasks.register("extractPluginDex") {
    dependsOn("assembleDebug")
    val outDir = layout.buildDirectory.dir("plugin/dex")
    val apkDir = layout.buildDirectory.dir("outputs/apk/debug")
    outputs.dir(outDir)
    inputs.dir(apkDir)
    doLast {
        val apk = apkDir.get().asFile.listFiles()?.firstOrNull { it.extension == "apk" }
            ?: error("插件 APK 未找到")
        // 先清空输出目录：上一次构建残留的 dex 不能混进本次插件包
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
    archiveFileName.set("sample-vision-$pluginVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("plugin"))
    from(layout.buildDirectory.dir("plugin/dex"))
    from("plugin.json")
}
