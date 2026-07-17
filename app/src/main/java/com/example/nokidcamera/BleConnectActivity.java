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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class BleConnectActivity extends AppCompatActivity {

    private static final int PERM_REQUEST = 200;
    private TextView statusText;
    private Button scanButton;
    private RecyclerView recyclerView;
    private BleDeviceAdapter adapter;

    private BroadcastReceiver bleReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 간단한 레이아웃 코드로 생성
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(0xFF000000);

        statusText = new TextView(this);
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setTextSize(16);
        statusText.setText("j링 워치(SR08)를 연결하려면 스캔을 시작하세요");
        root.addView(statusText);

        scanButton = new Button(this);
        scanButton.setText("BLE 스캔 시작");
        scanButton.setOnClickListener(v -> startScan());
        android.widget.LinearLayout.LayoutParams btnParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 16, 0, 16);
        root.addView(scanButton, btnParams);

        TextView listLabel = new TextView(this);
        listLabel.setTextColor(0xFFCCCCCC);
        listLabel.setText("발견된 장치 (탭하여 연결):");
        root.addView(listLabel);

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BleDeviceAdapter((name, address) -> connectDevice(name, address));
        recyclerView.setAdapter(adapter);
        android.widget.LinearLayout.LayoutParams rvParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(recyclerView, rvParams);

        // 저장된 연결 정보 표시
        SharedPreferences prefs = getSharedPreferences("jring_ble", MODE_PRIVATE);
        String savedAddress = prefs.getString("address", null);
        String savedName = prefs.getString("name", null);
        if (savedAddress != null) {
            statusText.setText("저장된 장치: " + savedName + "\n[" + savedAddress + "]\n\n재연결하려면 스캔 후 장치를 탭하세요");
        } else {
            statusText.setText("j링 워치(SR08)를 연결하려면\n스캔 시작 → 목록에서 SR08 탭");
        }

        setContentView(root);
        requestBlePermissions();
    }

    private void startScan() {
        adapter.clear();
        statusText.setText("스캔 중...");
        Intent intent = new Intent(this, JringBleService.class);
        intent.putExtra("cmd", JringBleService.CMD_SCAN);
        startService(intent);
    }

    private void connectDevice(String name, String address) {
        statusText.setText("연결 중: " + name + "\n[" + address + "]");

        // 주소 저장
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
                    statusText.setText("✓ 연결됨! 이제 j링 버튼을 누르면 자동 촬영됩니다.");
                    Toast.makeText(BleConnectActivity.this, "j링 연결 완료!", Toast.LENGTH_SHORT).show();
                } else if (JringBleService.ACTION_DISCONNECTED.equals(action)) {
                    statusText.setText("연결 끊김. 재연결 시도 중...");
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(JringBleService.ACTION_SCAN_RESULT);
        filter.addAction(JringBleService.ACTION_CONNECTED);
        filter.addAction(JringBleService.ACTION_DISCONNECTED);
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
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERM_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
