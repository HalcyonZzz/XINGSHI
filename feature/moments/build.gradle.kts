plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.xingshi.ai.feature.moments"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    (android as com.android.build.api.dsl.LibraryExtension).sourceSets.apply {
        getByName("main") { kotlin.directories.add(".") }
        getByName("test") { kotlin.directories.add("../../tests/feature-moments") }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
