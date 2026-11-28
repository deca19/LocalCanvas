# Changelog

## V15.4 (versionCode 154)

- 복구된 공식 Gradle Wrapper JAR 및 Wrapper 무결성 검사.
- 유효한 stable-diffusion.cpp 커밋 고정.
- LoRA를 `sd_opt_set_loras`로 실제 생성 옵션에 연결.
- 존재하지 않는 Canny C API 호출 제거 및 명시적 미지원 처리.
- `unitTests.returnDefaultValues` 제거.

## V15.3 (legacy)
- **엔진 API 명세 및 JNI LoRA 전달**: `ENGINE_API_COMPATIBILITY.md` 작성, `NativeEngine.nativeGenerateEx`·`GenerationQueue.Job`·`localcanvas_jni.cpp`에 `loraPaths`/`loraWeights` 인자 연결
- **리소스·데이터 보호**: `GenerationQueue` 작업 종료/취소 시 `tmp_*` 임시 이미지 자동 삭제, 캐릭터 선택 시 `fixedSeed` 및 `linkedLoraId` 자동 적용
- **테스트·빌드·문서 일관성**: `StorageAndStudioTest` 추가, 전체 소스 및 보고서 버전 `153` / `1.5.2-v15.3` 통일, `.github/workflows/build-apk.yml` 비순환 Gradle Wrapper 복구 검증 강화
## V16.0

- Bumped application version to 1.6.0 / code 160.
- Updated the CI workflow to build the current V16 source archive instead of the previous V15.4 archive.
- Fixed Hugging Face model-store results so repository pages are not offered as downloadable model files.
- Added direct-file discovery only for an unambiguous, large root-level checkpoint file; other repositories remain manual-import entries.
- Updated model-store and LoRA status text to distinguish connected code paths from unverified device-level results.

# V16.1 session updates

- Added a configurable Vulkan build flag while preserving Vulkan as the default.
- Verified the real stable-diffusion.cpp ARM64 CPU integration and produced an engine-included APK.
- Added reproducible development status and local-build evidence.


