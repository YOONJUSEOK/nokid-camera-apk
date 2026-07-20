package com.example.nokidcamera;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 접근성 서비스 - 백그라운드에서도 볼륨 키 이벤트 감지
 * j링 Short Video Control 모드에서 링을 흔들면 볼륨 키가 발생 → nokid 촬영 트리거
 */
public class JringAccessibilityService extends AccessibilityService {

    private long lastTriggerTime = 0;
    private static final long COOLDOWN_MS = 2000; // 2초 쿨다운

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 사용 안 함
    }

    @Override
    public void onInterrupt() {
        // 사용 안 함
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        // 볼륨 업/다운 키 감지 (j링 Short Video Control)
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && event.getAction() == KeyEvent.ACTION_DOWN) {

            long now = System.currentTimeMillis();
            if (now - lastTriggerTime < COOLDOWN_MS) {
                return true; // 쿨다운 중 - 볼륨 변경도 차단
            }
            lastTriggerTime = now;

            // nokid 앱으로 촬영 브로드캐스트 전송
            Intent intent = new Intent("com.example.nokidcamera.CAPTURE");
            intent.setPackage("com.example.nokidcamera");
            sendBroadcast(intent);

            return true; // 볼륨 변경 차단 (시스템에 전달 안 함)
        }

        return false; // 다른 키는 정상 처리
    }
}
