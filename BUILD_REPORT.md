# LocalCanvas V15.4 Build Report

## Changes

- `versionCode 154`, `versionName 1.5.4`.
- Verified Gradle wrapper JAR restored; `unzip -t` passed.
- Removed `unitTests.returnDefaultValues`.
- Pinned stable-diffusion.cpp to valid upstream commit `dee497f4df67786a24a30b46176ebc188f247192`.
- Connected LoRA entries to the official `sd_opt_set_loras` API.
- Marked Canny preprocessing unsupported because the pinned public C API does not export `sd_preprocess_canny`.

## Executed evidence

- Source ZIP integrity: PASS.
- Wrapper JAR integrity and `GradleWrapperMain`: PASS.
- `./gradlew --version`: wrapper starts, then is BLOCKED by sandbox `Network is unreachable` while downloading Gradle 8.9.
- Local Android SDK/NDK/CMake build: BLOCKED because the Android toolchain is not installed.

## CI gate

The V15.4 workflow runs Java/resource compilation, lint, JUnit, ARM64 native compilation, APK assembly, ELF/JNI/Vulkan checks, and uploads logs with `if: always()`. APK upload is allowed only after all gates pass.
