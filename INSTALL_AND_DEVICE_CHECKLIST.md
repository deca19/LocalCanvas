# LocalCanvas V15.3 빌드 · 설치 · 실기기 검증 가이드

## 1. GitHub 푸시 및 APK 빌드 (GitHub Actions)
1. `LocalCanvas-v15.3-source.zip`을 `https://github.com/deca19/LocalCanvas` 로컬 클론에 압축 해제합니다.
2. 로컬에 Gradle 8.9가 있다면 `gradle wrapper --gradle-version 8.9`를 실행하고, 없더라도 GitHub Actions가 공식 `gradle/actions/setup-gradle@v4`로 Wrapper를 자동 복구합니다.
3. `main` 또는 `v15-dev` 브랜치에 푸시합니다:
   ```bash
   git add .
   git commit -m "LocalCanvas V15.3: build stabilization, JNI LoRA args, and API matrix"
   git push origin main
   ```
4. GitHub **Actions → Build LocalCanvas V15.3 APK** 완료 후 `localcanvas-v15.3-native-arm64-debug` 아티팩트에서 APK를 다운로드합니다.

## 2. Galaxy S26 Ultra 실기기 검증 체크리스트 (GATE 6~11)
- [ ] 홈 화면에서 `engine=stable-diffusion.cpp; vulkan=compiled; devices=...Adreno...` 표시 확인
- [ ] SD 1.5 모델 ON 다운로드(진행률/속도/ETA, 일시정지·재개, GGUF/SafeTensors 검증)
- [ ] Text-to-Image 생성 (`512x512`, `euler`, `20 steps`) → 실제 PNG 저장 및 갤러리 메타데이터 확인
- [ ] Vulkan GPU 추론 및 연속 생성 시 발열/RAM 스로틀링 확인
- [ ] 생성 중 화면 회전, 백그라운드 알림, 모델 OFF 보호 확인
- [ ] 편집(Inpaint/Outpaint/Canny/ESRGAN) 및 웹툰 세로 PNG 내보내기 확인
