package com.example.nokidcamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String TAG = "NokidCamera";

    private PreviewView previewView;
    private Button captureButton;
    private TextView resultText;
    private android.widget.ScrollView resultScrollView;
    private android.os.Handler scrollHandler;
    private Runnable scrollRunnable;
    private boolean scrollingDown = true;
    private int scrollRoundCount = 0;       // 왕복 횟수 카운터
    private static final int MAX_SCROLL_ROUNDS = 2; // 2왕복 후 움직임 감지 재시작

    private ImageCapture imageCapture;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;

    // 카메라 프레임 모션 감지 변수
    private long lastCaptureTime = 0;
    private static final long CAPTURE_DELAY = 4000;   // 촬영 후 4초 쿨다운
    private static final long STABLE_DURATION = 2000; // 2초 안정 후 촬영
    private static final double MOTION_THRESHOLD = 4.0; // 노이즈 무시 임계값
    private double lastFrameAvg = -1;
    private long stableStartTime = 0;
    private boolean hadMotion = false;
    private boolean isStable = true;
    private boolean isAnalyzing = false;    // 분석 중 플래그 (촬영 차단용)
    private boolean isScrolling = false;    // 오토스크롤 중 플래그 (촬영 차단용)

    private static final String GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        captureButton = findViewById(R.id.captureButton);
        resultText = findViewById(R.id.resultText);
        resultScrollView = findViewById(R.id.resultScrollView);
        scrollHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        // 무음 설정
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
        }

        // 권한 확인
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.INTERNET
            }, PERMISSION_REQUEST_CODE);
        }

        captureButton.setOnClickListener(v -> {
            stopAutoScroll();
            resetToNormalLayout();
            capturePhoto();
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreview();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreview() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        // 카메라 프레임 분석기 설정
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), image -> {
            analyzeFrame(image);
            image.close();
        });

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalysis);
            runOnUiThread(() -> resultText.setText("준비 완료 — 시험지를 카메라 앞에 놓으세요"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 카메라 프레임 밝기 평균을 비교하여 움직임 감지
     * 분석 중이거나 오토스크롤 중이면 감지 차단
     */
    private void analyzeFrame(ImageProxy image) {
        // 분석 중이거나 오토스크롤 중이면 촬영 차단
        if (isAnalyzing || isScrolling) return;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCaptureTime < CAPTURE_DELAY) return;

        // Y 채널(밝기)에서 평균값 계산 (1/16 샘플링)
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        int ySize = yBuffer.remaining();
        long sum = 0;
        int count = 0;
        for (int i = 0; i < ySize; i += 16) {
            sum += (yBuffer.get(i) & 0xFF);
            count++;
        }
        double currentAvg = (count > 0) ? (double) sum / count : 0;

        if (lastFrameAvg < 0) {
            lastFrameAvg = currentAvg;
            stableStartTime = currentTime;
            return;
        }

        double diff = Math.abs(currentAvg - lastFrameAvg);
        lastFrameAvg = currentAvg;

        if (diff > MOTION_THRESHOLD) {
            // 움직임 감지 → 안정 타이머 리셋
            hadMotion = true;
            isStable = false;
            stableStartTime = currentTime;
            runOnUiThread(() -> resultText.setText("움직임 감지 ✓ — 멈추면 자동 촬영"));
        } else {
            // 안정 상태
            if (!isStable) {
                isStable = true;
                stableStartTime = currentTime;
            }
            // 움직임이 있었고, 2초 이상 안정된 경우 촬영
            if (hadMotion && (currentTime - stableStartTime) > STABLE_DURATION) {
                hadMotion = false;
                isStable = true;
                lastCaptureTime = currentTime;
                isAnalyzing = true;
                runOnUiThread(() -> {
                    resultText.setText("📸 촬영 중...");
                    capturePhoto();
                });
            }
        }
    }

    private void capturePhoto() {
        if (imageCapture == null) return;

        File outputFile = new File(getCacheDir(), "photo.jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(outputFile).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        resultText.setText("분석 중...");
                        analyzeImage(outputFile);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        isAnalyzing = false;
                        resultText.setText("촬영 실패: " + exception.getMessage());
                    }
                });
    }

    private void analyzeImage(File imageFile) {
        new Thread(() -> {
            try {
                byte[] imageData = Files.readAllBytes(imageFile.toPath());
                String base64Image = Base64.encodeToString(imageData, Base64.NO_WRAP);

                String jsonBody = "{\n" +
                        "  \"contents\": [{\n" +
                        "    \"parts\": [\n" +
                        "      {\"text\": \"이 이미지에 있는 문제를 풀고, 정답과 풀이 과정을 한국어로 간단하게 알려줘.\"},\n" +
                        "      {\"inline_data\": {\"mime_type\": \"image/jpeg\", \"data\": \"" + base64Image + "\"}}\n" +
                        "    ]\n" +
                        "  }]\n" +
                        "}";

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                Request request = new Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=" + GEMINI_API_KEY)
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    isAnalyzing = false;
                    if (response.isSuccessful()) {
                        resultText.setText("✓ 분석 완료:\n" + extractAnswer(responseBody));
                        // 오토스크롤 시작 전 레이아웃 변경 (결과 영역 50%)
                        switchToScrollLayout();
                        startAutoScroll();
                    } else {
                        resultText.setText("API 오류: " + response.code());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    isAnalyzing = false;
                    resultText.setText("오류: " + e.getMessage());
                });
            }
        }).start();
    }

    /** 오토스크롤 모드: 카메라 25% / 결과 75% */
    private void switchToScrollLayout() {
        isScrolling = true;
        ViewGroup.LayoutParams previewParams = previewView.getLayoutParams();
        if (previewParams instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) previewParams).weight = 25;
            previewView.setLayoutParams(previewParams);
        }
        ViewGroup.LayoutParams scrollParams = resultScrollView.getLayoutParams();
        if (scrollParams instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) scrollParams).weight = 75;
            resultScrollView.setLayoutParams(scrollParams);
        }
    }

    /** 일반 모드: 카메라 55% / 결과 45% */
    private void resetToNormalLayout() {
        isScrolling = false;
        ViewGroup.LayoutParams previewParams = previewView.getLayoutParams();
        if (previewParams instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) previewParams).weight = 55;
            previewView.setLayoutParams(previewParams);
        }
        ViewGroup.LayoutParams scrollParams = resultScrollView.getLayoutParams();
        if (scrollParams instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) scrollParams).weight = 45;
            resultScrollView.setLayoutParams(scrollParams);
        }
        // 상태 초기화
        hadMotion = false;
        isStable = true;
        lastFrameAvg = -1;
        resultText.setText("준비 완료 — 시험지를 카메라 앞에 놓으세요");
    }

    private void startAutoScroll() {
        stopAutoScroll();
        scrollingDown = true;
        scrollRoundCount = 0;
        if (resultScrollView == null) return;
        resultScrollView.scrollTo(0, 0);

        scrollRunnable = new Runnable() {
            @Override
            public void run() {
                if (resultScrollView == null) return;
                int maxScroll = resultScrollView.getChildAt(0).getHeight() - resultScrollView.getHeight();

                // 스크롤 불필요 (텍스트가 짧음) → 바로 움직임 감지 재시작
                if (maxScroll <= 0) {
                    onScrollComplete();
                    return;
                }

                int current = resultScrollView.getScrollY();
                if (scrollingDown) {
                    if (current >= maxScroll) {
                        // 맨 아래 도달 → 1.5초 멈춤 후 위로
                        scrollingDown = false;
                        scrollHandler.postDelayed(this, 1500);
                    } else {
                        resultScrollView.smoothScrollBy(0, 4);
                        scrollHandler.postDelayed(this, 30);
                    }
                } else {
                    if (current <= 0) {
                        // 맨 위 도달 → 왕복 1회 완료
                        scrollRoundCount++;
                        if (scrollRoundCount >= MAX_SCROLL_ROUNDS) {
                            // 2왕복 완료 → 움직임 감지 재시작
                            onScrollComplete();
                        } else {
                            // 아직 왕복 횟수 미달 → 계속 아래로
                            scrollingDown = true;
                            scrollHandler.postDelayed(this, 1500);
                        }
                    } else {
                        resultScrollView.smoothScrollBy(0, -4);
                        scrollHandler.postDelayed(this, 30);
                    }
                }
            }
        };
        // 1초 후 스크롤 시작
        scrollHandler.postDelayed(scrollRunnable, 1000);
    }

    /** 2왕복 완료 후 호출 — 레이아웃 복원 및 움직임 감지 재시작 */
    private void onScrollComplete() {
        stopAutoScroll();
        resetToNormalLayout();
    }

    private void stopAutoScroll() {
        if (scrollHandler != null && scrollRunnable != null) {
            scrollHandler.removeCallbacks(scrollRunnable);
            scrollRunnable = null;
        }
    }

    private String extractAnswer(String jsonResponse) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(jsonResponse);
            org.json.JSONArray candidates = root.getJSONArray("candidates");
            org.json.JSONObject content = candidates.getJSONObject(0).getJSONObject("content");
            org.json.JSONArray parts = content.getJSONArray("parts");
            return parts.getJSONObject(0).getString("text");
        } catch (Exception e) {
            return "파싱 오류: " + e.getMessage() + "\n원본: " + jsonResponse.substring(0, Math.min(300, jsonResponse.length()));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_ENTER) {
            stopAutoScroll();
            resetToNormalLayout();
            capturePhoto();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera();
            }
        }
    }
}
