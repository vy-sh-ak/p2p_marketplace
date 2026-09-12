# P2P Marketplace

A free peer-to-peer marketplace where users can list and sell items directly to other users — no middleman, no listing fees.

> **Status:** early development. The project currently consists of the app skeleton with a Rust core wired into the Android build via UniFFI.

## Tech Stack

| Layer | Technology |
|-------|------------|
| Android app | Kotlin, Jetpack Compose (Material 3), minSdk 24 |
| Business logic | Rust (`rust_core` crate) |
| Bridge | [UniFFI](https://mozilla.github.io/uniffi-rs/) generated Kotlin bindings + JNA |
| Build | Gradle (Kotlin DSL) + Cargo |

The Gradle build automatically compiles the Rust core for `arm64-v8a` and `x86_64`, generates the UniFFI Kotlin bindings, and packages `librust_core.so` into the APK — no manual steps required.

## Project Structure

```
p2p_marketplace/
├── android_app/          # Android application (Kotlin + Compose)
│   └── app/
└── rust_core/            # Rust core library, exposed to Kotlin via UniFFI
    └── src/lib.rs
```

## Prerequisites

- **Android Studio** with the Android SDK and NDK installed
- **Rust** via [rustup](https://rustup.rs/)
- Android Rust targets:

  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  ```

- NDK linker configuration: `rust_core/.cargo/config.toml` points the Android targets at the NDK clang linkers. Update the paths there to match your local NDK installation (e.g. `Sdk/ndk/<version>/toolchains/llvm/prebuilt/<host>/bin/<triple>-clang.cmd`).

## Building

```sh
cd android_app
./gradlew :app:assembleDebug        # Windows: .\gradlew :app:assembleDebug
```

The output APK is written to `android_app/app/build/outputs/apk/debug/`.

## Contributing

PRs are welcome — see the [pull request template](.github/PULL_REQUEST_TEMPLATE.md).
