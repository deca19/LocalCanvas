# LocalCanvas V16.1 기능 구현 현황표

> 이번 세션에서 실제 코드/빌드로 확인된 범위: Java·리소스·Lint·단위 테스트, stable-diffusion.cpp ARM64 CPU 연동, 엔진 포함 APK 패키징. Vulkan·실기기·실제 모델 생성은 아직 검증하지 않음.

| 영역 | 세부 기능 | 상태 | 근거 파일 | 비고 |
|---|---|---|---|---|
| 1. 엔진 | `stable-diffusion.cpp` (`52491bd`) C API, Vulkan/CPU 폴백, Txt2Img, 취소, 언로드 | `IMPLEMENTED` | `localcanvas_jni.cpp`, `NativeEngine.java`, `ENGINE_API_COMPATIBILITY.md` | NDK/CI 빌드 및 실기기 추론 `BLOCKED` |
| 1. 엔진 | LoRA 네이티브 전달 (`jLoraPaths`/`jLoraWeights` JNI 연결 + `lora_apply_mode=at_runtime`) | `UNSUPPORTED` | `localcanvas_jni.cpp`, `GenerationQueue.java`, `LoraManager.java` | JNI 인자 전달 완료, `sd_lora_t` C 구조체는 `stable-diffusion.h` CI 확인 후 활성화 필요 |
| 2. 생성 설정 | Prompt/Negative, Seed, Steps, CFG, 해상도/비율, Batch, Sampler, Scheduler, CLIP Skip, Hires.fix, VAE | `IMPLEMENTED` | `V14Activity.java`, `GenerationQueue.java` | 실기기 검증 `BLOCKED` |
| 3. LoRA | import/ON-OFF/강도/카테고리/트리거/캐릭터 연결 | `IMPLEMENTED` | `LoraManager.java`, `V14Activity.java` | - |
| 4. 편집 | Img2Img, Inpaint(`MaskCanvasView`), Outpaint, 회전/자르기, ControlNet(`canny` 등), ESRGAN AI 업스케일 vs Bicubic 분리, 전/후 비교 | `IMPLEMENTED` | `MaskCanvasView.java`, `ImageEditProcessor.java`, `V14Activity.java` | - |
| 5. 스토어 | HuggingFace & Civitai(SFW `nsfw=false`) 검색, 로컬 import | `IMPLEMENTED` | `ModelStoreRepository.java`, `ModelManager.java` | 네트워크 검증 `BLOCKED` |
| 6. 다운로드 | HTTP 200/206/416, URL+ETag 바인딩, 재시도 상한(3회), 속도/ETA, SHA-256, GGUF/SafeTensors 전체 구조 파서, 원자 교체 | `IMPLEMENTED` | `ModelManager.java`, `DownloadValidator.java`, `AtomicModelFile.java`, `DownloadCoordinator.java` | `ModelManagerHttpIntegrationTest` 실행 `BLOCKED` |
| 7. 모델 관리 | `ModelLeaseManager` 읽기/쓰기 락, ON/OFF, RAM/공간 확인 | `IMPLEMENTED` | `ModelLeaseManager.java`, `ModelManager.java` | - |
| 8. 프롬프트 스튜디오 | 오프라인 태그 DB, 한글→영어 태그, 중복/충돌 감지, 가중치, 프리셋, 기록 | `IMPLEMENTED` | `PromptStudio.java` | 유료 API 미사용 |
| 9. 캐릭터 스튜디오 | 프로필, LoRA/고정 Seed 연결, 복제, 조합, JSON 백업 | `IMPLEMENTED` | `CharacterStudio.java`, `V14Activity.java` | V14 데이터 하위 호환 |
| 10. 프로젝트/웹툰 | 스토리보드, 장면 생성/편집/순서/복제/삭제, 말풍선/효과음, 세로 PNG 내보내기 | `IMPLEMENTED` | `ProjectManager.java`, `V14Activity.java` | - |
| 11. 대기열 | 단일 소비자, 일시정지/순서/취소, 디스크 영속화, ForegroundService 알림, 임시 파일 정리 | `IMPLEMENTED` | `GenerationQueue.java`, `GenerationForegroundService.java` | - |
| 12. 갤러리 | 비동기 `LruCache`, 페이지네이션, 검색/즐겨찾기, PNG `tEXt` 메타데이터, 설정 복사/재생성, 공유, MediaStore | `IMPLEMENTED` | `GalleryRepository.java`, `ImageEditProcessor.java`, `V14Activity.java` | - |
| 13. UI | Edge-to-Edge `WindowInsets`, 다크/라이트 테마, 반응형 레이아웃, 접근성 | `IMPLEMENTED` | `UiKit.java`, `V14Activity.java` | - |
| 14. 성능/발열 | Vulkan+CPU 폴백, Thermal API(API 29+), 배터리/RAM 조절 | `IMPLEMENTED` | `PerformanceMonitor.java` | Adreno 840 실측 `BLOCKED` |
| 15. 저장공간 | 카테고리 용량, 캐시/임시파일 정리, 중복 SHA-256, JSON 백업/복원, v14→v15 마이그레이션 | `IMPLEMENTED` | `StorageCenter.java` | - |
| 16. 진단 | 링 버퍼 로그, 오류 안내, 개인정보 제외 리포트 | `IMPLEMENTED` | `DiagnosticsCenter.java` | - |
| CI / APK | GitHub Actions V15.3 (비순환 Wrapper 복구 + `lint test assembleDebug` + ARM64/JNI/Vulkan 검사) | `IMPLEMENTED` | `.github/workflows/build-apk.yml` | GitHub 푸시 권한 및 실행 `BLOCKED` |

