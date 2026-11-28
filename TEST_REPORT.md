# LocalCanvas V16.1 Test Report

## 2026-11-28 execution

- Gradle unit tests: PASS as part of `testDebugUnitTest`.
- Android lint: PASS (`lintDebug`).
- Resource/Java compilation: PASS.
- Engine-enabled CPU native compilation: PASS.
- APK packaging: PASS.
- APK archive/ARM64/JNI inspection: PASS.
- Incremental Java compilation after model-store/Canny/UI changes: PASS.
- GitHub Actions V16.1 Vulkan link/inspection gate: PASS (`46129902303`).
- Model download and real image generation: NOT RUN; no Android device and no model were available.
- Vulkan runtime and Galaxy S26 Ultra checks: NOT RUN.

| 테스트 클래스 | 검증 항목 | 상태 | 비고 |
|---|---|---|---|
| `DownloadValidatorTest` | Content-Range, GGUF v1-3 정상/손상, SafeTensors 경계/shape/중첩 `data_offsets` 거부 | PASS | `testDebugUnitTest` |
| `ModelManagerAtomicReplaceTest` | 원자 교체, 누락 시 기존 모델 보존 | PASS | `testDebugUnitTest` |
| `ModelManagerHttpIntegrationTest` | `ServerSocket` 기반 HTTP 200, 206 재개, 416 재시작, ETag 변경 재시작, SHA-256 불일치 거부 | PASS | `testDebugUnitTest` |
| `GenerationQueueTest` | 순차 소비, `ModelLeaseManager` 읽기/쓰기 락 경합 차단 | PASS | `testDebugUnitTest` |
| `StorageAndStudioTest` | 임시 파일 정리, 대기열 일시정지/순서변경/취소 | PASS | `testDebugUnitTest` |
| ZIP/Wrapper 무결성 | ZIP 및 `gradle-wrapper.jar` 검사 | `PASS` | `unzip -t` 실행 완료 |
| Gradle 실행 | Wrapper 시작 | `PASS` | 프로젝트 로컬 Gradle 8.9 |
| Java/리소스/C++/JNI/Unit/Lint | 실제 실행 | `PASS` | 엔진 포함 로컬 검증 완료; Vulkan runtime은 별도 |
| APK/SD1.5/Vulkan/LoRA | 실제 런타임 | `BLOCKED` | 설치 APK 및 Galaxy S26 Ultra 필요 |
