package com.codex.meetingrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.isArmed(context)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String channelId = "meeting_guard_setup";
        manager.createNotificationChannel(new NotificationChannel(channelId, "会议守护服务", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("会议录音守护")
                .setContentText("手机重启后请确认 Shizuku 已启动，避免会议漏录")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        manager.notify(1002, notification);
    }
}
