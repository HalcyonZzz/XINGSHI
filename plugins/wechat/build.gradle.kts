import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.xingshi.ai.plugins.wechat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xingshi.ai.plugins.wechat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
            kotlin.directories.add("../../tests/feature-wechat")
        }
    }
}

configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(project(":plugins:framework"))
    compileOnly(project(":core:common"))
    compileOnly(project(":core:database"))
    compileOnly(project(":themes:framework"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.androidx.lifecycle.viewmodel.compose)
    compileOnly(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(kotlin("stdlib"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
    implementation(libs.wechat.ilink.sdk) {
        exclude(group = "ch.qos.logback", module = "logback-classic")
        exclude(group = "ch.qos.logback", module = "logback-core")
    }
}

val pluginVersion = "1.0.0"

val extractPluginDex = tasks.register("extractPluginDex") {
    dependsOn("assembleDebug")
    val outDir = layout.buildDirectory.dir("plugin/dex")
    val apkDir = layout.buildDirectory.dir("outputs/apk/debug")
    outputs.dir(outDir)
    inputs.dir(apkDir)
    doLast {
        val apk = apkDir.get().asFile.listFiles()?.firstOrNull { it.extension == "apk" } ?: error("插件 APK 未找到")
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
                File(dexDir, entry.name).also { it.parentFile?.mkdirs() }
                    .outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
            }
        }
    }
}

tasks.register<Zip>("pluginPackage") {
    dependsOn(extractPluginDex)
    archiveFileName.set("wechat-$pluginVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("plugin"))
    from(layout.buildDirectory.dir("plugin/dex"))
    from("plugin.json")
}
