import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val rustDir = rootProject.projectDir.parentFile.resolve("rust_core")

val localProps = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()
        ?.use { load(it) }
}

val cargoExe = if (System.getProperty("os.name").lowercase().contains("windows")) "cargo.exe" else "cargo"

fun resolveCargoExecutable(): File {
    localProps.getProperty("cargo.dir")?.let { dir ->
        val candidate = file("$dir/$cargoExe")
        if (candidate.exists()) return candidate
    }
    System.getenv("CARGO_HOME")?.let { home ->
        val candidate = file("$home/bin/$cargoExe")
        if (candidate.exists()) return candidate
    }
    (System.getenv("USERPROFILE") ?: System.getenv("HOME"))?.let { home ->
        val candidate = file("$home/.cargo/bin/$cargoExe")
        if (candidate.exists()) return candidate
    }
    System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
        val candidate = file("$dir/$cargoExe")
        if (candidate.exists()) return candidate
    }
    error(
        "cargo executable not found. Set 'cargo.dir' in android_app/local.properties " +
            "(see local.properties.example) or install Rust via https://rustup.rs"
    )
}

val rustCargo = resolveCargoExecutable()

val generatedKotlinDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val generatedJniDir = layout.buildDirectory.dir("generated/jniLibs")

fun rustEnvironment(): Map<String, String> {
    val env = mutableMapOf<String, String>()
    if (System.getenv("ANDROID_HOME") == null && System.getenv("ANDROID_SDK_ROOT") == null) {
        localProps.getProperty("sdk.dir")?.let { env["ANDROID_HOME"] = it }
    }
    localProps.getProperty("rust.ndk.dir")?.let { env["ANDROID_NDK_HOME"] = it }
    return env
}

val rustBuildProfile = "debug"
val rustProfileDir = if (rustBuildProfile == "release") "release" else "debug"
val rustBuildArgs = if (rustBuildProfile == "release") listOf("--release") else emptyList<String>()

val buildRustAndroid = tasks.register<Exec>("buildRustAndroid") {
    workingDir(rustDir)
    environment(rustEnvironment())
    commandLine(
        rustCargo.absolutePath,
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        generatedJniDir.get().asFile.absolutePath,
        "--",
        "build",
        *rustBuildArgs.toTypedArray()
    )
}

val cleanGeneratedKotlin = tasks.register<Delete>("cleanGeneratedKotlin") {
    delete(generatedKotlinDir)
}

val generateUniFFIKotlin = tasks.register<Exec>("generateUniFFIKotlin") {
    dependsOn(buildRustAndroid, cleanGeneratedKotlin)
    workingDir(rustDir)

    commandLine(
        rustCargo.absolutePath,
        "run",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        rustDir.resolve(
            "target/aarch64-linux-android/$rustProfileDir/librust_core.so"
        ).absolutePath,
        "--language",
        "kotlin",
        "--out-dir",
        generatedKotlinDir.get().asFile.absolutePath
    )
}

android {
    namespace = "com.example.android_app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.android_app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += generatedKotlinDir.get().asFile.absolutePath
            jniLibs.directories += generatedJniDir.get().asFile.absolutePath
        }
    }

}

tasks.named("preBuild") {
    dependsOn(generateUniFFIKotlin)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
