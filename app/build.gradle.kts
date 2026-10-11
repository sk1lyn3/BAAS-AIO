plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "moe.baas.aio"
    compileSdk = 35

    defaultConfig {
        applicationId = "moe.baas.aio"
        minSdk = 28
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true; buildConfig = true; compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("shot.dir", System.getProperty("shotDir") ?: layout.buildDirectory.dir("test-shots").get().asFile.absolutePath)
            it.maxHeapSize = "1024m"
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/runtime-assets"))
    sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/debug-assets"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/ocr-jni"))
}

dependencies {
    implementation(project(":companion-runtime"))
    implementation("androidx.core:core:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("androidx.test:core:1.6.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

chaquopy {
    defaultConfig {
        version = "3.10"
        providers.gradleProperty("buildPython").orNull?.let { buildPython(it) }
        pip {
            install("opencv-python==4.5.1.48")
            install("numpy==1.26.2")
            install("rich==14.2.0")
            install("requests==2.32.5")
            install("pillow==11.0.0")
            install("psutil==7.1.3")
        }
    }
}

val prepareBaasCore by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val arguments = mutableListOf<Any>("python3", rootProject.file("tools/prepare_baas_core.py"),
        "--baas", providers.gradleProperty("baasSource").getOrElse("../blue_archive_auto_script"),
        "--fixture", providers.gradleProperty("probeFixture").getOrElse("artifacts/runtime-probe/shop.png"))
    providers.gradleProperty("baasRevision").orNull?.let { arguments.addAll(listOf("--revision", it)) }
    commandLine(arguments)
}
tasks.named("preBuild") { dependsOn(prepareBaasCore) }

val prepareOcrRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("tools/prepare_ocr_runtime.py"),
        "--prebuild", providers.gradleProperty("ocrPrebuild").getOrElse("../blue_archive_auto_script/core/ocr/baas_ocr_client/bin"),
        "--ndk", providers.gradleProperty("ocrNdk").getOrElse(android.sdkDirectory.resolve("ndk/27.2.12479018").absolutePath),
        "--reference", providers.gradleProperty("ocrReference").getOrElse("artifacts/ocr-probe/reference.json"))
}
tasks.named("preBuild") { dependsOn(prepareOcrRuntime) }

// The incremental APK writer reuses the previous APK in place and leaves the space of replaced
// entries (assets/baas-source.zip, dex, ...) as dead holes, so repeated debug builds grew to ~279 MB
// while the real content is ~204 MB. Remove the previous APK and the packager's incremental state in a
// separate task that runs *before* packaging, so Gradle sees the outputs gone and packages from scratch
// (deleting only the APK inside the package task itself produced an APK with just the changed entries).
for (variant in listOf("Debug", "Release")) {
    val fresh = tasks.register<Delete>("freshPackage$variant") {
        delete(layout.buildDirectory.dir("outputs/apk/${variant.lowercase()}"),
            layout.buildDirectory.dir("intermediates/incremental/package$variant"))
    }
    tasks.matching { it.name == "package$variant" }.configureEach { dependsOn(fresh) }
}
