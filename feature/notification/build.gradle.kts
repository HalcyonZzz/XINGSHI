plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.xingshi.ai.feature.notification"
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
            res.directories.add("res")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":themes:framework"))

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
