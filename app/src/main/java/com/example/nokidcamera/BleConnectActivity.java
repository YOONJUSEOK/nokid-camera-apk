package com.example.nokidcamera;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BleConnectActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 200;
    private TextView statusText;
    private TextView debugLog;
    private ScrollView debugScroll;
    private Button scanButton;
    private RecyclerView recyclerView;
    private BleDeviceAdapter adapter;
    private BroadcastReceiver bleReceiver;
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(0xFF000000);

        // 상태 텍스트
        statusText = new TextView(this);
        statusText.setTextColor(0xFF00FF88);
        statusText.setTextSize(15);
        statusText.setText("j링 워치(SR08)를 연결하려면 스캔을 시작하세요");
        root.addView(statusText);

        // 스캔 버튼
        scanButton = new Button(this);
        scanButton.setText("BLE 스캔 시작");
        scanButton.setOnClickListener(v -> startScan());
        android.widget.LinearLayout.LayoutParams btnParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 12, 0, 12);
        root.addView(scanButton, btnParams);

        // 장치 목록
        TextView listLabel = new TextView(this);
        listLabel.setTextColor(0xFFCCCCCC);
        listLabel.setTextSize(13);
        listLabel.setText("발견된 장치 (탭하여 연결):");
        root.addView(listLabel);

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BleDeviceAdapter((name, address) -> connectDevice(name, address));
        recyclerView.setAdapter(adapter);
        android.widget.LinearLayout.LayoutParams rvParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 300);
        root.addView(recyclerView, rvParams);

        // 디버그 로그 섹션
        TextView debugLabel = new TextView(this);
        debugLabel.setTextColor(0xFFFFAA00);
        debugLabel.setTextSize(13);
        debugLabel.setText("\n[BLE 수신 데이터 로그] - 워치 버튼을 눌러보세요:");
        root.addView(debugLabel);

        debugScroll = new ScrollView(this);
        debugScroll.setBackgroundColor(0xFF111111);
        android.widget.LinearLayout.LayoutParams scrollParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.setMargins(0, 4, 0, 0);

        debugLog = new TextView(this);
        debugLog.setTextColor(0xFF88FF88);
        debugLog.setTextSize(11);
        debugLog.setPadding(8, 8, 8, 8);
        debugLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        debugLog.setText("(아직 수신 없음)\n");
        debugScroll.addView(debugLog);
        root.addView(debugScroll, scrollParams);

        // 저장된 연결 정보 표시
        SharedPreferences prefs = getSharedPreferences("jring_ble", MODE_PRIVATE);
        String savedAddress = prefs.getString("address", null);
        String savedName = prefs.getString("name", null);
        if (savedAddress != null) {
            statusText.setText("저장된 장치: " + savedName + " [" + savedAddress + "]\n재연결하려면 스캔 후 장치를 탭하세요");
        } else {
            statusText.setText("j링 워치(SR08)를 연결하려면\n스캔 시작 → 목록에서 SR08 탭");
        }

        setContentView(root);
        requestBlePermissions();
    }

    private void appendLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logBuffer.insert(0, "[" + time + "] " + msg + "\n");
        // 최대 50줄 유지
        String[] lines = logBuffer.toString().split("\n");
        if (lines.length > 50) {
            logBuffer.setLength(0);
            for (int i = 0; i < 50; i++) logBuffer.append(lines[i]).append("\n");
        }
        runOnUiThread(() -> {
            debugLog.setText(logBuffer.toString());
        });
    }

    private void startScan() {
        adapter.clear();
        statusText.setText("스캔 중...");
        appendLog("스캔 시작");
        Intent intent = new Intent(this, JringBleService.class);
        intent.putExtra("cmd", JringBleService.CMD_SCAN);
        startService(intent);
    }

    private void connectDevice(String name, String address) {
        statusText.setText("연결 중: " + name + " [" + address + "]");
        appendLog("연결 시도: " + name + " [" + address + "]");

        SharedPreferences prefs = getSharedPreferences("jring_ble", MODE_PRIVATE);
        prefs.edit().putString("address", address).putString("name", name).apply();

        Intent intent = new Intent(this, JringBleService.class);
        intent.putExtra("cmd", JringBleService.CMD_CONNECT);
        intent.putExtra(JringBleService.EXTRA_DEVICE_ADDRESS, address);
        startService(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (JringBleService.ACTION_SCAN_RESULT.equals(action)) {
                    String name = intent.getStringExtra(JringBleService.EXTRA_DEVICE_NAME);
                    String address = intent.getStringExtra(JringBleService.EXTRA_DEVICE_ADDRESS);
                    adapter.addDevice(name, address);
                } else if (JringBleService.ACTION_CONNECTED.equals(action)) {
                    statusText.setText("✓ 연결됨! 워치 버튼을 눌러보세요 → 아래 로그 확인");
                    appendLog("=== GATT 연결 성공 ===");
                    Toast.makeText(BleConnectActivity.this, "j링 연결 완료!", Toast.LENGTH_SHORT).show();
                } else if (JringBleService.ACTION_DISCONNECTED.equals(action)) {
                    statusText.setText("연결 끊김. 재연결 시도 중...");
                    appendLog("연결 끊김");
                } else if (JringBleService.ACTION_BLE_DATA.equals(action)) {
                    // 실제 수신 데이터 표시
                    String uuid = intent.getStringExtra("uuid");
                    String hex = intent.getStringExtra("hex");
                    int len = intent.getIntExtra("len", 0);
                    String shortUuid = uuid != null && uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
                    appendLog("DATA [" + shortUuid + "...] len=" + len + "\n  → " + hex);
                } else if (JringBleService.ACTION_BUTTON.equals(action)) {
                    appendLog("★★★ 촬영 트리거 발동! ★★★");
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(JringBleService.ACTION_SCAN_RESULT);
        filter.addAction(JringBleService.ACTION_CONNECTED);
        filter.addAction(JringBleService.ACTION_DISCONNECTED);
        filter.addAction(JringBleService.ACTION_BLE_DATA);
        filter.addAction(JringBleService.ACTION_BUTTON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bleReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bleReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bleReceiver != null) {
            unregisterReceiver(bleReceiver);
            bleReceiver = null;
        }
    }

    private void requestBlePermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!perms.isEmpty())
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERM_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
