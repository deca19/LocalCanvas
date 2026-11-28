# LocalCanvas V15.3 Regression Report

- 모든 문서 및 빌드 설정의 버전을 `153` / `1.5.2-v15.3`로 통일해 버전 불일치를 제거했습니다.
- CI 워크플로(`.github/workflows/build-apk.yml`)는 Wrapper가 없는 상태에서 `./gradlew`를 호출하는 순환 오류 없이 공식 `gradle` 바이너리로 Wrapper를 복구·검증한 후 빌드를 진행합니다.
- `GenerationQueue`는 작업 종료/취소 시 캐시 내 `tmp_*` 임시 파일을 삭제해 저장공간 누적을 방지합니다.
- `NativeEngine.nativeGenerateEx`와 `localcanvas_jni.cpp` 간 13개 JNI 메서드 시그니처가 1:1로 일치합니다.
- `StorageAndStudioTest`를 추가해 대기열 일시정지/순서변경/취소 및 임시 파일 정리 회귀 테스트를 보강했습니다.
