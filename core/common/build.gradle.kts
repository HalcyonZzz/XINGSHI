plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.xingshi.ai.common"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    (android as com.android.build.api.dsl.LibraryExtension).sourceSets.apply {
        getByName("main") {
            kotlin.directories.add(".")
            assets.directories.add("assets")
        }
        getByName("test") {
            kotlin.directories.add("../../tests/core-common")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
}
