# LocalCanvas V15.4 — stable-diffusion.cpp API Compatibility Matrix

대상 엔진: `leejet/stable-diffusion.cpp`  
고정 커밋: `dee497f4df67786a24a30b46176ebc188f247192`  
참고 문서: `docs/guides/c_api.md` (Opaque Options / Tensor / Result C API)

| 기능 영역 | `stable-diffusion.cpp` C API 심볼 / 옵션 키 | JNI 구현 상태 | 비고 / 검증 상태 |
|---|---|---|---|
| 런타임/장치 탐지 | `sd_set_log_callback`, `sd_list_devices`, `sd_get_last_error` | `IMPLEMENTED` (`localcanvas_jni.cpp`) | CI NDK 컴파일 및 실기기 `BLOCKED` |
| 컨텍스트 생성/해제 | `sd_ctx_opt_create`, `sd_ctx_create`, `sd_ctx_free`, `sd_dgl_ctx_*` | `IMPLEMENTED` | 복합 캐시 키(모델+VAE+CN+IPA+백엔드+FA+MMap+Tiling) 적용 |
| 컨텍스트 옵션 | `model_path`, `vae_path`, `control_net_path`, `ip_adapter_path`, `n_threads`, `backend`, `params_backend`, `flash_attn`, `diffusion_flash_attn`, `enable_mmap`, `vae_conv_direct`, `lora_apply_mode` | `IMPLEMENTED` | `backend="vulkan"` 실패 시 `"cpu"` 폴백 지원 |
| Text-to-Image | `sd_img_gen_opt_create`, `sd_generate_image`, `prompt`, `negative_prompt`, `width`, `height`, `seed`, `batch_count`, `clip_skip`, `sample.steps`, `sample.guidance.txt_cfg`, `sample.method`, `sample.scheduler` | `IMPLEMENTED` | 실기기 SD 1.5 생성 `BLOCKED` |
| Img2Img / Inpaint | `init_image` (`SD_TENSOR_U8` `[3,w,h]`), `mask_image`, `strength` | `IMPLEMENTED` | `bitmapToRgbTensor` 변환 |
| ControlNet | `control_image`, `control_type`, `control_strength` | `IMPLEMENTED` | C API 옵션 전달. Canny 전처리 JNI는 현재 C API에 심볼이 없어 `UNSUPPORTED` |
| IP-Adapter | `ip_adapter_image`, `ip_adapter_strength` | `IMPLEMENTED` | - |
| Hires.fix / VAE Tiling | `hires.enabled`, `hires.scale`, `hires.steps`, `hires.denoising_strength`, `hires.upscaler`, `vae_tiling.enabled` | `IMPLEMENTED` | - |
| AI 업스케일러 (ESRGAN) | `sd_upscaler_opt_create`, `sd_upscaler_create`, `sd_upscale_opt_create`, `sd_upscale`, `sd_upscaler_free` | `IMPLEMENTED` (`nativeUpscale`) | ESRGAN 모델 파일 필요 |
| 진행률 / 취소 / 결과 | `sd_set_generation_progress_callback`, `sd_cancel_generation`, `sd_result_get_image_count`, `sd_result_get_image`, `sd_result_get_generation_info`, `sd_result_free` | `IMPLEMENTED` | - |
| LoRA 네이티브 구조체 | `sd_opt_set_loras` (`sd_lora_t`) | `IMPLEMENTED` | 고정 커밋 공식 헤더의 `is_high_noise`, `multiplier`, `path` 필드로 생성 옵션에 실제 전달. 실기기 결과 비교는 별도 검증 필요 |
