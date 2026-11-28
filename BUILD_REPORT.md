# LocalCanvas V16.1 Build Report

## 2026-11-28 local verification

- `testDebugUnitTest lintDebug assembleDebug`: PASS.
- Real stable-diffusion.cpp ARM64 CPU native compile: PASS (`dee497f4df67786a24a30b46176ebc188f247192`).
- Engine-included `:app:assembleDebug`: PASS with `-PrequireSdCpp=ON -PenableVulkan=OFF`.
- APK size: 59,800,998 bytes.
- APK SHA-256: `CF60064BDA76716BA307D48FAB9F13EAE43AB0C5F27D9168BFF12FDB516BECAD`.
- ELF check: `ELF64`, `AArch64`; JNI symbols for generation, upscaling, and Canny entrypoint present.
- Physical device check: BLOCKED; `adb devices` reported no connected device.
- Vulkan local check: BLOCKED; Windows environment has no `glslc`. Default project setting remains Vulkan ON and CI remains the authoritative Vulkan gate.
- GitHub Actions V16.1 run `46129902303`: PASS, including the Vulkan link/inspection step. [Run log](https://github.com/deca19/LocalCanvas/actions/runs/46129902303)
- Incremental Java verification after V16.1 changes: PASS (`:app:compileDebugJavaWithJavac`).

## Changes

- `versionCode 154`, `versionName 1.5.4`.
- Verified Gradle wrapper JAR restored; `unzip -t` passed.
- Removed `unitTests.returnDefaultValues`.
- Pinned stable-diffusion.cpp to valid upstream commit `dee497f4df67786a24a30b46176ebc188f247192`.
- Connected LoRA entries to the official `sd_opt_set_loras` API.
- Added version/file-level store entries without fabricating URLs; only direct HTTPS file URLs are surfaced.
- Added CPU Canny-style preprocessing for preview/control-image preparation because the pinned public C API does not export `sd_preprocess_canny`.

## Executed evidence

- Source ZIP integrity: PASS.
- Wrapper JAR integrity and `GradleWrapperMain`: PASS.
- `./gradlew --version`: wrapper starts, then is BLOCKED by sandbox `Network is unreachable` while downloading Gradle 8.9.
- Local Android SDK/NDK/CMake build: BLOCKED because the Android toolchain is not installed.

## CI gate

The V15.4 workflow runs Java/resource compilation, lint, JUnit, ARM64 native compilation, APK assembly, ELF/JNI/Vulkan checks, and uploads logs with `if: always()`. APK upload is allowed only after all gates pass.
