plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "moe.baas.aio"
    compileSdk = 35

    defaultConfig {
        applicationId = "moe.baas.aio"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/probe-assets"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/ocr-jni"))
}

dependencies {
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

val prepareBaasProbe by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("tools/prepare_probe.py"),
        "--baas", providers.gradleProperty("baasSource").getOrElse("../blue_archive_auto_script"),
        "--fixture", providers.gradleProperty("probeFixture").getOrElse("artifacts/runtime-probe/shop.png"))
}
tasks.named("preBuild") { dependsOn(prepareBaasProbe) }

val prepareOcrProbe by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("tools/prepare_ocr_probe.py"),
        "--prebuild", providers.gradleProperty("ocrPrebuild").getOrElse("../blue_archive_auto_script/core/ocr/baas_ocr_client/bin"),
        "--ndk", providers.gradleProperty("ocrNdk").getOrElse(android.sdkDirectory.resolve("ndk/27.2.12479018").absolutePath),
        "--reference", providers.gradleProperty("ocrReference").getOrElse("artifacts/ocr-probe/reference.json"))
}
tasks.named("preBuild") { dependsOn(prepareOcrProbe) }
