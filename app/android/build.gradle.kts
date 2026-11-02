import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xingshi.ai"

    compileSdk = 35

    defaultConfig {
        applicationId = "com.xingshi.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.1.5"

        manifestPlaceholders["developerName"] = "苏苏"
        manifestPlaceholders["developerOrg"] = "XingShi"

        // Developer: 苏苏 / Organization: XingShi

        // Force multi-DEX output
        multiDexEnabled = true

        manifestPlaceholders[
            "VIVO_PUSH_API_KEY"] = project.findProperty("VIVO_PUSH_API_KEY")?.toString() ?: ""
        manifestPlaceholders[
            "VIVO_PUSH_APP_ID"] = project.findProperty("VIVO_PUSH_APP_ID")?.toString() ?: ""

        buildConfigField("String", "HARDENING_LEVEL", "\"OPEN_SOURCE\"")
        val backendOrigin = providers.gradleProperty("LOOM_BACKEND_ORIGIN").orNull.orEmpty()
        require(backendOrigin.none { it == '"' || it == '\n' || it == '\r' || it == '\\' })
        buildConfigField("String", "LOOM_BACKEND_ORIGIN", "\"$backendOrigin\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    ndkVersion = "30.0.14904198"

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // 签名口令读取顺序：环境变量 → Gradle 属性 → 本机 local.properties（git-ignored）。
    // 跟踪的 gradle.properties 不再保存任何签名口令。
    fun signingCredential(name: String): String? {
        System.getenv(name)?.let { return it }
        (project.findProperty(name) as String?)?.let { return it }
        val local = rootProject.file("local.properties")
        if (!local.isFile) return null
        return runCatching {
            Properties().apply { local.inputStream().use { load(it) } }.getProperty(name)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    signingConfigs {
        // 本地开发签名：debug 专用，沿用与历史产物相同的 keystore/别名/口令以保持可覆盖安装。
        create("dev") {
            storeFile = rootProject.file("release.keystore")
            storePassword = signingCredential("XINGSHI_STORE_PASSWORD") ?: "debug_password_placeholder"
            keyAlias = signingCredential("XINGSHI_KEY_ALIAS") ?: "your_alias"
            keyPassword = signingCredential("XINGSHI_KEY_PASSWORD") ?: "debug_password_placeholder"
            enableV3Signing = true
        }
        // 发布签名：必须由环境变量 / 属性 / 本机 local.properties 提供，缺省不落占位口令。
        create("release") {
            storeFile = rootProject.file("release.keystore")
            signingCredential("XINGSHI_STORE_PASSWORD")?.let { storePassword = it }
            signingCredential("XINGSHI_KEY_ALIAS")?.let { keyAlias = it }
            signingCredential("XINGSHI_KEY_PASSWORD")?.let { keyPassword = it }
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_name", "星识")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    // jniLibs are picked up automatically from jniLibs/

    (android as com.android.build.api.dsl.ApplicationExtension).sourceSets.apply {
        getByName("main") {
            kotlin.directories.add(".")
            res.directories.add("res")
            assets.directories.add("assets")
            manifest.srcFile("AndroidManifest.xml")
        }
        getByName("test") {
            kotlin.directories.add("../../tests/app")
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.serialization.json)

    // Feature modules
    implementation(project(":feature:companion"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:groupchat"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:backup"))
    implementation(project(":plugins:framework"))
    implementation(project(":themes:framework"))
    implementation(project(":themes:official"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.animation.graphics)
    implementation(libs.androidx.work.runtime.ktx)

    // 厂商 Push SDK
    // OPPO / vivo 使用本地 aar，请从各厂商开放平台下载后放置到 build-tools/vendor-android
    val oppoAar = rootProject.file("build-tools/vendor-android/oppo-push-3.0.0.aar")
    if (oppoAar.exists()) {
        implementation(files(oppoAar))
    }
    val vivoAar = rootProject.file("build-tools/vendor-android/vivo-push-4.1.5.0.aar")
    if (vivoAar.exists()) {
        implementation(files(vivoAar))
    }
    // 华为 HMS Push 使用 Maven 依赖
    implementation(libs.huawei.hms.push)

    // 小米推送：请从 https://dev.mi.com/ 下载 aar 放到 build-tools/vendor-android/xiaomi-push-x.x.x.aar，
    // 然后取消下面注释并同步 Gradle。
    // implementation(rootProject.files("build-tools/vendor-android/xiaomi-push-6.0.1.aar"))

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
