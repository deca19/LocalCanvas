package com.localcanvas.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * LocalCanvas V16.0 Main Activity. The class name is retained for data and
 * manifest compatibility with existing installations.
 * Lifecycle-safe: observes process-scoped GenerationQueue and DownloadCoordinator in onStart/onStop.
 */
public final class V14Activity extends Activity {
    private static final int REQ_IMPORT_MODEL = 81;
    private static final int REQ_IMPORT_LORA = 82;
    private static final int REQ_IMPORT_VAE = 83;
    private static final int REQ_PICK_EDIT_IMAGE = 84;
    private static final int REQ_IMPORT_UPSCALER = 85;

    private static final String[] SAMPLERS = {
            "euler", "euler_a", "heun", "dpm2", "dpm++2s_a", "dpm++2m", "dpm++2mv2", "ddim_trailing", "lcm"
    };
    private static final String[] SCHEDULERS = {
            "auto", "discrete", "karras", "exponential", "sgm_uniform", "simple"
    };

    private UiKit ui;
    private LinearLayout content;
    private ModelManager models;
    private LoraManager loras;
    private PromptStudio prompts;
    private CharacterStudio characters;
    private ProjectManager projects;
    private StorageCenter storage;
    private GalleryRepository galleryRepo;
    private ModelStoreRepository storeRepo;
    private GenerationQueue generationQueue;
    private SharedPreferences prefs;

    private String selectedModel;
    private String selectedVae = "";
    private String selectedSampler = "euler";
    private String selectedScheduler = "auto";
    private String currentTab = "home";
    private int galleryPage = 0;
    private boolean galleryFavoritesOnly = false;

    // Generate screen live refs
    private EditText promptInput, negativeInput, widthInput, heightInput, stepsInput, cfgInput, seedInput, batchInput, clipSkipInput;
    private TextView genStatusView;
    private ProgressBar genProgressBar;
    private ImageView genPreviewView;
    private Button generateBtn, cancelBtn;

    // Edit screen state
    private Bitmap editSourceBitmap;
    private Bitmap editCompareBitmap;
    private Bitmap editControlPreviewBitmap;
    private boolean showCompareAfter = true;
    private String editControlType = "none";
    private float editDenoise = 0.65f;

    // Webtoon scene generation target
    private File activeWebtoonProject;
    private int activeWebtoonSceneIndex = -1;

    // Store search results cache
    private List<ModelStoreRepository.StoreItem> lastStoreResults = new ArrayList<>();
    private String storeFilter = "local";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressPoller = new Runnable() {
        @Override public void run() {
            if (NativeEngine.isGeneratingSafe() && genProgressBar != null) {
                int p = NativeEngine.nativeProgress();
                genProgressBar.setVisibility(View.VISIBLE);
                genProgressBar.setProgress(p);
                if (genStatusView != null) genStatusView.setText("생성 중... " + p + "%");
                mainHandler.postDelayed(this, 350);
            }
        }
    };

    private final GenerationQueue.QueueObserver queueObserver = new GenerationQueue.QueueObserver() {
        @Override public void onQueueChanged() {
            runOnUiThread(() -> {
                if ("queue".equals(currentTab)) showQueueScreen();
                if (genStatusView != null && generationQueue.currentJob() != null) {
                    genStatusView.setText("생성 중 · 대기 " + generationQueue.pendingCount() + "건");
                }
            });
        }

        @Override public void onJobCompleted(GenerationQueue.Job job, Bitmap[] images, String genInfo) {
            TaskRuntime.fileExecutor().submit(() -> {
                File firstSaved = null;
                int savedCount = 0;
                for (int i = 0; i < images.length; i++) {
                    File saved = saveGeneratedPng(job, images[i], genInfo, i);
                    if (saved != null) {
                        if (firstSaved == null) firstSaved = saved;
                        savedCount++;
                    }
                }
                if (firstSaved != null && activeWebtoonProject != null && activeWebtoonSceneIndex >= 0) {
                    try {
                        List<ProjectManager.Scene> scenes = projects.loadScenes(activeWebtoonProject);
                        if (activeWebtoonSceneIndex < scenes.size()) {
                            scenes.get(activeWebtoonSceneIndex).imagePath = firstSaved.getAbsolutePath();
                            projects.saveScenes(activeWebtoonProject, scenes);
                        }
                    } catch (Exception ignored) {}
                    activeWebtoonProject = null;
                    activeWebtoonSceneIndex = -1;
                }
                final int saved = savedCount;
                DiagnosticsCenter.log("GEN", "완료: " + saved + "장 (" + job.width + "x" + job.height + ")");
                runOnUiThread(() -> {
                    if (images.length > 0) editCompareBitmap = images[0];
                    if (genPreviewView != null && images.length > 0) genPreviewView.setImageBitmap(images[0]);
                    if (genStatusView != null) genStatusView.setText("완료 · " + saved + "장 갤러리 저장됨");
                    if (genProgressBar != null) genProgressBar.setVisibility(View.GONE);
                    if (generateBtn != null) generateBtn.setEnabled(true);
                    if (cancelBtn != null) cancelBtn.setVisibility(View.GONE);
                    if (generationQueue.pendingCount() == 0) GenerationForegroundService.stop(V14Activity.this);
                });
            });
        }

        @Override public void onJobFailed(GenerationQueue.Job job, String message) {
            DiagnosticsCenter.log("GEN_ERR", message);
            runOnUiThread(() -> {
                if (genStatusView != null) genStatusView.setText("실패: " + message);
                if (genProgressBar != null) genProgressBar.setVisibility(View.GONE);
                if (generateBtn != null) generateBtn.setEnabled(true);
                if (cancelBtn != null) cancelBtn.setVisibility(View.GONE);
                if (generationQueue.pendingCount() == 0) GenerationForegroundService.stop(V14Activity.this);
                Toast.makeText(V14Activity.this, message, Toast.LENGTH_LONG).show();
            });
        }
    };

    private final DownloadCoordinator.Observer downloadObserver = new DownloadCoordinator.Observer() {
        @Override public void onDownloadUpdated(DownloadCoordinator.DownloadStatus st) {
            if (st.error != null) DiagnosticsCenter.log("DL_ERR", st.modelId + ": " + st.error);
        }

        @Override public void onDownloadCompleted(String modelId, File file) {
            DiagnosticsCenter.log("DL", "설치 완료: " + modelId);
            runOnUiThread(() -> {
                selectedModel = modelId;
                prefs.edit().putString("model", selectedModel).apply();
                Toast.makeText(V14Activity.this, "설치 완료: " + file.getName(), Toast.LENGTH_SHORT).show();
                if ("store".equals(currentTab)) showModelStore();
            });
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("v14_settings", MODE_PRIVATE);
        ui = new UiKit(this, prefs.getBoolean("dark_theme", true));
        models = new ModelManager(this);
        loras = new LoraManager(this);
        prompts = new PromptStudio(this);
        characters = new CharacterStudio(this);
        projects = new ProjectManager(this);
        storage = new StorageCenter(this);
        galleryRepo = new GalleryRepository(this);
        storeRepo = new ModelStoreRepository();
        generationQueue = GenerationQueue.shared();
        generationQueue.attachPersistence(new File(getFilesDir(), "queue_pending.json"));
        generationQueue.restoreFromDisk(new GenerationQueue.Listener() {
            public void started(int index, int total) { mainHandler.post(progressPoller); }
            public void completed(Bitmap image, int index) {}
            public void failed(String message, int index) {}
        });

        selectedModel = prefs.getString("model", "sd15");
        selectedVae = prefs.getString("vae", "");
        selectedSampler = prefs.getString("sampler", "euler");
        selectedScheduler = prefs.getString("scheduler", "auto");

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        showHome();
    }

    @Override
    protected void onStart() {
        super.onStart();
        generationQueue.addObserver(queueObserver);
        DownloadCoordinator.shared().addObserver(downloadObserver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacks(progressPoller);
        generationQueue.removeObserver(queueObserver);
        DownloadCoordinator.shared().removeObserver(downloadObserver);
    }

    private void screen(String tabId, String title) {
        currentTab = tabId;
        genStatusView = null; genProgressBar = null; genPreviewView = null; generateBtn = null; cancelBtn = null;
        LinearLayout root = ui.col();
        root.setBackgroundColor(ui.bg);
        ui.applyEdgeToEdge(this, root);

        LinearLayout header = ui.row();
        header.addView(ui.text(title, 20, ui.text), ui.lp(0, ui.dp(54), 1));
        Button qBtn = ui.button("대기열(" + generationQueue.pendingCount() + ")");
        qBtn.setOnClickListener(v -> showQueueScreen());
        header.addView(qBtn, ui.lp(-2, ui.dp(44)));
        Button setBtn = ui.button("⚙");
        setBtn.setOnClickListener(v -> showSettings());
        header.addView(setBtn, ui.lp(ui.dp(46), ui.dp(44)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = ui.col();
        content.setPadding(0, 0, 0, ui.dp(12));
        scroll.addView(content);
        root.addView(scroll, ui.lp(-1, 0, 1));

        LinearLayout nav = ui.row();
        String[][] tabs = {{"home", "홈"}, {"gen", "생성"}, {"edit", "편집"}, {"store", "모델"}, {"studio", "스튜디오"}, {"gallery", "갤러리"}};
        for (String[] t : tabs) {
            Button b = t[0].equals(tabId) ? ui.accentButton(t[1]) : ui.button(t[1]);
            b.setTextSize(11);
            nav.addView(b, ui.lp(0, ui.dp(48), 1));
            b.setOnClickListener(v -> {
                if ("home".equals(t[0])) showHome();
                else if ("gen".equals(t[0])) showGenerate();
                else if ("edit".equals(t[0])) showEditScreen();
                else if ("store".equals(t[0])) showModelStore();
                else if ("studio".equals(t[0])) showStudio();
                else { galleryPage = 0; showGallery(); }
            });
        }
        root.addView(nav);
        setContentView(root);
        root.requestApplyInsets();
    }

    private boolean engineReady() {
        return NativeEngine.isNativeLibraryLoaded() && NativeEngine.nativeIsAvailable();
    }

    private void showHome() {
        screen("home", "LocalCanvas V16.0");
        PerformanceMonitor.Snapshot perf = PerformanceMonitor.capture(this);
        ui.card(content, ui.text("엔진 상태\n" + (engineReady() ? "● " + NativeEngine.nativeRuntimeStatus()
                : "○ 네이티브 엔진 미연결 (APK에 stable-diffusion.cpp 필요)"), 13, engineReady() ? ui.good : ui.warn));
        ui.card(content, ui.text("선택 모델: " + selectedModel
                + "\n활성 LoRA: " + loras.activeLoras().size() + "개"
                + "\nRAM: " + perf.availRamMb + "/" + perf.totalRamMb + " MB · 발열: " + perf.thermalLabel
                + (perf.batteryTempC > 0 ? " (" + perf.batteryTempC + "°C)" : ""), 13, ui.text));
        ui.card(content, ui.text(storage.report(), 13, ui.muted));

        Button go = ui.accentButton("＋ 이미지 생성 시작");
        go.setOnClickListener(v -> showGenerate());
        ui.card(content, go);
    }

    private void showGenerate() {
        screen("gen", "이미지 생성");
        LinearLayout form = ui.col();
        form.addView(ui.text("모델: " + selectedModel + (selectedVae.isEmpty() ? "" : " · VAE: " + selectedVae), 14, ui.text));

        genPreviewView = new ImageView(this);
        genPreviewView.setAdjustViewBounds(true);
        genPreviewView.setContentDescription("생성된 이미지 미리보기");
        form.addView(genPreviewView, ui.lp(-1, -2));
        genPreviewView.setMinimumHeight(ui.dp(180));
        promptInput = ui.input("프롬프트 (한글 또는 영어 태그)", prefs.getString("prompt", ""));
        negativeInput = ui.input("네거티브 프롬프트", prefs.getString("negative", "lowres, bad anatomy, blurry"));
        ui.field(form, "프롬프트", promptInput);
        ui.field(form, "네거티브 프롬프트", negativeInput);
        watch(promptInput, "prompt");
        watch(negativeInput, "negative");
        final LinearLayout suggestionsRow = ui.col();
        form.addView(suggestionsRow);
        promptInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence t, int start, int count, int after) {}
            public void onTextChanged(CharSequence t, int start, int before, int count) {
                suggestionsRow.removeAllViews();
                String raw = t.toString();
                int comma = raw.lastIndexOf(',');
                String prefix = raw.substring(comma + 1).trim();
                if (prefix.length() < 2) return;
                List<String> matches = prompts.suggestions(prefix);
                for (int i = 0; i < Math.min(5, matches.size()); i++) {
                    final String tag = matches.get(i);
                    if (tag.equalsIgnoreCase(prefix)) continue;
                    Button choice = ui.button(tag);
                    choice.setOnClickListener(v -> {
                        promptInput.setText(raw.substring(0, comma + 1) + (comma >= 0 ? " " : "") + tag + ", ");
                        promptInput.setSelection(promptInput.length());
                    });
                    suggestionsRow.addView(choice);
                }
            }
            public void afterTextChanged(Editable e) {}
        });

        LinearLayout pTools = ui.row();
        Button koBtn = ui.button("한글→태그");
        koBtn.setOnClickListener(v -> promptInput.setText(prompts.translateKoreanInput(promptInput.getText().toString())));
        Button conflictBtn = ui.button("중복/충돌");
        conflictBtn.setOnClickListener(v -> {
            String dedup = prompts.deduplicate(promptInput.getText().toString());
            promptInput.setText(dedup);
            List<String> c = prompts.detectConflicts(dedup);
            Toast.makeText(this, c.isEmpty() ? "충돌 없음 (중복 정리됨)" : "충돌 감지: " + String.join(", ", c), Toast.LENGTH_LONG).show();
        });
        Button presetBtn = ui.button("프리셋");
        presetBtn.setOnClickListener(v -> presetDialog());
        pTools.addView(koBtn, ui.lp(0, ui.dp(44), 1));
        pTools.addView(conflictBtn, ui.lp(0, ui.dp(44), 1));
        pTools.addView(presetBtn, ui.lp(0, ui.dp(44), 1));
        form.addView(pTools);

        LinearLayout cTools = ui.row();
        Button charBtn = ui.button("캐릭터 삽입");
        charBtn.setOnClickListener(v -> characterDialog());
        Button loraBtn = ui.button("활성 LoRA(" + loras.activeLoras().size() + ")");
        loraBtn.setOnClickListener(v -> { storeFilter = "lora"; showModelStore(); });
        Button lastSeedBtn = ui.button("마지막 Seed");
        lastSeedBtn.setOnClickListener(v -> {
            long last = prefs.getLong("last_seed", -1L);
            if (seedInput != null) seedInput.setText(String.valueOf(last));
        });
        cTools.addView(charBtn, ui.lp(0, ui.dp(44), 1));
        cTools.addView(loraBtn, ui.lp(0, ui.dp(44), 1));
        cTools.addView(lastSeedBtn, ui.lp(0, ui.dp(44), 1));
        form.addView(cTools);

        LinearLayout qualRow = ui.row();
        Button qFast = ui.button("빠름(12)");
        qFast.setOnClickListener(v -> { stepsInput.setText("12"); cfgInput.setText("6.0"); });
        Button qBal = ui.button("균형(20)");
        qBal.setOnClickListener(v -> { stepsInput.setText("20"); cfgInput.setText("7.0"); });
        Button qHigh = ui.button("고품질(30)");
        qHigh.setOnClickListener(v -> { stepsInput.setText("30"); cfgInput.setText("7.5"); });
        qualRow.addView(qFast, ui.lp(0, ui.dp(40), 1));
        qualRow.addView(qBal, ui.lp(0, ui.dp(40), 1));
        qualRow.addView(qHigh, ui.lp(0, ui.dp(40), 1));
        ui.field(form, "품질 프리셋", qualRow);

        LinearLayout ratioRow = ui.row();
        String[][] ratios = {{"1:1", "512", "512"}, {"3:4", "576", "768"}, {"16:9", "768", "432"}, {"SDXL", "1024", "1024"}};
        for (String[] r : ratios) {
            Button rb = ui.button(r[0]);
            rb.setOnClickListener(v -> { widthInput.setText(r[1]); heightInput.setText(r[2]); });
            ratioRow.addView(rb, ui.lp(0, ui.dp(40), 1));
        }
        ui.field(form, "비율 프리셋", ratioRow);

        widthInput = numericInput("가로 (64~2048)", prefs.getString("width", "512"));
        heightInput = numericInput("세로 (64~2048)", prefs.getString("height", "512"));
        LinearLayout dimRow = ui.row();
        dimRow.addView(widthInput, ui.lp(0, ui.dp(48), 1));
        dimRow.addView(heightInput, ui.lp(0, ui.dp(48), 1));
        ui.field(form, "해상도 (8의 배수)", dimRow);

        stepsInput = numericInput("Steps (1~200)", prefs.getString("steps", "20"));
        cfgInput = numericInput("CFG (1~30)", prefs.getString("cfg", "7.0"));
        seedInput = numericInput("Seed (-1=랜덤)", prefs.getString("seed", "-1"));
        LinearLayout optRow = ui.row();
        optRow.addView(stepsInput, ui.lp(0, ui.dp(48), 1));
        optRow.addView(cfgInput, ui.lp(0, ui.dp(48), 1));
        optRow.addView(seedInput, ui.lp(0, ui.dp(48), 1));
        ui.field(form, "Steps · CFG · Seed", optRow);

        batchInput = numericInput("Batch (1~8)", prefs.getString("batch", "1"));
        clipSkipInput = numericInput("CLIP Skip (1~12)", prefs.getString("clip_skip", "1"));
        LinearLayout advNumRow = ui.row();
        advNumRow.addView(batchInput, ui.lp(0, ui.dp(48), 1));
        advNumRow.addView(clipSkipInput, ui.lp(0, ui.dp(48), 1));
        ui.field(form, "배치 수 · CLIP Skip", advNumRow);

        watch(widthInput, "width"); watch(heightInput, "height");
        watch(stepsInput, "steps"); watch(cfgInput, "cfg"); watch(seedInput, "seed");
        watch(batchInput, "batch"); watch(clipSkipInput, "clip_skip");

        LinearLayout sampRow = ui.row();
        Button sampBtn = ui.button("샘플러: " + selectedSampler);
        sampBtn.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("샘플러 선택")
                .setItems(SAMPLERS, (d, w) -> { selectedSampler = SAMPLERS[w]; prefs.edit().putString("sampler", selectedSampler).apply(); sampBtn.setText("샘플러: " + selectedSampler); }).show());
        Button schedBtn = ui.button("스케줄러: " + selectedScheduler);
        schedBtn.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("스케줄러 선택")
                .setItems(SCHEDULERS, (d, w) -> { selectedScheduler = SCHEDULERS[w]; prefs.edit().putString("scheduler", selectedScheduler).apply(); schedBtn.setText("스케줄러: " + selectedScheduler); }).show());
        sampRow.addView(sampBtn, ui.lp(0, ui.dp(44), 1));
        sampRow.addView(schedBtn, ui.lp(0, ui.dp(44), 1));
        form.addView(sampRow);

        CheckBox hiresCb = new CheckBox(this);
        hiresCb.setText("Hires.fix 2단계 고해상도 (1.5x)");
        hiresCb.setTextColor(ui.text);
        hiresCb.setChecked(prefs.getBoolean("hires", false));
        hiresCb.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("hires", c).apply());
        form.addView(hiresCb);



        genProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        genProgressBar.setMax(100);
        genProgressBar.setVisibility(View.GONE);
        form.addView(genProgressBar);

        genStatusView = ui.text("대기 중", 13, ui.muted);
        form.addView(genStatusView);

        generateBtn = ui.accentButton("실제 이미지 생성 / 대기열 추가");
        generateBtn.setOnClickListener(v -> enqueueGeneration(null, null, null, "none", 0.75f, 0.8f));
        form.addView(generateBtn, ui.lp(-1, ui.dp(50)));

        cancelBtn = ui.button("현재 생성 중단 및 대기열 비우기");
        cancelBtn.setVisibility(generationQueue.currentJob() != null ? View.VISIBLE : View.GONE);
        cancelBtn.setOnClickListener(v -> {
            generationQueue.cancelPending();
            if (NativeEngine.isNativeLibraryLoaded()) NativeEngine.nativeCancel();
            genStatusView.setText("취소 요청됨");
        });
        form.addView(cancelBtn, ui.lp(-1, ui.dp(46)));
        ui.card(content, form);
    }

    private void showEditScreen() {
        screen("edit", "편집 · Inpaint · Outpaint · ControlNet");
        LinearLayout box = ui.col();

        LinearLayout topRow = ui.row();
        Button pickBtn = ui.button("이미지 불러오기");
        pickBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_PICK_EDIT_IMAGE);
        });
        Button compareBtn = ui.button("전/후 비교");
        topRow.addView(pickBtn, ui.lp(0, ui.dp(44), 1));
        topRow.addView(compareBtn, ui.lp(0, ui.dp(44), 1));
        box.addView(topRow);

        MaskCanvasView maskView = new MaskCanvasView(this);
        if (editSourceBitmap != null) {
            maskView.setBackgroundBitmap(showCompareAfter && editCompareBitmap != null ? editCompareBitmap : editSourceBitmap);
        }
        compareBtn.setOnClickListener(v -> {
            if (editSourceBitmap == null || editCompareBitmap == null) {
                Toast.makeText(this, "비교할 원본과 생성 결과 이미지가 필요합니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            showCompareAfter = !showCompareAfter;
            maskView.setBackgroundBitmap(showCompareAfter ? editCompareBitmap : editSourceBitmap);
            Toast.makeText(this, showCompareAfter ? "편집 후" : "원본", Toast.LENGTH_SHORT).show();
        });
        box.addView(maskView, ui.lp(-1, ui.dp(270)));

        LinearLayout brushRow = ui.row();
        Button brushBtn = ui.button("브러시");
        brushBtn.setOnClickListener(v -> maskView.setEraseMode(false));
        Button eraseBtn = ui.button("지우개");
        eraseBtn.setOnClickListener(v -> maskView.setEraseMode(true));
        Button undoBtn = ui.button("실행취소");
        undoBtn.setOnClickListener(v -> maskView.undo());
        Button redoBtn = ui.button("다시실행");
        redoBtn.setOnClickListener(v -> maskView.redo());
        Button clearBtn = ui.button("초기화");
        clearBtn.setOnClickListener(v -> maskView.clearMask());
        for (Button b : new Button[]{brushBtn, eraseBtn, undoBtn, redoBtn, clearBtn}) {
            brushRow.addView(b, ui.lp(0, ui.dp(42), 1));
        }
        box.addView(brushRow);

        LinearLayout geoRow = ui.row();
        Button rotBtn = ui.button("회전 90°");
        rotBtn.setOnClickListener(v -> {
            if (editSourceBitmap == null) return;
            editSourceBitmap = ImageEditProcessor.rotate(editSourceBitmap, 90f);
            maskView.setBackgroundBitmap(editSourceBitmap);
        });
        Button cropBtn = ui.button("1:1 자르기");
        cropBtn.setOnClickListener(v -> {
            if (editSourceBitmap == null) return;
            editSourceBitmap = ImageEditProcessor.centerCropSquare(editSourceBitmap);
            maskView.setBackgroundBitmap(editSourceBitmap);
        });
        Button outpaintBtn = ui.button("Outpaint (+128px)");
        outpaintBtn.setOnClickListener(v -> {
            if (editSourceBitmap == null) return;
            ImageEditProcessor.OutpaintPair pair = ImageEditProcessor.createOutpaintPair(editSourceBitmap, 64, 64, 64, 64);
            enqueueBitmapEdit(pair.initBitmap, pair.maskBitmap, null, "none", 0.8f);
        });
        geoRow.addView(rotBtn, ui.lp(0, ui.dp(42), 1));
        geoRow.addView(cropBtn, ui.lp(0, ui.dp(42), 1));
        geoRow.addView(outpaintBtn, ui.lp(0, ui.dp(42), 1));
        box.addView(geoRow);

        LinearLayout cnRow = ui.row();
        Button cnTypeBtn = ui.button("ControlNet: " + editControlType);
        String[] cnTypes = {"none", "canny", "depth", "openpose", "lineart", "softedge", "scribble", "tile"};
        cnTypeBtn.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("ControlNet 모드")
                .setItems(cnTypes, (d, w) -> { editControlType = cnTypes[w]; cnTypeBtn.setText("ControlNet: " + editControlType); }).show());
        Button cannyPreviewBtn = ui.button("전처리 미리보기");
        cannyPreviewBtn.setOnClickListener(v -> {
            if (editSourceBitmap == null) {
                Toast.makeText(this, "먼저 이미지를 선택하세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            TaskRuntime.fileExecutor().submit(() -> {
                try {
                    Bitmap preview = ImageEditProcessor.controlPreview(editSourceBitmap, editControlType);
                    editControlPreviewBitmap = preview;
                    runOnUiThread(() -> { editCompareBitmap = preview; maskView.setBackgroundBitmap(preview); });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        cnRow.addView(cnTypeBtn, ui.lp(0, ui.dp(42), 1));
        cnRow.addView(cannyPreviewBtn, ui.lp(0, ui.dp(42), 1));
        box.addView(cnRow);

        TextView denoiseLabel = ui.text("Denoise 강도: " + String.format(Locale.US, "%.2f", editDenoise), 12, ui.muted);
        SeekBar denoiseBar = new SeekBar(this);
        denoiseBar.setMax(100);
        denoiseBar.setProgress((int) (editDenoise * 100));
        denoiseBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean f) { editDenoise = Math.max(0.05f, p / 100f); denoiseLabel.setText("Denoise 강도: " + String.format(Locale.US, "%.2f", editDenoise)); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(denoiseLabel);
        box.addView(denoiseBar);

        Button runInpaintBtn = ui.accentButton("Img2Img / Inpaint 실행");
        runInpaintBtn.setOnClickListener(v -> {
            if (editSourceBitmap == null) { Toast.makeText(this, "먼저 이미지를 선택하세요.", Toast.LENGTH_SHORT).show(); return; }
            Bitmap mask = maskView.exportMaskBitmap(editSourceBitmap.getWidth(), editSourceBitmap.getHeight());
            Bitmap control = "none".equals(editControlType) ? null : editControlPreviewBitmap;
            if (!"none".equals(editControlType) && control == null) {
                Toast.makeText(this, "선택한 ControlNet의 전처리 미리보기를 먼저 실행하세요.", Toast.LENGTH_LONG).show();
                return;
            }
            enqueueBitmapEdit(editSourceBitmap, mask, control, editControlType, editDenoise);
        });
        box.addView(runInpaintBtn, ui.lp(-1, ui.dp(48)));

        LinearLayout upRow = ui.row();
        Button aiUpscaleBtn = ui.button("AI 업스케일 (ESRGAN)");
        aiUpscaleBtn.setOnClickListener(v -> {
            if (editSourceBitmap == null || !engineReady()) {
                Toast.makeText(this, "이미지와 네이티브 엔진이 필요합니다.", Toast.LENGTH_SHORT).show();
                return;
            }
            List<ModelManager.Model> upscalers = models.listByType(ModelManager.AssetType.UPSCALER);
            ModelManager.Model installed = null;
            for (ModelManager.Model u : upscalers) if (u.isInstalled()) { installed = u; break; }
            if (installed == null) {
                Toast.makeText(this, "설치된 ESRGAN 업스케일러 모델이 없습니다. 모델 탭에서 가져오세요.", Toast.LENGTH_LONG).show();
                return;
            }
            final File modelFile = installed.file;
            TaskRuntime.fileExecutor().submit(() -> {
                try {
                    Bitmap up = NativeEngine.nativeUpscale(modelFile.getAbsolutePath(), editSourceBitmap, 128, 4, prefs.getBoolean("use_vulkan", true));
                    File out = new File(galleryRepo.directory(), "AI_Upscale_" + System.currentTimeMillis() + ".png");
                    ImageEditProcessor.savePngWithMetadata(up, out, "LocalCanvas", "upscale=esrgan");
                    runOnUiThread(() -> { editCompareBitmap = up; Toast.makeText(this, "AI 업스케일 완료: " + out.getName(), Toast.LENGTH_SHORT).show(); });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "AI 업스케일 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        Button swResizeBtn = ui.button("2x 소프트웨어 확대");
        swResizeBtn.setOnClickListener(v -> {
            if (editSourceBitmap == null) return;
            final Bitmap source = editSourceBitmap;
            if ((long) source.getWidth() * source.getHeight() > 4000000L) { Toast.makeText(this, "이미지가 너무 큽니다.", Toast.LENGTH_SHORT).show(); return; }
            TaskRuntime.fileExecutor().submit(() -> {
            Bitmap up = ImageEditProcessor.resizeBicubic(source, source.getWidth() * 2, source.getHeight() * 2);
            File out = new File(galleryRepo.directory(), "Bicubic2x_" + System.currentTimeMillis() + ".png");
            ImageEditProcessor.savePngWithMetadata(up, out, "LocalCanvas", "resize=bicubic_2x");
            runOnUiThread(() -> Toast.makeText(this, "소프트웨어 2x 확대 저장됨: " + out.getName(), Toast.LENGTH_SHORT).show());
            });
        });
        upRow.addView(aiUpscaleBtn, ui.lp(0, ui.dp(44), 1));
        upRow.addView(swResizeBtn, ui.lp(0, ui.dp(44), 1));
        box.addView(upRow);
        ui.card(content, box);
    }

    private void showModelStore() {
        screen("store", "모델 스토어 · LoRA");
        LinearLayout tabs = ui.row();
        String[][] modes = {{"local", "설치/기본"}, {"hf", "HuggingFace"}, {"civitai", "Civitai(SFW)"}, {"lora", "LoRA"}};
        for (String[] m : modes) {
            Button b = m[0].equals(storeFilter) ? ui.accentButton(m[1]) : ui.button(m[1]);
            b.setTextSize(11);
            b.setOnClickListener(v -> { storeFilter = m[0]; showModelStore(); });
            tabs.addView(b, ui.lp(0, ui.dp(40), 1));
        }
        ui.card(content, tabs);

        if ("hf".equals(storeFilter) || "civitai".equals(storeFilter)) {
            EditText searchInput = ui.input("모델 검색어 (예: anime, sdxl, lora)", "");
            Button searchBtn = ui.accentButton("온라인 검색");
            searchBtn.setOnClickListener(v -> {
                final String query = searchInput.getText().toString();
                final String source = storeFilter;
                searchBtn.setEnabled(false);
                TaskRuntime.networkExecutor().submit(() -> {
                try {
                    List<ModelStoreRepository.StoreItem> res = "hf".equals(source)
                            ? storeRepo.searchHuggingFace(query, "")
                            : storeRepo.searchCivitaiSfw(query, "");
                    runOnUiThread(() -> { searchBtn.setEnabled(true); if (source.equals(storeFilter)) { lastStoreResults = res; showModelStore(); } });
                } catch (Exception e) {
                    runOnUiThread(() -> { searchBtn.setEnabled(true); Toast.makeText(this, "검색 실패: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
                }
            }); });
            LinearLayout sBox = ui.col();
            sBox.addView(searchInput);
            sBox.addView(searchBtn);
            ui.card(content, sBox);

            for (ModelStoreRepository.StoreItem item : lastStoreResults) {
                LinearLayout card = ui.col();
                card.addView(ui.text(item.name + "\n" + item.source + " · " + item.architecture + " · " + item.kind
                        + "\n예상 RAM: " + (item.estimatedRamBytes / 1024 / 1024) + " MB · " + item.license, 13, ui.text));
                ModelManager.AssetType asset = "Checkpoint".equalsIgnoreCase(item.kind) ? ModelManager.AssetType.CHECKPOINT : "VAE".equalsIgnoreCase(item.kind) ? ModelManager.AssetType.VAE : "ControlNet".equalsIgnoreCase(item.kind) ? ModelManager.AssetType.CONTROLNET : null;
                if (asset != null && item.downloadUrl != null && item.downloadUrl.startsWith("https://")) {
                    Button dl = ui.button("다운로드 (ON)");
                    dl.setOnClickListener(v -> showDownloadDialog(item.id, asset, item.name, item.downloadUrl, null));
                    card.addView(dl);
                } else {
                    card.addView(ui.text("자동 설치 불가 · 호환 파일을 직접 가져오세요", 12, ui.muted));
                }
                ui.card(content, card);
            }
            return;
        }

        if ("lora".equals(storeFilter)) {
            ui.card(content, ui.text("LoRA는 네이티브 엔진 전달 경로가 연결되어 있습니다. 실제 영향은 모델 호환성과 실기기 결과로 확인해야 하며, 호환되지 않는 조합은 실행되지 않습니다.", 12, ui.warn));
            EditText loraSearch = ui.input("온라인 LoRA 검색어", "");
            Button loraSearchBtn = ui.accentButton("검토된 LoRA 검색");
            loraSearchBtn.setOnClickListener(v -> {
                String query = loraSearch.getText().toString();
                loraSearchBtn.setEnabled(false);
                TaskRuntime.networkExecutor().submit(() -> {
                    try {
                        lastStoreResults = storeRepo.searchCivitaiSfw(query, "LoRA");
                        runOnUiThread(() -> { loraSearchBtn.setEnabled(true); showModelStore(); });
                    } catch (Exception e) {
                        runOnUiThread(() -> { loraSearchBtn.setEnabled(true); Toast.makeText(this, "LoRA 검색 실패: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
                    }
                });
            });
            LinearLayout onlineLora = ui.col();
            onlineLora.addView(loraSearch);
            onlineLora.addView(loraSearchBtn);
            ui.card(content, onlineLora);
            for (ModelStoreRepository.StoreItem item : lastStoreResults) {
                if (!"LoRA".equalsIgnoreCase(item.kind) || item.downloadUrl == null) continue;
                LinearLayout onlineCard = ui.col();
                onlineCard.addView(ui.text(item.name + "\n" + item.source + " · " + item.architecture
                        + "\n파일 크기: " + (item.sizeBytes > 0 ? (item.sizeBytes / 1024 / 1024) + " MB" : "확인 중")
                        + " · " + item.license, 13, ui.text));
                Button install = ui.accentButton("LoRA 자동 설치");
                install.setOnClickListener(v -> showLoraDownloadDialog(item));
                onlineCard.addView(install);
                ui.card(content, onlineCard);
            }
            Button importLora = ui.accentButton("로컬 LoRA (.safetensors) 가져오기");
            importLora.setOnClickListener(v -> openPicker(REQ_IMPORT_LORA));
            ui.card(content, importLora);
            for (LoraManager.LoraEntry e : loras.list(null, "All", false)) {
                LinearLayout box = ui.col();
                box.addView(ui.text(e.name + " [" + e.category + " · " + e.architecture + "]\n트리거: " + e.triggerWords
                        + "\n강도: " + String.format(Locale.US, "%.2f", e.weight), 13, ui.text));
                LinearLayout row = ui.row();
                Button toggle = e.enabled ? ui.accentButton("ON") : ui.button("OFF");
                toggle.setOnClickListener(v -> { loras.updateState(e.id, !e.enabled, e.weight, e.favorite); showModelStore(); });
                Button del = ui.button("삭제");
                del.setOnClickListener(v -> { loras.delete(e.id); showModelStore(); });
                row.addView(toggle, ui.lp(0, ui.dp(42), 1));
                row.addView(del, ui.lp(0, ui.dp(42), 1));
                box.addView(row);
                SeekBar weightBar = new SeekBar(this);
                weightBar.setMax(400);
                weightBar.setProgress(Math.round((e.weight + 2f) * 100));
                TextView weightLabel = ui.text("LoRA 강도: " + e.weight, 12, ui.muted);
                weightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar bar, int p, boolean user) { weightLabel.setText(String.format(Locale.US, "LoRA 강도: %.2f", p / 100f - 2f)); }
                    public void onStartTrackingTouch(SeekBar bar) {}
                    public void onStopTrackingTouch(SeekBar bar) { loras.updateState(e.id, e.enabled, bar.getProgress() / 100f - 2f, e.favorite); }
                });
                box.addView(weightLabel);
                box.addView(weightBar);
                ui.card(content, box);
            }
            return;
        }

        for (ModelManager.Model m : models.list()) {
            LinearLayout box = ui.col();
            box.addView(ui.text(m.name + "\n" + m.type.name() + " · " + m.architecture
                    + (m.expectedBytes > 0 ? " · ~" + (m.expectedBytes / 1024 / 1024) + " MB" : "")
                    + "\n상태: " + (m.isInstalled() ? "설치 완료" : "미설치") + " · " + m.license, 13, ui.text));
            LinearLayout actions = ui.row();
            Button select = ui.button(m.id.equals(selectedModel) || m.id.equals(selectedVae) ? "✓ 선택됨" : "선택");
            select.setOnClickListener(v -> {
                if (!m.isInstalled()) { Toast.makeText(this, "먼저 ON으로 설치하세요.", Toast.LENGTH_SHORT).show(); return; }
                if (m.type != ModelManager.AssetType.CHECKPOINT && m.type != ModelManager.AssetType.VAE) { Toast.makeText(this, "편집 화면에서 사용하는 보조 모델입니다.", Toast.LENGTH_SHORT).show(); return; }
                if (m.type == ModelManager.AssetType.VAE) { selectedVae = m.id; prefs.edit().putString("vae", selectedVae).apply(); }
                else { selectedModel = m.id; prefs.edit().putString("model", selectedModel).apply(); }
                showModelStore();
            });
            Button toggle = m.isInstalled() ? ui.button("OFF") : ui.accentButton("ON");
            toggle.setOnClickListener(v -> {
                if (m.isInstalled()) confirmRemove(m);
                else if (m.downloadUrl == null) Toast.makeText(this, "검증된 자동 다운로드 파일이 없습니다. 라이선스와 모델 구조를 확인한 뒤 로컬 가져오기를 이용하세요.", Toast.LENGTH_LONG).show();
                else showDownloadDialog(m.id, m.type, m.name, m.downloadUrl, m.sha256);
            });
            actions.addView(select, ui.lp(0, ui.dp(44), 1));
            actions.addView(toggle, ui.lp(ui.dp(76), ui.dp(44)));
            box.addView(actions);
            ui.card(content, box);
        }

        LinearLayout impRow = ui.row();
        Button impModel = ui.button("모델 가져오기");
        impModel.setOnClickListener(v -> openPicker(REQ_IMPORT_MODEL));
        Button impVae = ui.button("VAE 가져오기");
        impVae.setOnClickListener(v -> openPicker(REQ_IMPORT_VAE));
        Button impUp = ui.button("업스케일러");
        impUp.setOnClickListener(v -> openPicker(REQ_IMPORT_UPSCALER));
        impRow.addView(impModel, ui.lp(0, ui.dp(46), 1));
        impRow.addView(impVae, ui.lp(0, ui.dp(46), 1));
        impRow.addView(impUp, ui.lp(0, ui.dp(46), 1));
        ui.card(content, impRow);
    }

    private void showStudio() {
        screen("studio", "스튜디오 · 웹툰");
        Button charBtn = ui.accentButton("캐릭터 스튜디오 (" + characters.names().size() + ")");
        charBtn.setOnClickListener(v -> showCharacterEditor());
        ui.card(content, charBtn);

        Button webtoonBtn = ui.accentButton("프로젝트 / 웹툰 스토리보드 (" + projects.list().size() + ")");
        webtoonBtn.setOnClickListener(v -> showProjectEditor());
        ui.card(content, webtoonBtn);

        Button queueBtn = ui.button("생성 대기열 관리 (" + generationQueue.pendingCount() + ")");
        queueBtn.setOnClickListener(v -> showQueueScreen());
        ui.card(content, queueBtn);
    }

    private void showCharacterEditor() {
        screen("studio", "캐릭터 스튜디오");
        LinearLayout form = ui.col();
        String[] labels = {"이름", "설명", "헤어스타일", "머리카락 색상", "눈 색상", "의상", "표정", "자세", "배경"};
        EditText[] fields = new EditText[labels.length];
        for (int i = 0; i < labels.length; i++) {
            fields[i] = ui.input(labels[i], "");
            ui.field(form, labels[i], fields[i]);
        }
        Button save = ui.accentButton("캐릭터 저장");
        save.setOnClickListener(v -> {
            String name = fields[0].getText().toString().trim();
            if (name.isEmpty()) { Toast.makeText(this, "이름을 입력하세요.", Toast.LENGTH_SHORT).show(); return; }
            characters.save(new CharacterStudio.CharacterProfile(name, fields[1].getText().toString(),
                    fields[2].getText().toString(), fields[3].getText().toString(), fields[4].getText().toString(),
                    fields[5].getText().toString(), fields[6].getText().toString(), fields[7].getText().toString(),
                    fields[8].getText().toString(), "", "", 0.8f, -1L));
            Toast.makeText(this, "저장 완료", Toast.LENGTH_SHORT).show();
            showCharacterEditor();
        });
        form.addView(save, ui.lp(-1, ui.dp(48)));
        ui.card(content, form);

        for (String name : characters.names()) {
            CharacterStudio.CharacterProfile cp = characters.get(name);
            if (cp == null) continue;
            LinearLayout card = ui.col();
            card.addView(ui.text(cp.name + "\n" + cp.prompt(), 13, ui.text));
            LinearLayout row = ui.row();
            Button dup = ui.button("복제");
            dup.setOnClickListener(v -> { characters.duplicate(name, name + "_copy"); showCharacterEditor(); });
            Button del = ui.button("삭제");
            del.setOnClickListener(v -> { characters.delete(name); showCharacterEditor(); });
            row.addView(dup, ui.lp(0, ui.dp(40), 1));
            row.addView(del, ui.lp(0, ui.dp(40), 1));
            card.addView(row);
            ui.card(content, card);
        }
    }

    private void showProjectEditor() {
        screen("studio", "웹툰 / 스토리보드");
        EditText name = ui.input("프로젝트 ID (영문/숫자/.-)", "");
        Button create = ui.accentButton("프로젝트 생성");
        create.setOnClickListener(v -> {
            try { projects.create(name.getText().toString()); showProjectEditor(); }
            catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        LinearLayout top = ui.col();
        top.addView(name);
        top.addView(create);
        ui.card(content, top);

        for (File proj : projects.list()) {
            List<ProjectManager.Scene> scenes = projects.loadScenes(proj);
            LinearLayout card = ui.col();
            card.addView(ui.text("▣ " + proj.getName() + " · 장면 " + scenes.size() + "개", 15, ui.text));
            LinearLayout row = ui.row();
            Button openBtn = ui.accentButton("스토리보드 열기");
            openBtn.setOnClickListener(v -> showProjectDetail(proj));
            Button exportBtn = ui.button("세로 PNG 내보내기");
            exportBtn.setOnClickListener(v -> TaskRuntime.fileExecutor().submit(() -> {
                try {
                    File out = projects.exportWebtoonStrip(proj, 720);
                    runOnUiThread(() -> Toast.makeText(this, "내보내기 완료: " + out.getName(), Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }));
            row.addView(openBtn, ui.lp(0, ui.dp(42), 1));
            row.addView(exportBtn, ui.lp(0, ui.dp(42), 1));
            card.addView(row);
            ui.card(content, card);
        }
    }

    private void showProjectDetail(File proj) {
        screen("studio", "웹툰: " + proj.getName());
        List<ProjectManager.Scene> scenes = projects.loadScenes(proj);

        LinearLayout top = ui.row();
        Button addScene = ui.accentButton("＋ 장면 추가");
        addScene.setOnClickListener(v -> {
            scenes.add(new ProjectManager.Scene(null, "장면 " + (scenes.size() + 1), prefs.getString("prompt", "1girl, webtoon style")));
            try { projects.saveScenes(proj, scenes); showProjectDetail(proj); } catch (Exception ignored) {}
        });
        Button exportBtn = ui.button("세로 PNG 내보내기");
        exportBtn.setOnClickListener(v -> TaskRuntime.fileExecutor().submit(() -> {
            try {
                File out = projects.exportWebtoonStrip(proj, 720);
                runOnUiThread(() -> Toast.makeText(this, "내보내기 완료: " + out.getName(), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
        top.addView(addScene, ui.lp(0, ui.dp(44), 1));
        top.addView(exportBtn, ui.lp(0, ui.dp(44), 1));
        ui.card(content, top);

        for (int i = 0; i < scenes.size(); i++) {
            final int idx = i;
            ProjectManager.Scene s = scenes.get(i);
            LinearLayout card = ui.col();
            card.addView(ui.text((i + 1) + ". " + s.title + "\n프롬프트: " + s.prompt
                    + (s.dialogue.isEmpty() ? "" : "\n대사: \"" + s.dialogue + "\"")
                    + (s.sfx.isEmpty() ? "" : " · 효과음: " + s.sfx), 13, ui.text));
            LinearLayout row = ui.row();
            Button genBtn = ui.accentButton("생성");
            genBtn.setOnClickListener(v -> {
                activeWebtoonProject = proj;
                activeWebtoonSceneIndex = idx;
                prefs.edit().putString("prompt", s.prompt).apply();
                enqueueGeneration(null, null, null, "none", 0.75f, 0.8f);
            });
            Button editBtn = ui.button("편집");
            editBtn.setOnClickListener(v -> editSceneDialog(proj, scenes, idx));
            Button upBtn = ui.button("↑");
            upBtn.setOnClickListener(v -> {
                try { projects.moveScene(proj, idx, idx - 1); showProjectDetail(proj); } catch (Exception ignored) {}
            });
            Button dupBtn = ui.button("복제");
            dupBtn.setOnClickListener(v -> {
                try { projects.duplicateScene(proj, idx); showProjectDetail(proj); } catch (Exception ignored) {}
            });
            Button delBtn = ui.button("삭제");
            delBtn.setOnClickListener(v -> {
                scenes.remove(idx);
                try { projects.saveScenes(proj, scenes); showProjectDetail(proj); } catch (Exception ignored) {}
            });
            for (Button b : new Button[]{genBtn, editBtn, upBtn, dupBtn, delBtn}) {
                row.addView(b, ui.lp(0, ui.dp(40), 1));
            }
            card.addView(row);
            ui.card(content, card);
        }
    }

    private void editSceneDialog(File proj, List<ProjectManager.Scene> scenes, int idx) {
        ProjectManager.Scene s = scenes.get(idx);
        LinearLayout box = ui.col();
        EditText titleIn = ui.input("장면 제목", s.title);
        EditText promptIn = ui.input("프롬프트", s.prompt);
        EditText dialIn = ui.input("말풍선 대사", s.dialogue);
        EditText sfxIn = ui.input("효과음 (SFX)", s.sfx);
        EditText spacingIn = numericInput("하단 간격 (px)", String.valueOf(s.spacingPx));
        box.addView(titleIn); box.addView(promptIn); box.addView(dialIn); box.addView(sfxIn); box.addView(spacingIn);
        new AlertDialog.Builder(this).setTitle("장면 편집").setView(box)
                .setPositiveButton("저장", (d, w) -> {
                    s.title = titleIn.getText().toString();
                    s.prompt = promptIn.getText().toString();
                    s.dialogue = dialIn.getText().toString();
                    s.sfx = sfxIn.getText().toString();
                    try { s.spacingPx = Integer.parseInt(spacingIn.getText().toString().trim()); } catch (Exception ignored) {}
                    try { projects.saveScenes(proj, scenes); showProjectDetail(proj); } catch (Exception ignored) {}
                }).setNegativeButton("취소", null).show();
    }

    private void showQueueScreen() {
        screen("studio", "생성 대기열");
        LinearLayout ctrl = ui.row();
        Button pauseBtn = ui.button(generationQueue.isPaused() ? "재개" : "일시정지");
        pauseBtn.setOnClickListener(v -> { generationQueue.setPaused(!generationQueue.isPaused()); showQueueScreen(); });
        Button clearBtn = ui.button("대기열 비우기");
        clearBtn.setOnClickListener(v -> { generationQueue.cancelPending(); showQueueScreen(); });
        ctrl.addView(pauseBtn, ui.lp(0, ui.dp(44), 1));
        ctrl.addView(clearBtn, ui.lp(0, ui.dp(44), 1));
        ui.card(content, ctrl);

        GenerationQueue.Job cur = generationQueue.currentJob();
        if (cur != null) {
            ui.card(content, ui.text("▶ 실행 중: " + cur.prompt, 13, ui.good));
        }
        for (GenerationQueue.Job j : generationQueue.pendingSnapshot()) {
            LinearLayout row = ui.row();
            row.addView(ui.text("대기: " + j.prompt, 13, ui.text), ui.lp(0, -2, 1));
            Button up = ui.button("↑");
            up.setOnClickListener(v -> { generationQueue.moveUp(j.id); showQueueScreen(); });
            Button cancel = ui.button("✕");
            cancel.setOnClickListener(v -> { generationQueue.cancelJob(j.id); showQueueScreen(); });
            row.addView(up, ui.lp(ui.dp(42), ui.dp(40)));
            row.addView(cancel, ui.lp(ui.dp(42), ui.dp(40)));
            ui.card(content, row);
        }
        for (GenerationQueue.HistoryEntry h : generationQueue.historySnapshot()) {
            ui.card(content, ui.text("[" + h.state + "] " + h.job.prompt + (h.error != null ? "\n" + h.error : ""), 12, ui.muted));
        }
    }

    private void showGallery() {
        screen("gallery", "갤러리");
        EditText searchBox = ui.input("프롬프트/태그 검색", prefs.getString("gal_q", ""));
        Button searchBtn = ui.button("검색");
        searchBtn.setOnClickListener(v -> {
            prefs.edit().putString("gal_q", searchBox.getText().toString()).apply();
            galleryPage = 0;
            showGallery();
        });
        Button favBtn = galleryFavoritesOnly ? ui.accentButton("★") : ui.button("☆");
        favBtn.setOnClickListener(v -> { galleryFavoritesOnly = !galleryFavoritesOnly; galleryPage = 0; showGallery(); });

        LinearLayout top = ui.row();
        top.addView(searchBox, ui.lp(0, ui.dp(46), 1));
        top.addView(searchBtn, ui.lp(ui.dp(66), ui.dp(46)));
        top.addView(favBtn, ui.lp(ui.dp(48), ui.dp(46)));
        ui.card(content, top);

        List<GalleryRepository.GalleryItem> items = galleryRepo.listPaged(prefs.getString("gal_q", ""), galleryFavoritesOnly, galleryPage * 12, 12);
        if (items.isEmpty()) {
            ui.card(content, ui.text("표시할 이미지가 없습니다.", 14, ui.muted));
            if (galleryPage > 0) { Button back = ui.button("이전 페이지"); back.setOnClickListener(v -> { galleryPage--; showGallery(); }); ui.card(content, back); }
            return;
        }
        for (GalleryRepository.GalleryItem item : items) {
            LinearLayout card = ui.col();
            ImageView thumb = new ImageView(this);
            thumb.setAdjustViewBounds(true);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setContentDescription(item.file.getName());
            card.addView(thumb, ui.lp(-1, ui.dp(200)));
            galleryRepo.loadThumbnailAsync(item.file, 800, 500, (f, bmp) -> {
                if (bmp != null) runOnUiThread(() -> thumb.setImageBitmap(bmp));
            });
            thumb.setOnClickListener(v -> showFullImageDialog(item));

            card.addView(ui.text(new Date(item.file.lastModified()) + " · " + (item.file.length() / 1024) + " KB"
                    + (item.favorite ? " ★" : "") + (item.tags.isEmpty() ? "" : " · #" + item.tags), 12, ui.muted));
            ui.card(content, card);
        }
        Button previousPage = ui.button("이전 페이지");
        previousPage.setEnabled(galleryPage > 0);
        previousPage.setOnClickListener(v -> { galleryPage--; showGallery(); });
        ui.card(content, previousPage);
        Button nextPage = ui.button("다음 페이지");
        nextPage.setEnabled(items.size() == 12);
        nextPage.setOnClickListener(v -> { galleryPage++; showGallery(); });
        ui.card(content, nextPage);
    }

    private void showFullImageDialog(GalleryRepository.GalleryItem item) {
        LinearLayout box = ui.col();
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(GalleryRepository.decodeDownsampled(item.file, 1440, 1440));
        box.addView(iv, ui.lp(-1, ui.dp(280)));
        String metaText = item.pngMeta.isEmpty() ? "메타데이터 없음" : item.pngMeta.toString();
        box.addView(ui.text(metaText, 12, ui.muted));

        LinearLayout row = ui.row();
        Button reuseBtn = ui.button("설정 복사/재생성");
        Button shareBtn = ui.button("공유");
        Button delBtn = ui.button("삭제");
        row.addView(reuseBtn, ui.lp(0, ui.dp(42), 1));
        row.addView(shareBtn, ui.lp(0, ui.dp(42), 1));
        row.addView(delBtn, ui.lp(0, ui.dp(42), 1));
        box.addView(row);

        AlertDialog dlg = new AlertDialog.Builder(this).setTitle(item.file.getName()).setView(box)
                .setPositiveButton("MediaStore 저장", (d, w) -> TaskRuntime.fileExecutor().submit(() -> {
                    try {
                        Uri u = galleryRepo.exportToMediaStore(item.file);
                        runOnUiThread(() -> Toast.makeText(this, "사진 앱에 저장됨: " + u, Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }))
                .setNeutralButton("즐겨찾기", (d, w) -> { galleryRepo.setFavorite(item.file, !item.favorite); showGallery(); })
                .setNegativeButton("닫기", null).create();

        reuseBtn.setOnClickListener(v -> {
            String rawJson = item.pngMeta.get("LocalCanvas");
            if (rawJson != null) {
                try {
                    JSONObject o = new JSONObject(rawJson);
                    prefs.edit()
                            .putString("prompt", o.optString("prompt", ""))
                            .putString("negative", o.optString("negative", ""))
                            .putString("steps", String.valueOf(o.optInt("steps", 20)))
                            .putString("cfg", String.valueOf(o.optDouble("cfg", 7.0)))
                            .putString("seed", String.valueOf(o.optLong("seed", -1L)))
                            .apply();
                } catch (Exception ignored) {}
            }
            dlg.dismiss();
            showGenerate();
        });
        shareBtn.setOnClickListener(v -> TaskRuntime.fileExecutor().submit(() -> {
            try {
                Uri u = galleryRepo.exportToMediaStore(item.file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/png");
                send.putExtra(Intent.EXTRA_STREAM, u);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "이미지 공유")));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
        delBtn.setOnClickListener(v -> {
            galleryRepo.delete(item.file);
            dlg.dismiss();
            showGallery();
        });
        dlg.show();
    }

    private void showSettings() {
        screen("settings", "설정 · 저장공간 · 진단");
        PerformanceMonitor.Snapshot perf = PerformanceMonitor.capture(this);

        LinearLayout perfCard = ui.col();
        perfCard.addView(ui.text("시스템 진단\n" + (engineReady() ? NativeEngine.nativeBackendStatus() : "네이티브 라이브러리 없음")
                + "\nRAM: " + perf.availRamMb + "/" + perf.totalRamMb + " MB · 발열: " + perf.thermalLabel
                + "\n권장 스레드: " + perf.recommendedThreads, 13, ui.text));

        CheckBox vkCb = new CheckBox(this);
        vkCb.setText("Vulkan GPU 가속 사용 (권장)");
        vkCb.setTextColor(ui.text);
        vkCb.setChecked(prefs.getBoolean("use_vulkan", true));
        vkCb.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("use_vulkan", c).apply());
        perfCard.addView(vkCb);

        CheckBox cpuCb = new CheckBox(this);
        cpuCb.setText("Vulkan 실패 시 CPU 폴백 허용");
        cpuCb.setTextColor(ui.text);
        cpuCb.setChecked(prefs.getBoolean("cpu_fallback", true));
        cpuCb.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("cpu_fallback", c).apply());
        perfCard.addView(cpuCb);

        CheckBox lowMemCb = new CheckBox(this);
        lowMemCb.setText("저메모리 모드 (VAE Tiling + Direct Conv)");
        lowMemCb.setTextColor(ui.text);
        lowMemCb.setChecked(prefs.getBoolean("low_mem_mode", perf.recommendLowMemMode));
        lowMemCb.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("low_mem_mode", c).apply());
        perfCard.addView(lowMemCb);

        Button themeBtn = ui.button(ui.dark ? "라이트 테마로 전환" : "다크 테마로 전환");
        themeBtn.setOnClickListener(v -> {
            boolean next = !ui.dark;
            prefs.edit().putBoolean("dark_theme", next).apply();
            ui = new UiKit(this, next);
            showSettings();
        });
        perfCard.addView(themeBtn);
        ui.card(content, perfCard);

        LinearLayout storCard = ui.col();
        storCard.addView(ui.text("저장 공간 센터\n" + storage.report(), 13, ui.muted));
        Button cleanBtn = ui.button("캐시 및 임시 파일 정리");
        cleanBtn.setOnClickListener(v -> {
            int n = storage.cleanCacheAndOrphans();
            Toast.makeText(this, n + "개 파일 정리됨", Toast.LENGTH_SHORT).show();
            showSettings();
        });
        Button dupBtn = ui.button("중복 모델 SHA-256 검사");
        dupBtn.setOnClickListener(v -> TaskRuntime.fileExecutor().submit(() -> {
            List<String> dups = storage.findDuplicateModels();
            runOnUiThread(() -> Toast.makeText(this, dups.isEmpty() ? "중복 모델 없음" : "중복 발견: " + String.join(", ", dups), Toast.LENGTH_LONG).show());
        }));
        Button backupBtn = ui.button("설정/스튜디오 JSON 백업");
        backupBtn.setOnClickListener(v -> TaskRuntime.fileExecutor().submit(() -> {
            try {
                File f = storage.exportBackup(characters);
                runOnUiThread(() -> Toast.makeText(this, "백업 완료: " + f.getName(), Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
        Button restoreBtn = ui.button("최신 백업 복원");
        restoreBtn.setOnClickListener(v -> {
            boolean ok = storage.restoreLatestBackup(characters);
            Toast.makeText(this, ok ? "캐릭터/설정 복원 완료" : "복원할 백업 파일이 없습니다.", Toast.LENGTH_SHORT).show();
        });
        Button diagBtn = ui.button("진단 리포트 내보내기 (개인정보 제외)");
        diagBtn.setOnClickListener(v -> TaskRuntime.fileExecutor().submit(() -> {
            try {
                File f = DiagnosticsCenter.exportSanitizedReport(this);
                runOnUiThread(() -> Toast.makeText(this, "리포트 저장: " + f.getName(), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));
        storCard.addView(cleanBtn);
        storCard.addView(dupBtn);
        storCard.addView(backupBtn);
        storCard.addView(restoreBtn);
        storCard.addView(diagBtn);
        ui.card(content, storCard);
    }

    private void enqueueGeneration(Bitmap initBmp, Bitmap maskBmp, Bitmap ctrlBmp, String ctrlType, float strength, float ctrlStrength) {
        if (!engineReady()) {
            Toast.makeText(this, "네이티브 엔진(stable-diffusion.cpp)이 없는 APK입니다.", Toast.LENGTH_LONG).show();
            return;
        }
        ModelManager.Model model = models.find(selectedModel);
        if (model == null || !model.isInstalled()) {
            Toast.makeText(this, "모델 화면에서 설치된 모델을 선택하세요.", Toast.LENGTH_LONG).show();
            showModelStore();
            return;
        }
        String rawPrompt = promptInput != null ? promptInput.getText().toString().trim() : prefs.getString("prompt", "");
        if (rawPrompt.isEmpty()) {
            Toast.makeText(this, "프롬프트를 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<LoraManager.LoraEntry> active = loras.activeLoras();
        final String[] loraPaths = new String[active.size()];
        final float[] loraWeights = new float[active.size()];
        final java.util.List<String> triggerParts = new java.util.ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            LoraManager.LoraEntry e = active.get(i);
            loraPaths[i] = e.file.getAbsolutePath();
            loraWeights[i] = e.weight;
            if (e.triggerWords != null && !e.triggerWords.trim().isEmpty()) triggerParts.add(e.triggerWords.trim());
        }
        String triggers = String.join(", ", triggerParts);
        String finalPrompt = triggers.isEmpty() ? rawPrompt : rawPrompt + ", " + triggers;
        prompts.pushHistory(rawPrompt);

        int w = integer(widthInput, 512), h = integer(heightInput, 512), s = integer(stepsInput, 20);
        int batch = integer(batchInput, 1), clipSkip = integer(clipSkipInput, 1);
        float cfg = floatVal(cfgInput, 7.0f);
        long seed = longVal(seedInput, -1L);
        if (w < 64 || h < 64 || w > 2048 || h > 2048 || w % 8 != 0 || h % 8 != 0 || s < 1 || s > 200) {
            Toast.makeText(this, "해상도(8의 배수)와 Steps(1~200)를 확인하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Float.isFinite(cfg) || cfg < 1 || cfg > 30 || batch < 1 || batch > 8 || clipSkip < 1 || clipSkip > 12) {
            Toast.makeText(this, "CFG 1~30, Batch 1~8, CLIP Skip 1~12 범위를 확인하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        final String jobSampler = selectedSampler, jobScheduler = selectedScheduler;
        if (seed >= 0) prefs.edit().putLong("last_seed", seed).apply();

        PerformanceMonitor.Snapshot perf = PerformanceMonitor.capture(this);
        String jobUid = UUID.randomUUID().toString();
            ModelManager.Model vaeModel = selectedVae.isEmpty() ? null : models.find(selectedVae);
            final boolean useVulkan = prefs.getBoolean("use_vulkan", true);
            final boolean cpuFallback = prefs.getBoolean("cpu_fallback", true);
            final boolean vaeTiling = prefs.getBoolean("low_mem_mode", perf.recommendLowMemMode);
            final boolean hires = prefs.getBoolean("hires", false);
            final String negPrompt = negativeInput != null ? negativeInput.getText().toString() : prefs.getString("negative", "");
            final File vaeFile = vaeModel != null && vaeModel.isInstalled() ? vaeModel.file : null;
            final File modelFile = model.file;
            File controlNetFile = null;
            if (ctrlBmp != null && ctrlType != null && !"none".equalsIgnoreCase(ctrlType)) {
                for (ModelManager.Model candidate : models.listByType(ModelManager.AssetType.CONTROLNET)) {
                    if (candidate.isInstalled() && (candidate.name.toLowerCase(Locale.US).contains(ctrlType.toLowerCase(Locale.US))
                            || candidate.id.toLowerCase(Locale.US).contains(ctrlType.toLowerCase(Locale.US)))) {
                        controlNetFile = candidate.file;
                        break;
                    }
                }
                if (controlNetFile == null) {
                    for (ModelManager.Model candidate : models.listByType(ModelManager.AssetType.CONTROLNET)) {
                        if (candidate.isInstalled()) { controlNetFile = candidate.file; break; }
                    }
                }
                if (controlNetFile == null) {
                    final String requestedControl = ctrlType;
                    runOnUiThread(() -> Toast.makeText(this,
                            "ControlNet " + requestedControl + " 모델을 먼저 설치하세요.", Toast.LENGTH_LONG).show());
                    if (generateBtn != null) generateBtn.setEnabled(true);
                    if (cancelBtn != null) cancelBtn.setVisibility(View.GONE);
                    return;
                }
            }
            final File finalControlNetFile = controlNetFile;

            if (generateBtn != null) generateBtn.setEnabled(false);
            if (cancelBtn != null) cancelBtn.setVisibility(View.VISIBLE);
            if (genStatusView != null) genStatusView.setText("대기열 추가 준비 중...");

            TaskRuntime.fileExecutor().submit(() -> {
                File initFile = saveTempBitmap(initBmp, "tmp_init_" + jobUid + ".png");
                File maskFile = saveTempBitmap(maskBmp, "tmp_mask_" + jobUid + ".png");
                File ctrlFile = saveTempBitmap(ctrlBmp, "tmp_ctrl_" + jobUid + ".png");

                GenerationQueue.Job job = new GenerationQueue.Job(
                        jobUid, modelFile, vaeFile,
                        finalControlNetFile, null, loraPaths, loraWeights, finalPrompt, negPrompt,
                        w, h, s, cfg, seed, batch, clipSkip, perf.recommendedThreads,
                        jobSampler, jobScheduler,
                        useVulkan, cpuFallback, true, true, vaeTiling,
                        hires, 1.5f, 10, 0.5f, "latent",
                        initFile, maskFile, strength, ctrlFile, ctrlType, ctrlStrength,
                        null, 0.8f, System.currentTimeMillis());

                runOnUiThread(() -> {
                    if (genStatusView != null) genStatusView.setText("대기열 추가됨 · 남은 작업 " + (generationQueue.pendingCount() + 1));
                });
                GenerationForegroundService.startIfNeeded(this, "생성 중: " + rawPrompt);
                generationQueue.submit(job, new GenerationQueue.Listener() {
                    public void started(int index, int total) { mainHandler.post(progressPoller); }
                    public void completed(Bitmap image, int index) {}
                    public void failed(String message, int index) {}
                });
            });
    }

    private void enqueueBitmapEdit(Bitmap init, Bitmap mask, Bitmap ctrl, String ctrlType, float denoise) {
        editSourceBitmap = init;
        enqueueGeneration(init, mask, ctrl, ctrlType, denoise, 0.8f);
    }

    private File saveTempBitmap(Bitmap b, String name) {
        if (b == null) return null;
        File f = new File(getCacheDir(), name);
        ImageEditProcessor.savePngWithMetadata(b, f, null, null);
        return f;
    }

    private File saveGeneratedPng(GenerationQueue.Job job, Bitmap b, String genInfo, int idx) {
        File target = new File(galleryRepo.directory(), "LocalCanvas_" + System.currentTimeMillis() + "_" + idx + ".png");
        JSONObject meta = new JSONObject();
        try {
            meta.put("prompt", job.prompt);
            meta.put("negative", job.negative);
            meta.put("model", job.model != null ? job.model.getName() : "");
            meta.put("steps", job.steps);
            meta.put("cfg", job.cfg);
            meta.put("seed", job.seed);
            meta.put("sampler", job.sampler);
            if (genInfo != null && !genInfo.isEmpty()) meta.put("engine_info", genInfo);
        } catch (Exception ignored) {}
        return ImageEditProcessor.savePngWithMetadata(b, target, "LocalCanvas", meta.toString()) ? target : null;
    }

    private void confirmRemove(ModelManager.Model m) {
        if (NativeEngine.isGeneratingSafe() || generationQueue.isModelInUse(m.file.getAbsolutePath())) {
            Toast.makeText(this, "생성 또는 대기열에서 사용 중인 모델은 삭제할 수 없습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("모델 OFF")
                .setMessage(m.name + " 파일을 삭제하고 메모리를 해제할까요?")
                .setPositiveButton("삭제", (d, w) -> {
                    if (models.remove(m.id, m.type)) {
                        if (selectedModel.equals(m.id)) selectedModel = "sd15";
                        if (selectedVae.equals(m.id)) selectedVae = "";
                        showModelStore();
                    } else Toast.makeText(this, "삭제 또는 메모리 해제 실패", Toast.LENGTH_LONG).show();
                }).setNegativeButton("취소", null).show();
    }

    private void showDownloadDialog(String id, ModelManager.AssetType type, String name, String url, String sha256) {
        LinearLayout body = ui.col();
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView msg = ui.text("대기 중", 13, ui.muted);
        body.addView(bar);
        body.addView(msg);

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(name + " 다운로드").setView(body)
                .setPositiveButton("시작", null).setNeutralButton("일시정지/취소", null).setNegativeButton("닫기", null).create();

        DownloadCoordinator.Observer dlgObs = new DownloadCoordinator.Observer() {
            @Override public void onDownloadUpdated(DownloadCoordinator.DownloadStatus st) {
                if (!st.modelId.equals(id)) return;
                runOnUiThread(() -> {
                    if (st.error != null) {
                        msg.setText("오류: " + st.error);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    } else {
                        bar.setProgress(st.percent());
                        double mbps = st.speedBytesPerSec() / (1024.0 * 1024.0);
                        long eta = st.etaSeconds();
                        msg.setText(String.format(Locale.US, "%d%% · %.1f MB/s%s",
                                st.percent(), mbps, eta >= 0 ? " · 남은 시간 " + eta + "s" : ""));
                    }
                });
            }
            @Override public void onDownloadCompleted(String modelId, File file) {
                if (modelId.equals(id)) runOnUiThread(dialog::dismiss);
            }
        };

        dialog.setOnShowListener(x -> {
            DownloadCoordinator.shared().addObserver(dlgObs);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                DownloadCoordinator.shared().startDownload(models, id, type, url, sha256);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                DownloadCoordinator.shared().cancel(id);
                msg.setText("취소 요청됨 · 다음 ON에서 재개");
            });
        });
        dialog.setOnDismissListener(d -> DownloadCoordinator.shared().removeObserver(dlgObs));
        dialog.show();
    }

    private void showLoraDownloadDialog(ModelStoreRepository.StoreItem item) {
        LinearLayout body = ui.col();
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView msg = ui.text("대기 중", 13, ui.muted);
        body.addView(bar);
        body.addView(msg);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(item.name + " LoRA 설치")
                .setView(body).setPositiveButton("시작", null).setNegativeButton("닫기", null).create();
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            TaskRuntime.networkExecutor().submit(() -> {
                try {
                    String id = "civitai_" + item.id.replaceAll("[^A-Za-z0-9._-]", "_");
                    File out = loras.download(id, item.downloadUrl, null, (done, total, elapsed) -> runOnUiThread(() -> {
                        int percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : 0;
                        bar.setProgress(percent);
                        double speed = elapsed > 0 ? done * 1000.0 / elapsed / (1024.0 * 1024.0) : 0;
                        msg.setText(String.format(Locale.US, "%d%% · %.1f MB/s", percent, speed));
                    }), cancel, item.name, "Style", item.architecture, "");
                    runOnUiThread(() -> { dialog.dismiss(); Toast.makeText(this, "LoRA 설치 완료: " + out.getName(), Toast.LENGTH_SHORT).show(); showModelStore(); });
                } catch (Exception e) {
                    runOnUiThread(() -> { msg.setText("오류: " + e.getMessage()); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); });
                }
            });
        }));
        dialog.setOnDismissListener(d -> cancel.set(true));
        dialog.show();
    }

    private void presetDialog() {
        LinearLayout box = ui.col();
        EditText name = ui.input("프리셋 이름", "새 프리셋");
        box.addView(name);
        for (String n : prompts.names()) {
            Button load = ui.button("불러오기: " + n);
            load.setOnClickListener(v -> {
                if (promptInput != null) promptInput.setText(prompts.prompt(n));
                if (negativeInput != null) negativeInput.setText(prompts.negative(n));
            });
            box.addView(load);
        }
        new AlertDialog.Builder(this).setTitle("프롬프트 프리셋").setView(box)
                .setPositiveButton("현재 설정 저장", (d, w) -> prompts.save(name.getText().toString(),
                        promptInput != null ? promptInput.getText().toString() : "",
                        negativeInput != null ? negativeInput.getText().toString() : ""))
                .setNegativeButton("닫기", null).show();
    }

    private void characterDialog() {
        Set<String> names = characters.names();
        if (names.isEmpty()) { Toast.makeText(this, "스튜디오에서 캐릭터를 먼저 저장하세요.", Toast.LENGTH_SHORT).show(); return; }
        String[] a = names.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("캐릭터 선택").setItems(a, (d, w) -> {
            CharacterStudio.CharacterProfile c = characters.get(a[w]);
            if (c != null) {
                if (promptInput != null) {
                    promptInput.append((promptInput.length() == 0 ? "" : ", ") + c.prompt());
                }
                if (c.fixedSeed >= 0 && seedInput != null) {
                    seedInput.setText(String.valueOf(c.fixedSeed));
                }
                if (!c.linkedLoraId.isEmpty()) {
                    loras.updateState(c.linkedLoraId, true, c.loraWeight, false);
                }
            }
        }).show();
    }

    private void openPicker(int reqCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, reqCode);
    }

    private EditText numericInput(String hint, String value) {
        EditText x = ui.input(hint, value);
        x.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return x;
    }

    private void watch(EditText view, String key) {
        view.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { prefs.edit().putString(key, s.toString()).apply(); }
            public void afterTextChanged(Editable e) {}
        });
    }

    private int integer(EditText e, int fallback) {
        if (e == null) return fallback;
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return fallback; }
    }

    private long longVal(EditText e, long fallback) {
        if (e == null) return fallback;
        try { return Long.parseLong(e.getText().toString().trim()); } catch (Exception x) { return fallback; }
    }

    private float floatVal(EditText e, float fallback) {
        if (e == null) return fallback;
        try { return Float.parseFloat(e.getText().toString().trim()); } catch (Exception x) { return fallback; }
    }

    @Override
    protected void onActivityResult(int req, int resultCode, Intent data) {
        super.onActivityResult(req, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_IMPORT_MODEL || req == REQ_IMPORT_VAE || req == REQ_IMPORT_UPSCALER) {
            ModelManager.AssetType type = (req == REQ_IMPORT_VAE) ? ModelManager.AssetType.VAE
                    : (req == REQ_IMPORT_UPSCALER ? ModelManager.AssetType.UPSCALER : ModelManager.AssetType.CHECKPOINT);
            TaskRuntime.fileExecutor().submit(() -> {
                try {
                    File f = models.importFile(uri, "custom_" + System.currentTimeMillis(), type);
                    runOnUiThread(() -> {
                        if (type == ModelManager.AssetType.CHECKPOINT) {
                            selectedModel = f.getName().replace(".model", "");
                            prefs.edit().putString("model", selectedModel).apply();
                        }
                        showModelStore();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "가져오기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        } else if (req == REQ_IMPORT_LORA) {
            TaskRuntime.fileExecutor().submit(() -> {
                try {
                    loras.importLora(uri, "lora_" + System.currentTimeMillis(), "가져온 LoRA", "Style", "SD 1.5", "");
                    runOnUiThread(this::showModelStore);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "LoRA 가져오기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        } else if (req == REQ_PICK_EDIT_IMAGE) {
            TaskRuntime.decodeExecutor().submit(() -> {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    runOnUiThread(() -> {
                        editSourceBitmap = bmp;
                        showCompareAfter = false;
                        showEditScreen();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "이미지 로드 실패: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
    }
}
