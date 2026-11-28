# LocalCanvas V16.1 Development Status

## Current baseline

- Branch: `fix/v16.1-core`
- Latest source commit: `2c53dff16d0c90c55df6f5fdf37914976abbaab3`
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
- Added explicit Canny/Lineart/Scribble/SoftEdge/Depth control-image preview routing; OpenPose is rejected without a pose model.
- Confirmed GitHub Actions run `46129902303` passed the Vulkan link/inspection gate.
- Confirmed Actions run `46133822927` built commit `252f251014192ea6c348b03f3349a5993347a25b` with Vulkan checks and APK SHA-256 `f19e127bc8f13d24753bc46e28d0b4bcc67bea2773b6782ff8deabca32cecc39`.
- Confirmed no ADB device is connected; physical-device inference remains unverified.

## Resume point

1. Finish Actions run `46134359181` for the newer control-preview commit `2c53dff16d0c90c55df6f5fdf37914976abbaab3`.
2. If a device is connected, install the APK and verify model installation plus one SD 1.5 generation with Vulkan and CPU fallback.
3. Continue wiring only engine-supported reference, ControlNet, and multi-character paths; do not label prompt concatenation as independent regional control.
4. Keep runtime/model/device evidence separate from compile-time evidence in the reports.
