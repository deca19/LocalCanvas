# LocalCanvas V15.3 — 5-Stage Independent Code Audit Report

## AUDIT 1: 빌드 감사 (Build & Dependency)
- **Gradle Wrapper**: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.9, SHA-256 고정). `gradle-wrapper.jar`는 바이너리 직접 생성 금지 원칙에 따라 `.github/workflows/build-apk.yml`에서 공식 `gradle/actions/setup-gradle@v4`로 복구 후 `unzip -t` 및 `./gradlew --version` 검증.
- **SDK/NDK/CMake**: `compileSdk 35`, `minSdk 29`, `targetSdk 35`, `ndkVersion 27.2.12479018`, `CMake 3.22.1`, `arm64-v8a`.
- **차단 요소**: 로컬 샌드박스 VM에 `javac`/Android SDK/NDK/Gradle 부재 → 실제 컴파일은 `BLOCKED`.

## AUDIT 2: 실행 및 엔진 연동 감사
- `localcanvas_jni.cpp`는 `stable-diffusion.cpp` (`52491bd`)의 `sd_ctx_opt_create`, `sd_img_gen_opt_create`, `sd_upscaler_opt_create`, `sd_preprocess_canny`만 사용하며 존재하지 않는 API를 호출하지 않음.
- **V15.3 수정**: LoRA 경로 전달 후 로그만 남기던 코드를 제거해 `#ifndef LOCALCANVAS_ENABLE_NATIVE_LORA` 시 명시적 오류를 반환하도록 수정하고, UI에서는 트리거 워드만 적용됨을 명확히 안내.

## AUDIT 3: 안정성 · 동시성 · 생명주기 감사
- **동시성**: `ModelLeaseManager` 읽기/쓰기 락 + `GenerationQueue` 단일 소비자 큐로 다운로드/삭제/추론 경합 차단.
- **ANR/OOM 방지**: `V14Activity.enqueueGeneration`의 `saveTempBitmap` PNG 압축을 `TaskRuntime.fileExecutor()`로 오프로딩. `ProjectManager.exportWebtoonStrip`에 `MAX_STRIP_HEIGHT_PX=16384` 상한 및 패널 비트맵 즉시 해제 추가.
- **데이터 복구**: `GenerationQueue.restoreFromDisk`를 앱 시작 시 1회 실행하도록 연결(`restoredOnce` 가드). `StorageCenter.restoreLatestBackup`이 `settings`, `prompts`, `characters`를 모두 복원하도록 수정.
- **파일 검증**: `DownloadValidator.JsonParser`에서 SafeTensors 중복 JSON 키를 거부하도록 보강.

## AUDIT 4: 기능 및 가짜 구현 감사
- 전수 스캔 결과 더미 이미지, 가짜 진행률, 무조건 `true` 반환 스텁 없음.
- 소프트웨어 2x 확대(`Bicubic`)와 실제 AI 업스케일(`NativeEngine.nativeUpscale` ESRGAN)을 분리.

## AUDIT 5: 결과물 일치 감사
- `app/build.gradle`, `.github/workflows/build-apk.yml`, `V14Activity.java`, `DiagnosticsCenter.java`, 모든 보고서가 `versionCode 153` / `1.5.3-v15.3`으로 일치.
- Java 29개 파일 괄호/패키지 검사, XML 파싱 검사, 13개 JNI 심볼 1:1 매칭 검사 통과.
