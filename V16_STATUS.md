# LocalCanvas V16.0 status

## Verified in this workspace

- The V16 source archive has been restored into the checked-out Android project tree on the `v16-development` branch.
- The CI workflow now builds the checked-out source tree directly instead of replacing it with the archived ZIP during the build.
- The workflow trigger now includes application, Gradle, wrapper, and build-configuration changes.
- V16.0 versionCode/versionName are set to 160/1.6.0.
- The existing V15.4 Gradle wrapper, NDK/CMake configuration, JNI bridge and pinned stable-diffusion.cpp build setup are preserved.
- Hugging Face search no longer treats a repository HTML page as a model download. A direct URL is exposed only when the API reports exactly one plausible, sufficiently large root-level `.safetensors`, `.gguf`, or `.ckpt` file.
- Civitai SFW results continue to use the API-provided file URL when present.
- Models without a verified automatic URL are explicitly kept as local-import/manual-install entries.

## Not verified here

- Android/NDK build in this workspace is blocked because the Gradle distribution download is network-unreachable.
- The remote GitHub branch could not be created from this environment because the connected GitHub integration returned HTTP 403 (`Resource not accessible by integration`), and the local HTTPS remote has no credentials.
- GitHub Actions V16 build, APK creation, and artifact download are not yet verified for this source snapshot.
- Actual SD 1.5 image generation, Vulkan execution, LoRA visual effect, thermal behavior, and S26 Ultra runtime behavior require a device or a successful CI APK build.
- Advanced features such as ControlNet, IP-Adapter, Hires.fix, and AI upscaling remain subject to the native engine API and model availability; they are not marked complete by UI presence alone.

## Safety rule

No unverified URL, fake image, or synthetic progress result is used as evidence of support.
