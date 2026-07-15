package com.example.nokidcamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
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
    private ImageCapture imageCapture;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;

    // 카메라 프레임 모션 감지 변수
    private long lastCaptureTime = 0;
    private static final long CAPTURE_DELAY = 3000;
    private static final long STABLE_DURATION = 1500;
    private double lastFrameAvg = -1;
    private long motionStartTime = 0;
    private boolean motionDetected = false;
    private boolean isAnalyzing = false;

    // 오토스크롤 변수 (TextView 자체 스크롤)
    private Handler scrollHandler = new Handler(Looper.getMainLooper());
    private Runnable scrollRunnable;
    private boolean isScrolling = false;
    private boolean scrollingDown = true;
    private int scrollRoundCount = 0;
    private int scrollY = 0;

    private static final String GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        captureButton = findViewById(R.id.captureButton);
        resultText = findViewById(R.id.resultText);

        // TextView 스크롤 활성화
        resultText.setMovementMethod(new android.text.method.ScrollingMovementMethod());

        // 무음 설정
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
        }

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
            isAnalyzing = false;
            isScrolling = false;
            shrinkResultArea();
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
            runOnUiThread(() -> {
                shrinkResultArea();
                resultText.setText("준비 완료 — 시험지를 카메라 앞에 놓으세요");
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void analyzeFrame(ImageProxy image) {
        if (isAnalyzing || isScrolling) return;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCaptureTime < CAPTURE_DELAY) return;

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
            return;
        }

        double diff = Math.abs(currentAvg - lastFrameAvg);
        lastFrameAvg = currentAvg;

        if (diff > 1.5) {
            motionDetected = true;
            motionStartTime = currentTime;
            runOnUiThread(() -> resultText.setText("움직임 감지 중... (멈추면 촬영)"));
        } else {
            if (motionDetected && (currentTime - motionStartTime) > STABLE_DURATION) {
                motionDetected = false;
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
                        expandResultArea();
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

    // ─── 자막창 크기 조절 (TextView height만 변경) ───

    private void shrinkResultArea() {
        ViewGroup.LayoutParams params = resultText.getLayoutParams();
        params.height = (int) (100 * getResources().getDisplayMetrics().density);
        resultText.setLayoutParams(params);
        resultText.scrollTo(0, 0);
        scrollY = 0;
    }

    private void expandResultArea() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        ViewGroup.LayoutParams params = resultText.getLayoutParams();
        params.height = screenHeight / 2;
        resultText.setLayoutParams(params);
        resultText.scrollTo(0, 0);
        scrollY = 0;
    }

    // ─── 오토스크롤 (TextView.scrollTo 방식) ───

    private void startAutoScroll() {
        stopAutoScroll();
        isScrolling = true;
        scrollingDown = true;
        scrollRoundCount = 0;
        scrollY = 0;

        scrollRunnable = new Runnable() {
            @Override
            public void run() {
                // 스크롤 가능한 최대값 계산
                int textHeight = resultText.getLineCount() * resultText.getLineHeight();
                int viewHeight = resultText.getHeight();
                int maxScroll = Math.max(0, textHeight - viewHeight);

                if (maxScroll <= 0) {
                    onScrollComplete();
                    return;
                }

                if (scrollingDown) {
                    scrollY += 6;
                    if (scrollY >= maxScroll) {
                        scrollY = maxScroll;
                        resultText.scrollTo(0, scrollY);
                        scrollingDown = false;
                        scrollHandler.postDelayed(this, 1500);
                    } else {
                        resultText.scrollTo(0, scrollY);
                        scrollHandler.postDelayed(this, 30);
                    }
                } else {
                    scrollY -= 6;
                    if (scrollY <= 0) {
                        scrollY = 0;
                        resultText.scrollTo(0, scrollY);
                        scrollRoundCount++;
                        if (scrollRoundCount >= 2) {
                            onScrollComplete();
                        } else {
                            scrollingDown = true;
                            scrollHandler.postDelayed(this, 1500);
                        }
                    } else {
                        resultText.scrollTo(0, scrollY);
                        scrollHandler.postDelayed(this, 30);
                    }
                }
            }
        };
        scrollHandler.postDelayed(scrollRunnable, 1000);
    }

    private void stopAutoScroll() {
        if (scrollRunnable != null) {
            scrollHandler.removeCallbacks(scrollRunnable);
            scrollRunnable = null;
        }
    }

    private void onScrollComplete() {
        isScrolling = false;
        motionDetected = false;
        lastFrameAvg = -1;
        runOnUiThread(() -> {
            shrinkResultArea();
            resultText.setText("준비 완료 — 시험지를 카메라 앞에 놓으세요");
        });
    }

    // ─── JSON 파싱 ───

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
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_SPACE) {
            stopAutoScroll();
            isAnalyzing = false;
            isScrolling = false;
            shrinkResultArea();
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
