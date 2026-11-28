# LocalCanvas V16.1 Development Status

## Current baseline

- Branch: `fix/v16.1-core`
- Source baseline: `4993840` (`feat: connect V16.1 LoRA install and ControlNet routing`)
- Engine baseline: stable-diffusion.cpp `dee497f4df67786a24a30b46176ebc188f247192`
- Working environment: project-local Android SDK, NDK `27.2.12479018`, CMake `3.22.1`, Gradle `8.9`

## Completed in this session

- Installed the required Android SDK packages in `.android-sdk/`.
- Added a project-local `LOCALCANVAS_ENABLE_VULKAN` CMake/Gradle switch, defaulting to ON.
- Built the real stable-diffusion.cpp ARM64 CPU path with `requireSdCpp=ON`.
- Packaged an engine-included debug APK.
- Verified APK ARM64 ELF architecture and JNI symbols.
- Confirmed no ADB device is connected; physical-device inference remains unverified.

## Resume point

1. Install/use `glslc` and run the Vulkan build with `-PenableVulkan=ON`.
2. Run full tests/lint against the engine-enabled configuration.
3. If a device is connected, install the APK and verify model installation plus one SD 1.5 generation.
4. Update the reports with the resulting logs and commit only source/report changes.

