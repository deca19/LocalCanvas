# LocalCanvas V16.1 Bug Tracker

| Bug ID | 제목 | 심각도 | 발견 버전 | 관련 파일 | 근본 원인 | 수정 내용 | 테스트 방법 | 현재 상태 |
|---|---|---|---|---|---|---|---|---|
| LC-1511 | `GradleWrapperMain` 누락 | P0 | V15.0 | `.github/workflows/build-apk.yml`, `gradlew.bat` | 바이너리 JAR 미포함 | CI에서 공식 Gradle 8.9로 Wrapper 복구 및 `unzip -t` 검증 | CI `./gradlew --version` | `FIXED_UNVERIFIED` (CI `BLOCKED`) |
| LC-1531 | LoRA JNI 로그 전용 처리 | P1 | V15.2 | `localcanvas_jni.cpp`, `V14Activity.java` | `sd_lora_t` 구조체 미검증 상태에서 경로만 전달 후 로그만 출력 | `#ifndef LOCALCANVAS_ENABLE_NATIVE_LORA` 시 명시적 오류 반환, UI에 트리거 워드 전용 안내 | 정적/CI/실기기 | `FIXED_UNVERIFIED` |
| LC-1532 | 메인 스레드 PNG 압축 ANR 위험 | P1 | V15.2 | `V14Activity.java` (`enqueueGeneration`) | UI 스레드에서 `saveTempBitmap` 실행 | `TaskRuntime.fileExecutor()`로 오프로딩 | 실기기 프로파일링 | `FIXED_UNVERIFIED` |
| LC-1533 | 웹툰 내보내기 OOM 위험 및 예외 무시 | P1 | V15.2 | `ProjectManager.java`, `V14Activity.java` | `totalHeight` 상한 부재 | `MAX_STRIP_HEIGHT_PX=16384` 상한 및 패널 즉시 `recycle()` | 실기기/단위 테스트 | `FIXED_UNVERIFIED` |
| LC-1534 | 백업 복원 시 설정/프롬프트 누락 | P1 | V15.2 | `StorageCenter.java` | `restoreLatestBackup`이 캐릭터만 복원 | `settings`, `prompts`, `characters` 모두 타입 안전 복원 | 실기기 백업/복원 | `FIXED_UNVERIFIED` |
| LC-1535 | 앱 재시작 시 대기열 복구 미호출 | P1 | V15.2 | `V14Activity.java`, `GenerationQueue.java` | `restoreFromDisk` 호출 누락 | `onCreate`에서 `restoredOnce` 가드로 1회 복구 호출 | 앱 재시작 테스트 | `FIXED_UNVERIFIED` |
| LC-1536 | SafeTensors 중복 JSON 키 허용 | P2 | V15.2 | `DownloadValidator.java` | `JsonParser.parseObject`가 중복 키 덮어씀 | `containsKey` 검사로 중복 키 즉시 거부 | `DownloadValidatorTest.rejectsDuplicateKeysInSafeTensorsHeader` | `FIXED_UNVERIFIED` (JUnit `BLOCKED`) |
| LC-1537 | 로컬 빌드/실기기 환경 부재 | P0 | V14~V15.3 | 전체 프로젝트 | 샌드박스 VM에 `javac`/SDK/NDK/Gradle/실기기 없음 | GitHub Actions 워크플로 및 실기기 체크리스트 제공 | GitHub Actions + Galaxy S26 Ultra | `BLOCKED` |
# Active V16.1 environment findings

- `LOCAL-ENV-001` — Windows local Vulkan build is blocked by missing `glslc`; use the existing CI Linux Vulkan toolchain or install an official Vulkan shader compiler before claiming Vulkan verification.
- `DEVICE-001` — No ADB device is connected; physical install and inference remain pending.


