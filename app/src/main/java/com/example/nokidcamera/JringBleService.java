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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * j링 스마트워치와 직접 BLE 연결하여 버튼 이벤트를 수신하는 서비스.
 *
 * 프로토콜 분석 결과:
 * - Service UUID : 000056ff-0000-1000-8000-00805f9b34fb
 * - Notify Char  : 000033f3-0000-1000-8000-00805f9b34fb  (HJT_IBRACELETPLUS_TX)
 * - 버튼 클릭 데이터: 바이트 배열 [0x06, 0x01, ...]
 *   (첫 바이트 0x06 = DeviceAction 커맨드, 두 번째 바이트 0x01 = action 값)
 */
public class JringBleService extends Service {

    private static final String TAG = "JringBleService";

    // j링 BLE UUID
    public static final String SERVICE_UUID    = "000056ff-0000-1000-8000-00805f9b34fb";
    public static final String NOTIFY_CHAR_UUID = "000033f3-0000-1000-8000-00805f9b34fb";
    public static final String CCCD_UUID       = "00002902-0000-1000-8000-00805f9b34fb";

    // 브로드캐스트 액션
    public static final String ACTION_CONNECTED    = "com.example.nokidcamera.BLE_CONNECTED";
    public static final String ACTION_DISCONNECTED = "com.example.nokidcamera.BLE_DISCONNECTED";
    public static final String ACTION_BUTTON       = "com.example.nokidcamera.CAPTURE";
    public static final String ACTION_SCAN_RESULT  = "com.example.nokidcamera.BLE_SCAN_RESULT";
    public static final String EXTRA_DEVICE_NAME   = "device_name";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";

    // 인텐트 커맨드
    public static final String CMD_SCAN   = "cmd_scan";
    public static final String CMD_CONNECT = "cmd_connect";
    public static final String CMD_DISCONNECT = "cmd_disconnect";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private boolean isConnected = false;

    // BLE 스캔 콜백
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            String address = device.getAddress();
            if (name == null) name = "Unknown";

            Log.d(TAG, "Scan result: " + name + " [" + address + "]");

            // j링 워치 이름 패턴 확인 (J-Ring, jring, JY 등)
            boolean isJring = name.toLowerCase().contains("ring")
                    || name.toLowerCase().contains("jy")
                    || name.toLowerCase().contains("jring")
                    || name.toLowerCase().contains("bracelet");

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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server");
                isConnected = true;
                stopScan();
                // 서비스 검색 시작
                mainHandler.postDelayed(() -> gatt.discoverServices(), 600);
                sendBroadcast(new Intent(ACTION_CONNECTED));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server");
                isConnected = false;
                sendBroadcast(new Intent(ACTION_DISCONNECTED));
                // 3초 후 재연결 시도
                mainHandler.postDelayed(() -> {
                    if (bluetoothGatt != null) {
                        String address = bluetoothGatt.getDevice().getAddress();
                        bluetoothGatt.close();
                        bluetoothGatt = null;
                        connect(address);
                    }
                }, 3000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered");
                enableNotification(gatt);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null || data.length < 2) return;

            String uuid = characteristic.getUuid().toString();
            Log.d(TAG, "Char changed: " + uuid + " data[0]=" + String.format("%02X", data[0])
                    + " data[1]=" + String.format("%02X", data[1]));

            // 커맨드 0x06 = DeviceAction, data[1] = action 값
            if (data[0] == 0x06 && data[1] == 0x01) {
                Log.d(TAG, "j링 버튼 클릭 감지! → nokid 촬영 트리거");
                sendBroadcast(new Intent(ACTION_BUTTON));
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            Log.d(TAG, "Descriptor write status: " + status);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        }
        Log.d(TAG, "JringBleService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String cmd = intent.getStringExtra("cmd");
        if (CMD_SCAN.equals(cmd)) {
            startScan();
        } else if (CMD_CONNECT.equals(cmd)) {
            String address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
            if (address != null) connect(address);
        } else if (CMD_DISCONNECT.equals(cmd)) {
            disconnect();
        }
        return START_STICKY;
    }

    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (isScanning) return;
        if (bleScanner == null) bleScanner = bluetoothAdapter.getBluetoothLeScanner();

        isScanning = true;
        Log.d(TAG, "BLE 스캔 시작");

        // Service UUID 필터로 j링 워치만 스캔
        List<ScanFilter> filters = new ArrayList<>();
        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid.fromString(SERVICE_UUID))
                    .build();
            filters.add(filter);
        } catch (Exception e) {
            // 필터 없이 스캔
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            if (!filters.isEmpty()) {
                bleScanner.startScan(filters, settings, scanCallback);
            } else {
                bleScanner.startScan(scanCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "Scan start failed: " + e.getMessage());
            // 필터 없이 재시도
            try {
                bleScanner.startScan(scanCallback);
            } catch (Exception ex) {
                Log.e(TAG, "Scan retry failed: " + ex.getMessage());
            }
        }

        // 30초 후 스캔 중지
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
        if (bluetoothAdapter == null) return;
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            Log.d(TAG, "Connecting to: " + address);
            if (bluetoothGatt != null) {
                bluetoothGatt.close();
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

    private void enableNotification(BluetoothGatt gatt) {
        try {
            BluetoothGattService service = gatt.getService(UUID.fromString(SERVICE_UUID));
            if (service == null) {
                Log.e(TAG, "Service not found: " + SERVICE_UUID);
                return;
            }

            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(UUID.fromString(NOTIFY_CHAR_UUID));
            if (characteristic == null) {
                Log.e(TAG, "Characteristic not found: " + NOTIFY_CHAR_UUID);
                return;
            }

            // Notification 활성화
            gatt.setCharacteristicNotification(characteristic, true);

            // CCCD 디스크립터 설정
            BluetoothGattDescriptor descriptor =
                    characteristic.getDescriptor(UUID.fromString(CCCD_UUID));
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
                Log.d(TAG, "Notification enabled for: " + NOTIFY_CHAR_UUID);
            }
        } catch (Exception e) {
            Log.e(TAG, "Enable notification error: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopScan();
        disconnect();
    }
}
