package com.example.nokidcamera;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String ACTION_JRING_CAPTURE = "com.example.nokidcamera.CAPTURE";
    private static final long CAPTURE_COOLDOWN_MS = 2000; // 2초 쿨다운

    private PreviewView previewView;
    private Button captureButton;
    private TextView resultText;
    private ImageCapture imageCapture;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;

    private boolean isAnalyzing = false;
    private long lastCaptureTime = 0;

    private static final String GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY;

    // j링 BroadcastReceiver (포그라운드 수신)
    private BroadcastReceiver jringReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        captureButton = findViewById(R.id.captureButton);
        resultText = findViewById(R.id.resultText);

        // TextView 스크롤 활성화 (스크롤 필요시만)
        resultText.setMovementMethod(new android.text.method.ScrollingMovementMethod());

        // 무음 설정 + 오디오 포커스 요청 (볼륨 키 가로채기 위해 필수)
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
            // 오디오 포커스 요청 - 볼륨 키를 앱이 먼저 받기 위해 필요
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.media.AudioFocusRequest focusRequest =
                        new android.media.AudioFocusRequest.Builder(
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                .build();
                audioManager.requestAudioFocus(focusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
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
            isAnalyzing = false;
            capturePhoto();
        });

        // j링 BroadcastReceiver 등록 (포그라운드)
        registerJringReceiver();

        resultText.setText("준비 완료 — 촬영 버튼을 누르세요");
    }

    private void registerJringReceiver() {
        jringReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_JRING_CAPTURE.equals(intent.getAction())) {
                    isAnalyzing = false;
                    capturePhoto();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_JRING_CAPTURE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(jringReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(jringReceiver, filter);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_JRING_CAPTURE.equals(action)
                || android.provider.MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                || "android.media.action.STILL_IMAGE_CAMERA".equals(action)
                || "android.intent.action.CAMERA_BUTTON".equals(action)) {
            isAnalyzing = false;
            capturePhoto();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // j링 원격 카메라로 실행된 경우 자동 촬영
        Intent intent = getIntent();
        if (intent != null) {
            String action = intent.getAction();
            if (android.provider.MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                    || "android.media.action.STILL_IMAGE_CAMERA".equals(action)
                    || "android.intent.action.CAMERA_BUTTON".equals(action)) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    isAnalyzing = false;
                    capturePhoto();
                }, 1500); // 카메라 초기화 대기
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (jringReceiver != null) {
            unregisterReceiver(jringReceiver);
            jringReceiver = null;
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, getMainExecutor());
    }

    private void capturePhoto() {
        // 연속 촬영 방지 (쿨다운)
        long now = System.currentTimeMillis();
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) {
            return;
        }
        lastCaptureTime = now;

        if (isAnalyzing || imageCapture == null) {
            return;
        }

        isAnalyzing = true;
        resultText.setText("분석 중...");

        File photoFile = new File(getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, getMainExecutor(),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        analyzeWithGemini(photoFile);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        runOnUiThread(() -> {
                            isAnalyzing = false;
                            resultText.setText("촬영 오류: " + exception.getMessage());
                        });
                    }
                });
    }

    private void analyzeWithGemini(File photoFile) {
        new Thread(() -> {
            try {
                byte[] imageBytes = Files.readAllBytes(photoFile.toPath());
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                String jsonBody = "{\n" +
                        "  \"contents\": [{\n" +
                        "    \"parts\": [{\n" +
                        "      \"text\": \"이 사진의 문제를 분석하고 정답과 풀이를 제시하세요. 정답만 간단히 말해주세요.\"\n" +
                        "    }, {\n" +
                        "      \"inline_data\": {\"mime_type\": \"image/jpeg\", \"data\": \"" +
                        base64Image + "\"}}\n" +
                        "    ]\n" +
                        "  }]\n" +
                        "}";

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                Request request = new Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/"
                                + "gemini-3.1-flash-lite:generateContent?key=" + GEMINI_API_KEY)
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    isAnalyzing = false;
                    if (response.isSuccessful()) {
                        String answer = extractAnswer(responseBody);
                        resultText.setText("✓ " + answer);
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

    // ─── JSON 파싱 ───

    private String extractAnswer(String jsonResponse) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(jsonResponse);
            org.json.JSONArray candidates = root.getJSONArray("candidates");
            org.json.JSONObject content = candidates.getJSONObject(0).getJSONObject("content");
            org.json.JSONArray parts = content.getJSONArray("parts");
            return parts.getJSONObject(0).getString("text");
        } catch (Exception e) {
            return "파싱 오류: " + e.getMessage();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 볼륨 키를 시스템보다 먼저 가로채기 (j링 Short Video Control 연동)
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                isAnalyzing = false;
                capturePhoto();
            }
            return true; // 시스템 볼륨 변경 차단
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_SPACE) {
            isAnalyzing = false;
            capturePhoto();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
                        == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera();
            }
        }
    }
}
