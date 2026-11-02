import groovy.json.JsonOutput
import groovy.json.JsonSlurper


plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xingshi.ai.themes.official"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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

    (android as com.android.build.api.dsl.LibraryExtension).sourceSets.apply {
        getByName("main") {
            kotlin.directories.add(".")
            res.directories.add("res")
        }
    }
}

dependencies {
    implementation(project(":themes:framework"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":plugins:framework"))

    implementation(project(":feature:companion"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:groupchat"))
    implementation(project(":feature:moments"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:backup"))

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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
}


// ============================================================
// 作者主题打包：读取 themes/market-registry.json，通用打包主题目录、
// 可选代码 DEX，并生成离线市场目录；新增作者主题无需改宿主构建脚本。
// ============================================================

val themeMarketAssets = layout.buildDirectory.dir("generated/themeMarketAssets")

val marketRegistry = JsonSlurper().parse(rootProject.file("themes/market-registry.json")) as Map<*, *>
val marketEntries = (marketRegistry["themes"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

val packageAuthorThemes = tasks.register("packageAuthorThemes") {
    group = "theme"
    description = "打包 themes/<id>/ 为市场主题包并生成离线市场目录"
    doLast {
        // 清理旧版本 zip，避免 assets 里遗留过期主题包
        val expected = marketEntries.mapNotNull { entry ->
            val dirName = themeString(entry, "dir") ?: return@mapNotNull null
            val manifestFile = rootProject.file("themes/$dirName/theme.json")
            if (!manifestFile.isFile) return@mapNotNull null
            val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
            val id = themeString(manifest, "id") ?: return@mapNotNull null
            val version = themeString(manifest, "version") ?: return@mapNotNull null
            "$id-$version.zip"
        }.toSet()
        themeMarketAssets.get().dir("themes").asFile.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") && it.name !in expected }
            ?.forEach { it.delete() }
    }
}

fun themeString(map: Map<*, *>, key: String): String? = map[key] as? String

fun findD8Jar(): File {
    val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile
    return File(sdkDir, "build-tools").listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.map { File(it, "lib/d8.jar") }
        ?.firstOrNull { it.isFile }
        ?: throw IllegalStateException("找不到 d8.jar（Android build-tools）")
}

fun registerDexTask(
    themeId: String,
    jarSource: File,
    unpackTask: org.gradle.api.tasks.TaskProvider<*>?
): org.gradle.api.tasks.TaskProvider<JavaExec> {
    val targetDexDir = layout.buildDirectory.dir("themeMarket/$themeId/dex").get().asFile
    val d8Jar = findD8Jar()
    return tasks.register<JavaExec>("dexTheme$themeId") {
        if (unpackTask != null) dependsOn(unpackTask)
        classpath = files(d8Jar)
        mainClass.set("com.android.tools.r8.D8")
        args("--min-api", "26", "--output", targetDexDir.absolutePath, jarSource.absolutePath)
        doFirst { targetDexDir.mkdirs() }
    }
}

fun registerThemeZip(
    themeId: String,
    themeDir: File,
    themeVersion: String,
    dexTask: org.gradle.api.tasks.TaskProvider<JavaExec>?,
    authorDexPath: String?
): org.gradle.api.tasks.TaskProvider<Zip> = tasks.register<Zip>("packageThemeZip$themeId") {
    // 作者运行素材全量携带（字体、图标、预览、扩展资源等），生成物仅覆盖 dex/classes.dex
    from(themeDir) {
        if (dexTask != null) exclude("dex/classes.dex")
    }
    if (dexTask != null) {
        dependsOn(dexTask)
        from(layout.buildDirectory.dir("themeMarket/$themeId/dex")) { include("classes.dex"); into("dex") }
    }
    if (authorDexPath != null) {
        val authorDexFile = rootProject.file(authorDexPath)
        // 作者按指南把 DEX 放在主题目录 dex/classes.dex 时，上面的全目录收入已包含它；
        // 目标路径只允许出现一次，否则 Gradle 会报 duplicate entry。
        val includedByThemeDir = authorDexFile.canonicalFile == File(themeDir, "dex/classes.dex").canonicalFile
        if (!includedByThemeDir) {
            from(authorDexFile) { into("dex"); rename { "classes.dex" } }
        }
    }
    archiveFileName.set("$themeId-$themeVersion.zip")
    destinationDirectory.set(themeMarketAssets.map { it.dir("themes") })
}

marketEntries.forEach { entry ->
    val dirName = themeString(entry, "dir") ?: return@forEach
    val themeDir = rootProject.file("themes/$dirName")
    val manifestFile = File(themeDir, "theme.json")
    if (!manifestFile.isFile) return@forEach
    val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
    val themeId = themeString(manifest, "id") ?: return@forEach
    val themeVersion = themeString(manifest, "version") ?: "1.0.0"
    val code = entry["code"] as? Map<*, *>
    // 作者代码来源：本仓模块 AAR（module+aar）、作者自带 jar（jar）、作者自带 dex（dex）。
    // 后两者让新作者无需改 settings.gradle 或宿主脚本即可接入；ZIP 显式依赖 DEX 产物。
    var dexTask: org.gradle.api.tasks.TaskProvider<JavaExec>? = null
    var unpackTask: org.gradle.api.tasks.TaskProvider<Copy>? = null
    var authorDexPath: String? = null
    if (code != null) {
        authorDexPath = themeString(code, "dex")
        val codeJarPath = themeString(code, "jar")
        if (codeJarPath != null) {
            dexTask = registerDexTask(themeId, rootProject.file(codeJarPath), null)
        } else {
            val module = themeString(code, "module")
            val aarName = themeString(code, "aar")
            if (module != null && aarName != null) {
                val moduleProject = rootProject.project(module)
                val aarFile = moduleProject.layout.buildDirectory.file("outputs/aar/$aarName").get().asFile
                val unpackJar = tasks.register<Copy>("unpackThemeCodeJar$themeId") {
                    dependsOn("$module:assembleDebug")
                    from(zipTree(aarFile)) { include("classes.jar") }
                    into(layout.buildDirectory.dir("themeMarket/$themeId/aar"))
                    rename { "classes.jar" }
                }
                unpackTask = unpackJar
                val classesJar = layout.buildDirectory.file("themeMarket/$themeId/aar/classes.jar").get().asFile
                dexTask = registerDexTask(themeId, classesJar, unpackJar)
            }
        }
        dexTask?.let { task -> packageAuthorThemes.configure { dependsOn(task) } }
    }
    val zipTask = registerThemeZip(themeId, themeDir, themeVersion, dexTask, authorDexPath)
    packageAuthorThemes.configure { dependsOn(zipTask) }
}

val generateThemeMarketJson = tasks.register("generateThemeMarketJson") {
    dependsOn(packageAuthorThemes)
    val output = themeMarketAssets.map { it.file("themes/market.json") }
    inputs.file(rootProject.file("themes/market-registry.json"))
    inputs.files(marketEntries.mapNotNull { entry ->
        val dirName = themeString(entry, "dir") ?: return@mapNotNull null
        rootProject.file("themes/$dirName/theme.json")
    })
    outputs.file(output)
    doLast {
        val outFile = output.get().asFile
        outFile.parentFile.mkdirs()
        val themes = marketEntries.mapNotNull { entry ->
            val dirName = themeString(entry, "dir") ?: return@mapNotNull null
            val manifestFile = rootProject.file("themes/$dirName/theme.json")
            if (!manifestFile.isFile) return@mapNotNull null
            val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
            val id = themeString(manifest, "id") ?: return@mapNotNull null
            val version = themeString(manifest, "version") ?: "1.0.0"
            mapOf(
                "manifest" to manifest,
                "asset" to "themes/$id-$version.zip",
                "changelog" to (themeString(entry, "changelog") ?: "")
            )
        }
        val catalog = mapOf(
            "name" to "星识官方主题目录",
            "description" to "作者主题离线目录（随官方主题内置，不依赖后端）",
            "themes" to themes
        )
        outFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(catalog)), Charsets.UTF_8)
    }
}

(android as com.android.build.api.dsl.LibraryExtension).sourceSets.getByName("main").assets.directories.add("assets")
(android as com.android.build.api.dsl.LibraryExtension).sourceSets.getByName("main").assets.directories.add(themeMarketAssets.get().asFile.absolutePath)

tasks.named("preBuild") { dependsOn(generateThemeMarketJson) }
