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
- Expanded model-store results to expose every eligible Hugging Face file and every Civitai version/file with a direct HTTPS download URL.
- Replaced the unavailable native Canny preprocessing symbol with an in-app CPU Canny-style preview path.
- Applied the V16.1 navy/lavender UI tokens, 48dp touch targets, input/card surfaces, and accessibility labels.
- Confirmed GitHub Actions run `46129902303` passed the Vulkan link/inspection gate.
- Confirmed no ADB device is connected; physical-device inference remains unverified.

## Resume point

1. If needed, install/use `glslc` locally and compare a Windows Vulkan build; CI already passed the Vulkan gate.
2. If a device is connected, install the APK and verify model installation plus one SD 1.5 generation with Vulkan and CPU fallback.
3. Continue wiring only engine-supported reference, ControlNet, and multi-character paths; do not label prompt concatenation as independent regional control.
4. Keep runtime/model/device evidence separate from compile-time evidence in the reports.
