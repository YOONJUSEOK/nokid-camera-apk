package com.example.nokidcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * j링 스마트워치 버튼 이벤트를 수신하는 BroadcastReceiver.
 * j링 앱이 com.example.nokidcamera.CAPTURE 액션을 브로드캐스트하면
 * MainActivity가 포그라운드에 있을 경우 MainActivity의 인라인 리시버가 처리하고,
 * 이 리시버는 앱을 포그라운드로 가져오는 역할을 한다.
 */
public class JringCaptureReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.example.nokidcamera.CAPTURE".equals(intent.getAction())) {
            // MainActivity를 포그라운드로 가져오고 촬영 트리거
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launchIntent.setAction("com.example.nokidcamera.CAPTURE");
            context.startActivity(launchIntent);
        }
    }
}
