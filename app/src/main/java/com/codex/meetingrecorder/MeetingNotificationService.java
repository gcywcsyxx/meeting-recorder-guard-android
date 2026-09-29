package com.codex.meetingrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MeetingNotificationService extends NotificationListenerService {
    static final String[][] TARGETS = {
            {"us.zoom.videomeetings", "Zoom"},
            {"com.tencent.wemeet.app", "腾讯会议"},
            {"com.tencent.mm", "微信"},
            {"com.microsoft.teams", "Teams"},
            {"com.ss.android.lark", "飞书"},
            {"com.alibaba.android.rimet", "钉钉"},
            {"com.tencent.mobileqq", "QQ"},
            {"com.whatsapp", "WhatsApp"},
            {"com.whatsapp.w4b", "WhatsApp Business"},
            {"org.telegram.messenger", "Telegram"},
            {"com.google.android.apps.tachyon", "Google Meet"},
            {"com.cisco.webex.meetings", "Webex"},
            {"com.skype.raider", "Skype"},
            {"com.facebook.orca", "Messenger"},
            {"org.thoughtcrime.securesms", "Signal"},
            {"jp.naver.line.android", "LINE"},
            {"com.android.chrome", "Chrome 网页通话"},
            {"com.microsoft.emmx", "Edge 网页通话"},
            {"org.mozilla.firefox", "Firefox 网页通话"},
            {"com.sec.android.app.sbrowser", "三星浏览器网页通话"},
            {"com.android.browser", "小米浏览器网页通话"},
            {"com.mi.globalbrowser", "小米浏览器网页通话"},
            {"com.brave.browser", "Brave 网页通话"},
            {"com.opera.browser", "Opera 网页通话"},
            {"com.vivaldi.browser", "Vivaldi 网页通话"},
            {"com.quark.browser", "夸克网页通话"},
            {"com.UCMobile", "UC 网页通话"}
    };
    private static final long START_DELAY_MS = 1200;
    private static final long STOP_DELAY_MS = 20000;
    private static final long WATCHDOG_MS = 7000;
    private static final long AUDIO_POLL_MS = 2000;
    private static final String CHANNEL = "meeting_guard_status";

    private final Map<String, StatusBarNotification> candidates = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean meetingActive;
    private String activeApp = "";
    private String audioApp = "";
    private boolean audioPollInFlight;

    private final Runnable audioPoll = new Runnable() {
        @Override
        public void run() {
            if (audioPollInFlight) {
                handler.postDelayed(this, AUDIO_POLL_MS);
                return;
            }
            audioPollInFlight = true;
            int[] uids = new int[TARGETS.length];
            String[] labels = new String[TARGETS.length];
            for (int i = 0; i < TARGETS.length; i++) {
                uids[i] = packageUid(TARGETS[i][0]);
                labels[i] = TARGETS[i][1];
            }
            RecorderController.get(MeetingNotificationService.this)
                    .getCommunicationOwner(uids, labels, (success, app, detail) ->
                            handler.post(() -> {
                                audioPollInFlight = false;
                                String next = success ? app : "";
                                if (!next.equals(audioApp)) {
                                    audioApp = next;
                                    evaluate();
                                }
                                handler.postDelayed(audioPoll, AUDIO_POLL_MS);
                            }));
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!meetingActive || !hasMeetingSignal()) return;
            ensureRecording();
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final Runnable startRunnable = () -> {
        if (!hasMeetingSignal() || !Prefs.isArmed(this)) return;
        meetingActive = true;
        activeApp = currentMeetingApp();
        Prefs.event(this, now() + " 检测到 " + activeApp + " 会议，正在确认录制");
        ensureRecording();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    };

    private final Runnable stopRunnable = () -> {
        if (hasMeetingSignal()) return;
        meetingActive = false;
        handler.removeCallbacks(watchdog);
        if (Prefs.startedByUs(this)) {
            RecorderController.get(this).stop((success, recording, detail) -> {
                Prefs.setStartedByUs(this, false);
                Prefs.event(this, now() + (success ? " 会议结束，录制已停止并保存" : " 停止录制失败，请手动检查"));
                notifyState(success ? "会议结束，录像已保存" : "警告：自动停止失败，请手动停止", !success);
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler.removeCallbacks(audioPoll);
        handler.post(audioPoll);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        createChannel();
        handler.removeCallbacks(audioPoll);
        handler.post(audioPoll);
        candidates.clear();
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) {
            for (StatusBarNotification item : active) {
                if (isMeetingNotification(item)) candidates.put(item.getKey(), item);
            }
        }
        evaluate();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isTargetPackage(sbn.getPackageName())) return;
        if (isMeetingNotification(sbn)) candidates.put(sbn.getKey(), sbn);
        else candidates.remove(sbn.getKey());
        evaluate();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        candidates.remove(sbn.getKey());
        evaluate();
    }

    @Override
    public void onListenerDisconnected() {
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(startRunnable);
        handler.removeCallbacks(stopRunnable);
        // Some Android variants briefly disconnect a listener during an APK update
        // without destroying the Service. Audio polling is independent of notification
        // access, so keep it alive instead of silently losing automatic recording.
        handler.removeCallbacks(audioPoll);
        handler.postDelayed(audioPoll, 1000);
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void evaluate() {
        handler.removeCallbacks(startRunnable);
        handler.removeCallbacks(stopRunnable);
        if (hasMeetingSignal()) {
            if (!meetingActive) handler.postDelayed(startRunnable, START_DELAY_MS);
        } else if (meetingActive) {
            handler.postDelayed(stopRunnable, STOP_DELAY_MS);
        }
    }

    private boolean isMeetingNotification(StatusBarNotification sbn) {
        if (!isTargetPackage(sbn.getPackageName())) return false;
        Notification n = sbn.getNotification();
        String category = safe(n.category).toLowerCase(Locale.ROOT);
        String channel = safe(n.getChannelId()).toLowerCase(Locale.ROOT);
        String title = safe(n.extras.getCharSequence(Notification.EXTRA_TITLE)).toLowerCase(Locale.ROOT);
        String text = safe(n.extras.getCharSequence(Notification.EXTRA_TEXT)).toLowerCase(Locale.ROOT);
        String combined = category + " " + channel + " " + title + " " + text;
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || !sbn.isClearable();
        boolean callLike = Notification.CATEGORY_CALL.equals(n.category) ||
                containsAny(combined, "meeting", "会议", "call", "通话", "conference", "正在讲话", "返回会议");
        boolean incomingOnly = containsAny(combined, "incoming", "来电", "邀请你加入", "calling you") &&
                !containsAny(combined, "ongoing", "进行中", "in progress", "return", "返回");
        return ongoing && callLike && !incomingOnly;
    }

    private void ensureRecording() {
        RecorderController controller = RecorderController.get(this);
        if (!controller.isShizukuReady()) {
            Prefs.event(this, now() + " 检测到会议，但 Shizuku 未运行或未授权");
            notifyState("警告：会议进行中，但录制服务未就绪", true);
            return;
        }
        controller.getStatus((ok, recording, detail) -> {
            if (ok && recording) {
                Prefs.event(this, now() + " " + activeApp + " 会议录制正常");
                notifyState(activeApp + " 会议正在录制", false);
                return;
            }
            controller.start((started, nowRecording, startDetail) -> {
                if (started && nowRecording) {
                    Prefs.setStartedByUs(this, true);
                    Prefs.event(this, now() + " " + activeApp + " 会议录制已自动启动");
                    notifyState(activeApp + " 会议录制已启动", false);
                } else {
                    Prefs.event(this, now() + " 严重：检测到会议但录制启动失败");
                    notifyState("严重警告：录制启动失败，请立即手动录屏", true);
                }
            });
        });
    }

    private boolean hasMeetingCandidate() { return !candidates.isEmpty(); }

    private boolean hasMeetingSignal() { return !audioApp.isEmpty() || hasMeetingCandidate(); }

    private String currentMeetingApp() {
        if (!audioApp.isEmpty()) return audioApp;
        for (StatusBarNotification item : candidates.values()) {
            String label = labelForPackage(item.getPackageName());
            if (!label.isEmpty()) return label;
        }
        return "会议";
    }

    private boolean isTargetPackage(String name) { return !labelForPackage(name).isEmpty(); }

    private String labelForPackage(String packageName) {
        for (String[] target : TARGETS) {
            if (target[0].equals(packageName)) return target[1];
        }
        return "";
    }

    private int packageUid(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException ignored) {
            return -1;
        }
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String safe(Object value) { return value == null ? "" : value.toString(); }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "会议录音状态", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("录制开始、漏录和保存状态");
        manager.createNotificationChannel(channel);
    }

    private void notifyState(String message, boolean alarm) {
        createChannel();
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.codex.meetingrecorder.R.drawable.ic_app)
                .setContentTitle(alarm ? "会议录音守护警告" : "会议录音守护")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(!meetingActive)
                .setOngoing(meetingActive && !alarm)
                .setCategory(alarm ? Notification.CATEGORY_ALARM : Notification.CATEGORY_STATUS)
                .build();
        getSystemService(NotificationManager.class).notify(1001, notification);
    }
}
