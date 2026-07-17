package com.example.nokidcamera;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.UUID;

/**
 * j링 스마트워치(SR08)와 직접 BLE 연결하여 버튼 이벤트를 수신하는 서비스.
 *
 * 프로토콜 분석 결과:
 * - Service UUID : 000056ff-0000-1000-8000-00805f9b34fb
 * - Notify Char  : 000033f3-0000-1000-8000-00805f9b34fb  (HJT_IBRACELETPLUS_TX)
 * - 버튼 클릭 데이터: 첫 바이트 0x06 = DeviceAction 커맨드
 *
 * 개선사항 (v36):
 * - 블루투스 ON/OFF 감지 → 자동 재연결
 * - 데이터 패턴 완화: 첫 바이트 0x06이면 무조건 촬영 트리거
 * - 모든 Notify Characteristic에 대해 수신 처리
 * - 서비스 UUID 못 찾을 경우 모든 서비스의 Notify Char에 등록
 */
public class JringBleService extends Service {

    private static final String TAG = "JringBleService";

    // j링 BLE UUID
    public static final String SERVICE_UUID     = "000056ff-0000-1000-8000-00805f9b34fb";
    public static final String NOTIFY_CHAR_UUID = "000033f3-0000-1000-8000-00805f9b34fb";
    public static final String CCCD_UUID        = "00002902-0000-1000-8000-00805f9b34fb";

    // 브로드캐스트 액션
    public static final String ACTION_CONNECTED    = "com.example.nokidcamera.BLE_CONNECTED";
    public static final String ACTION_DISCONNECTED = "com.example.nokidcamera.BLE_DISCONNECTED";
    public static final String ACTION_BUTTON       = "com.example.nokidcamera.CAPTURE";
    public static final String ACTION_SCAN_RESULT  = "com.example.nokidcamera.BLE_SCAN_RESULT";
    public static final String ACTION_BLE_DATA     = "com.example.nokidcamera.BLE_DATA"; // 디버그용
    public static final String EXTRA_DEVICE_NAME   = "device_name";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";

    // 인텐트 커맨드
    public static final String CMD_SCAN       = "cmd_scan";
    public static final String CMD_CONNECT    = "cmd_connect";
    public static final String CMD_DISCONNECT = "cmd_disconnect";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private String savedAddress = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private boolean isConnected = false;
    private boolean btWasOff = false;
    private long lastTriggerTime = 0; // 중복 트리거 방지 쿨다운

    // 블루투스 ON/OFF 감지 리시버
    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_ON) {
                Log.d(TAG, "블루투스 켜짐 → 저장된 주소로 재연결 시도");
                btWasOff = false;
                bleScanner = bluetoothAdapter.getBluetoothLeScanner();
                // 1초 후 재연결
                mainHandler.postDelayed(() -> {
                    if (savedAddress != null && !isConnected) {
                        connect(savedAddress);
                    }
                }, 1000);
            } else if (state == BluetoothAdapter.STATE_OFF) {
                Log.d(TAG, "블루투스 꺼짐");
                btWasOff = true;
                isConnected = false;
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
                sendBroadcast(new Intent(ACTION_DISCONNECTED));
            }
        }
    };

    // BLE 스캔 콜백
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            String address = device.getAddress();
            if (name == null) name = "Unknown";

            Log.d(TAG, "Scan: " + name + " [" + address + "]");

            // SR08 포함 다양한 이름 패턴
            String nameLower = name.toLowerCase();
            boolean isJring = nameLower.contains("sr08")
                    || nameLower.contains("sr0")
                    || nameLower.contains("ring")
                    || nameLower.contains("jy")
                    || nameLower.contains("jring")
                    || nameLower.contains("bracelet")
                    || nameLower.contains("ibracelet");

            Intent intent = new Intent(ACTION_SCAN_RESULT);
            intent.putExtra(EXTRA_DEVICE_NAME, name);
            intent.putExtra(EXTRA_DEVICE_ADDRESS, address);
            intent.putExtra("is_jring", isJring);
            sendBroadcast(intent);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed: " + errorCode);
        }
    };

    // BLE GATT 콜백
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT 연결됨");
                isConnected = true;
                stopScan();
                mainHandler.postDelayed(() -> {
                    if (gatt != null) gatt.discoverServices();
                }, 600);
                sendBroadcast(new Intent(ACTION_CONNECTED));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT 연결 끊김 (status=" + status + ")");
                isConnected = false;
                sendBroadcast(new Intent(ACTION_DISCONNECTED));
                // 연결 끊김 시 3초 후 재연결 (BT가 켜져 있을 때만)
                mainHandler.postDelayed(() -> {
                    if (savedAddress != null && bluetoothAdapter != null
                            && bluetoothAdapter.isEnabled()) {
                        if (bluetoothGatt != null) {
                            bluetoothGatt.close();
                            bluetoothGatt = null;
                        }
                        Log.d(TAG, "재연결 시도: " + savedAddress);
                        connect(savedAddress);
                    }
                }, 3000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 서비스 목록 로그
                List<BluetoothGattService> services = gatt.getServices();
                for (BluetoothGattService svc : services) {
                    Log.d(TAG, "  Service: " + svc.getUuid());
                }
                enableAllNotifications(gatt);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();

            // 수신 데이터 전체 로그 + 디버그 브로드캐스트
            StringBuilder sb = new StringBuilder();
            if (data != null) {
                for (byte b : data) sb.append(String.format("%02X ", b));
            }
            String hexStr = sb.toString().trim();
            Log.d(TAG, "BLE 수신 [" + characteristic.getUuid() + "]: " + hexStr);

            // 디버그 화면에 실시간 표시
            Intent dataIntent = new Intent(ACTION_BLE_DATA);
            dataIntent.putExtra("uuid", characteristic.getUuid().toString());
            dataIntent.putExtra("hex", hexStr.isEmpty() ? "(empty)" : hexStr);
            dataIntent.putExtra("len", data != null ? data.length : 0);
            sendBroadcast(dataIntent);

            // 2초 쿨다운: 연속 수신 시 중복 촬영 방지
            long now = System.currentTimeMillis();
            if (now - lastTriggerTime < 2000) {
                Log.d(TAG, "쿨다운 중 무시 (" + (now - lastTriggerTime) + "ms)");
                return;
            }
            lastTriggerTime = now;

            // 데이터 내용 무관하게 BLE Notification 수신 = 버튼 클릭으로 처리
            Log.d(TAG, ">>> j링 버튼 이벤트! → 촬영 트리거");
            sendBroadcast(new Intent(ACTION_BUTTON));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            Log.d(TAG, "DescriptorWrite: " + descriptor.getUuid() + " status=" + status);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }

        // 블루투스 상태 변화 감지 등록
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(btStateReceiver, filter);
        }

        // 저장된 주소 로드
        SharedPreferences prefs = getSharedPreferences("jring_ble", MODE_PRIVATE);
        savedAddress = prefs.getString("address", null);

        Log.d(TAG, "JringBleService 생성됨. savedAddress=" + savedAddress);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String cmd = intent.getStringExtra("cmd");
        Log.d(TAG, "onStartCommand cmd=" + cmd);

        if (CMD_SCAN.equals(cmd)) {
            startScan();
        } else if (CMD_CONNECT.equals(cmd)) {
            String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
            if (address != null) {
                savedAddress = address;
                // SharedPreferences에도 저장
                getSharedPreferences("jring_ble", MODE_PRIVATE)
                        .edit().putString("address", address).apply();
                connect(address);
            }
        } else if (CMD_DISCONNECT.equals(cmd)) {
            savedAddress = null;
            disconnect();
        }
        return START_STICKY;
    }

    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "BT 꺼져 있음, 스캔 불가");
            return;
        }
        if (isScanning) return;
        if (bleScanner == null) bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) return;

        isScanning = true;
        Log.d(TAG, "BLE 스캔 시작");

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            bleScanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            Log.e(TAG, "Scan start failed: " + e.getMessage());
            try {
                bleScanner.startScan(scanCallback);
            } catch (Exception ex) {
                Log.e(TAG, "Scan retry failed: " + ex.getMessage());
                isScanning = false;
            }
        }

        mainHandler.postDelayed(this::stopScan, 30000);
    }

    private void stopScan() {
        if (!isScanning) return;
        isScanning = false;
        try {
            if (bleScanner != null) bleScanner.stopScan(scanCallback);
        } catch (Exception e) {
            Log.e(TAG, "Stop scan error: " + e.getMessage());
        }
    }

    private void connect(String address) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "BT 꺼져 있음, 연결 불가");
            return;
        }
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            Log.d(TAG, "연결 시도: " + address);
            if (bluetoothGatt != null) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
            bluetoothGatt = device.connectGatt(this, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            Log.e(TAG, "Connect error: " + e.getMessage());
        }
    }

    private void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        isConnected = false;
    }

    /**
     * 모든 서비스의 Notify 가능한 Characteristic에 Notification 등록.
     * j링 Service UUID가 없는 경우에도 동작하도록 폴백 처리.
     */
    private void enableAllNotifications(BluetoothGatt gatt) {
        boolean found = false;

        // 1순위: j링 전용 Service + Char
        BluetoothGattService targetService = gatt.getService(UUID.fromString(SERVICE_UUID));
        if (targetService != null) {
            BluetoothGattCharacteristic ch =
                    targetService.getCharacteristic(UUID.fromString(NOTIFY_CHAR_UUID));
            if (ch != null) {
                enableNotification(gatt, ch);
                found = true;
                Log.d(TAG, "j링 전용 Char에 Notification 등록 완료");
            }
        }

        // 2순위: 모든 서비스의 모든 Notify Char에 등록 (폴백)
        if (!found) {
            Log.d(TAG, "j링 Service 없음 → 모든 Notify Char에 등록 시도");
            for (BluetoothGattService svc : gatt.getServices()) {
                for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    int props = ch.getProperties();
                    if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        Log.d(TAG, "  Notify 등록: " + ch.getUuid());
                        enableNotification(gatt, ch);
                    }
                }
            }
        }
    }

    private void enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor =
                    characteristic.getDescriptor(UUID.fromString(CCCD_UUID));
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        } catch (Exception e) {
            Log.e(TAG, "enableNotification error: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(btStateReceiver);
        } catch (Exception ignored) {}
        stopScan();
        disconnect();
    }
}
