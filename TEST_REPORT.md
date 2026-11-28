# LocalCanvas V16.1 Test Report

## 2026-11-28 execution

- Gradle unit tests: PASS as part of `testDebugUnitTest`.
- Android lint: PASS (`lintDebug`).
- Resource/Java compilation: PASS.
- Engine-enabled CPU native compilation: PASS.
- APK packaging: PASS.
- APK archive/ARM64/JNI inspection: PASS.
- Model download and real image generation: NOT RUN; no Android device and no model were available.
- Vulkan runtime and Galaxy S26 Ultra checks: NOT RUN.

| 테스트 클래스 | 검증 항목 | 상태 | 비고 |
|---|---|---|---|
| `DownloadValidatorTest` | Content-Range, GGUF v1-3 정상/손상, SafeTensors 경계/shape/중첩 `data_offsets` 거부 | 작성됨 / 실행 `BLOCKED` | JUnit 실행 환경 필요 |
| `ModelManagerAtomicReplaceTest` | 원자 교체, 누락 시 기존 모델 보존 | 작성됨 / 실행 `BLOCKED` | - |
| `ModelManagerHttpIntegrationTest` | `ServerSocket` 기반 HTTP 200, 206 재개, 416 재시작, ETag 변경 재시작, SHA-256 불일치 거부 | 작성됨 / 실행 `BLOCKED` | - |
| `GenerationQueueTest` | 순차 소비, `ModelLeaseManager` 읽기/쓰기 락 경합 차단 | 작성됨 / 실행 `BLOCKED` | - |
| `StorageAndStudioTest` | 임시 파일 정리, 대기열 일시정지/순서변경/취소 | 작성됨 / 실행 `BLOCKED` | - |
| ZIP/Wrapper 무결성 | ZIP 및 `gradle-wrapper.jar` 검사 | `PASS` | `unzip -t` 실행 완료 |
| Gradle 실행 | Wrapper 시작 | `BLOCKED` | Gradle 8.9 다운로드에서 `Network is unreachable` |
| Java/리소스/C++/JNI/Unit/Lint | 실제 실행 | `BLOCKED` | 로컬 Android toolchain 미설치 |
| APK/SD1.5/Vulkan/LoRA | 실제 런타임 | `BLOCKED` | 설치 APK 및 Galaxy S26 Ultra 필요 |

