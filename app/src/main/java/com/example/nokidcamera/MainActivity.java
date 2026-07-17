package com.example.nokidcamera;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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

    private PreviewView previewView;
    private Button captureButton;
    private Button bleButton;
    private TextView resultText;
    private ImageCapture imageCapture;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;

    private boolean isAnalyzing = false;

    // 오토스크롤
    private final Handler scrollHandler = new Handler(Looper.getMainLooper());
    private Runnable scrollRunnable;
    private boolean scrollingDown = true;
    private int scrollRoundCount = 0;
    private int scrollY = 0;

    private static final String GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY;

    // j링 BroadcastReceiver (포그라운드 수신)
    private BroadcastReceiver jringReceiver;
    // BLE 상태 수신
    private BroadcastReceiver bleStatusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        captureButton = findViewById(R.id.captureButton);
        bleButton = findViewById(R.id.bleButton);
        resultText = findViewById(R.id.resultText);

        // TextView 스크롤 활성화
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
            stopAutoScroll();
            isAnalyzing = false;
            capturePhoto();
        });

        // j링 BLE 설정 버튼
        bleButton.setOnClickListener(v -> {
            startActivity(new Intent(this, BleConnectActivity.class));
        });

        // j링 BroadcastReceiver 등록 (포그라운드)
        registerJringReceiver();

        // BLE 상태 수신
        registerBleStatusReceiver();

        // 저장된 j링 주소가 있으면 자동 연결
        autoConnectJring();
    }

    private void autoConnectJring() {
        SharedPreferences prefs = getSharedPreferences("jring_ble", MODE_PRIVATE);
        String savedAddress = prefs.getString("address", null);
        if (savedAddress != null) {
            Intent intent = new Intent(this, JringBleService.class);
            intent.putExtra("cmd", JringBleService.CMD_CONNECT);
            intent.putExtra(JringBleService.EXTRA_DEVICE_ADDRESS, savedAddress);
            startService(intent);
        }
    }

    private void registerJringReceiver() {
        jringReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_JRING_CAPTURE.equals(intent.getAction())) {
                    stopAutoScroll();
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

    private void registerBleStatusReceiver() {
        bleStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (JringBleService.ACTION_CONNECTED.equals(action)) {
                    if (bleButton != null) bleButton.setText("j링 ●");
                } else if (JringBleService.ACTION_DISCONNECTED.equals(action)) {
                    if (bleButton != null) bleButton.setText("j링 ○");
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(JringBleService.ACTION_CONNECTED);
        filter.addAction(JringBleService.ACTION_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleStatusReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bleStatusReceiver, filter);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && ACTION_JRING_CAPTURE.equals(intent.getAction())) {
            stopAutoScroll();
            isAnalyzing = false;
            capturePhoto();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (jringReceiver != null) {
            unregisterReceiver(jringReceiver);
            jringReceiver = null;
        }
        if (bleStatusReceiver != null) {
            unregisterReceiver(bleStatusReceiver);
            bleStatusReceiver = null;
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
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

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            runOnUiThread(() -> resultText.setText("준비 완료 — 촬영 버튼을 누르세요"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void capturePhoto() {
        if (imageCapture == null || isAnalyzing) return;
        isAnalyzing = true;
        resultText.setText("📸 촬영 중...");

        File outputFile = new File(getCacheDir(), "photo.jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(outputFile).build();

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
                        "      {\"inline_data\": {\"mime_type\": \"image/jpeg\", \"data\": \""
                        + base64Image + "\"}}\n" +
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
                        resultText.setText("✓ 분석 완료:\n" + answer);
                        resultText.scrollTo(0, 0);
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

    // ─── 오토스크롤 (TextView.scrollTo 방식, 레이아웃 변경 없음) ───

    private void startAutoScroll() {
        stopAutoScroll();
        scrollingDown = true;
        scrollRoundCount = 0;
        scrollY = 0;

        scrollRunnable = new Runnable() {
            @Override
            public void run() {
                android.text.Layout layout = resultText.getLayout();
                if (layout == null) {
                    scrollHandler.postDelayed(this, 100);
                    return;
                }
                int textHeight = layout.getHeight();
                int viewHeight = resultText.getHeight()
                        - resultText.getPaddingTop()
                        - resultText.getPaddingBottom();
                int maxScroll = Math.max(0, textHeight - viewHeight);

                if (maxScroll <= 0) {
                    onScrollDone();
                    return;
                }

                if (scrollingDown) {
                    scrollY += 2;
                    if (scrollY >= maxScroll) {
                        scrollY = maxScroll;
                        resultText.scrollTo(0, scrollY);
                        scrollingDown = false;
                        scrollHandler.postDelayed(this, 2000);
                    } else {
                        resultText.scrollTo(0, scrollY);
                        scrollHandler.postDelayed(this, 40);
                    }
                } else {
                    scrollY -= 2;
                    if (scrollY <= 0) {
                        scrollY = 0;
                        resultText.scrollTo(0, scrollY);
                        scrollRoundCount++;
                        if (scrollRoundCount >= 2) {
                            onScrollDone();
                        } else {
                            scrollingDown = true;
                            scrollHandler.postDelayed(this, 2000);
                        }
                    } else {
                        resultText.scrollTo(0, scrollY);
                        scrollHandler.postDelayed(this, 40);
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

    private void onScrollDone() {
        runOnUiThread(() -> {
            resultText.scrollTo(0, 0);
            resultText.setText("준비 완료 — 촬영 버튼을 누르세요");
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
            return "파싱 오류: " + e.getMessage();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 볼륨 키를 시스템보다 먼저 가로채기 (j링 Short Video Control 연동)
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                stopAutoScroll();
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
            stopAutoScroll();
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
