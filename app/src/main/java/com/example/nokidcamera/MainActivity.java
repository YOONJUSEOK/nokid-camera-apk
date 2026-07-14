package com.example.nokidcamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.Sensor;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements android.hardware.SensorEventListener {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private PreviewView previewView;
    private Button captureButton;
    private TextView resultText;
    private ImageCapture imageCapture;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private float[] lastAccel = new float[3];
    private float[] lastGyro = new float[3];
    private long lastCaptureTime = 0;
    private static final long CAPTURE_DELAY = 1500;
    private static final String GEMINI_API_KEY = "AQ.Ab8RN6J2KkRK2-YuQo7vX5VWAfY-o9FNLZwSboRLAdeql2Ps8g";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        captureButton = findViewById(R.id.captureButton);
        resultText = findViewById(R.id.resultText);

        // 무음 설정
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0);
        }

        // 센서 초기화
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // 권한 확인
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.INTERNET
            }, PERMISSION_REQUEST_CODE);
        }

        captureButton.setOnClickListener(v -> capturePhoto());
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

        imageCapture = new ImageCapture.Builder().build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            resultText.setText("카메라 준비 완료");
        } catch (Exception e) {
            e.printStackTrace();
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

                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + GEMINI_API_KEY)
                        .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                        .build();

                Response response = client.newCall(request).execute();
                String responseBody = response.body().string();

                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        resultText.setText("✓ 분석 완료:\n" + extractAnswer(responseBody));
                    } else {
                        resultText.setText("API 오류: " + response.code());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> resultText.setText("오류: " + e.getMessage()));
            }
        }).start();
    }

    private String extractAnswer(String jsonResponse) {
        try {
            int startIdx = jsonResponse.indexOf("\"text\": \"") + 9;
            int endIdx = jsonResponse.indexOf("\"", startIdx);
            return jsonResponse.substring(startIdx, endIdx).replace("\\n", "\n");
        } catch (Exception e) {
            return "응답 파싱 오류";
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCaptureTime < CAPTURE_DELAY) return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            lastAccel = event.values.clone();
            float accelMagnitude = (float) Math.sqrt(lastAccel[0] * lastAccel[0] +
                    lastAccel[1] * lastAccel[1] + lastAccel[2] * lastAccel[2]);
            if (accelMagnitude > 25) {
                lastCaptureTime = currentTime;
                capturePhoto();
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            lastGyro = event.values.clone();
            float gyroMagnitude = (float) Math.sqrt(lastGyro[0] * lastGyro[0] +
                    lastGyro[1] * lastGyro[1] + lastGyro[2] * lastGyro[2]);
            if (gyroMagnitude > 5) {
                lastCaptureTime = currentTime;
                capturePhoto();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_ENTER) {
            capturePhoto();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
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
