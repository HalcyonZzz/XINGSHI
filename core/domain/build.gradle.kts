plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

android {
    namespace = "com.xingshi.ai.domain"
    compileSdk = 35
    defaultConfig { minSdk = 26 }

    (android as com.android.build.api.dsl.LibraryExtension).sourceSets.apply {
        getByName("main") {
            kotlin.directories.add(".")
        }
    }
}
