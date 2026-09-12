import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val rustDir = rootProject.projectDir.parentFile.resolve("rust_core")

val rustCargo = System.getenv("USERPROFILE")?.let { file("$it/.cargo/bin/cargo.exe") }?: error("USERPROFILE environment not available")
val generatedKotlinDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val generatedJniDir = layout.buildDirectory.dir("generated/jniLibs")

val rustBuildProfile = "debug"
val rustProfileDir = if (rustBuildProfile == "release") "release" else "debug"
val rustBuildArgs = if (rustBuildProfile == "release") listOf("--release") else emptyList<String>()

val buildRustArm64 = tasks.register<Exec>("buildRustArm64") {
    workingDir(rustDir)
    commandLine(
        rustCargo.absolutePath,
        "build",
        *rustBuildArgs.toTypedArray(),
        "--target",
        "aarch64-linux-android"
    )
}
val buildRustX86_64 = tasks.register<Exec>("buildRustX86_64") {
    workingDir(rustDir)

    commandLine(
        rustCargo.absolutePath,
        "build",
        *rustBuildArgs.toTypedArray(),
        "--target",
        "x86_64-linux-android"
    )
}

val buildRustAndroid = tasks.register("buildRustAndroid") {
    dependsOn(buildRustArm64)
    dependsOn(buildRustX86_64)
}

val cleanGeneratedKotlin = tasks.register<Delete>("cleanGeneratedKotlin") {
    delete(generatedKotlinDir)
}

val generateUniFFIKotlin = tasks.register<Exec>("generateUniFFIKotlin") {
    dependsOn(buildRustArm64, cleanGeneratedKotlin)
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

val copyRustArm64 = tasks.register<Copy>("copyRustArm64") {
    dependsOn(buildRustArm64)

    from(
        rustDir.resolve("target/aarch64-linux-android/$rustProfileDir")
    ) {
        include("librust_core.so")
    }

    into(
        generatedJniDir.map {
            it.dir("arm64-v8a")
        }
    )
}

val copyRustX86_64 = tasks.register<Copy>("copyRustX86_64") {

    dependsOn(buildRustX86_64)

    from(
        rustDir.resolve(
            "target/x86_64-linux-android/$rustProfileDir"
        )
    ) {
        include("librust_core.so")
    }

    into(
        generatedJniDir.map {
            it.dir("x86_64")
        }
    )
}

val copyRustLibraries = tasks.register("copyRustLibraries") {
    dependsOn(copyRustArm64)
    dependsOn(copyRustX86_64)
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
    dependsOn(copyRustLibraries)
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
