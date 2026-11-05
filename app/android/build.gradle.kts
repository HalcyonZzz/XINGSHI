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
        versionCode = 26
        versionName = "0.1.13"

        manifestPlaceholders["developerName"] = "苏苏"
        manifestPlaceholders["developerOrg"] = "XingShi"

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
        create("dev") {
            storeFile = rootProject.file("release.keystore")
            storePassword = signingCredential("XINGSHI_STORE_PASSWORD") ?: "debug_password_placeholder"
            keyAlias = signingCredential("XINGSHI_KEY_ALIAS") ?: "your_alias"
            keyPassword = signingCredential("XINGSHI_KEY_PASSWORD") ?: "debug_password_placeholder"
            enableV3Signing = true
        }
        create("release") {
            storeFile = signingCredential("XINGSHI_RELEASE_KEYSTORE")?.let { rootProject.file(it) }
                ?: rootProject.file("release.keystore")
            signingCredential("XINGSHI_RELEASE_STORE_PASSWORD")?.let { storePassword = it }
            signingCredential("XINGSHI_RELEASE_KEY_ALIAS")?.let { keyAlias = it }
            signingCredential("XINGSHI_RELEASE_KEY_PASSWORD")?.let { keyPassword = it }
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

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
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.serialization.json)

    implementation(project(":feature:companion"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:groupchat"))
    implementation(project(":feature:moments"))
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

    val oppoAar = rootProject.file("build-tools/vendor-android/oppo-push-3.0.0.aar")
    if (oppoAar.exists()) {
        implementation(files(oppoAar))
    }
    val vivoAar = rootProject.file("build-tools/vendor-android/vivo-push-4.1.5.0.aar")
    if (vivoAar.exists()) {
        implementation(files(vivoAar))
    }
    implementation(libs.huawei.hms.push)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
