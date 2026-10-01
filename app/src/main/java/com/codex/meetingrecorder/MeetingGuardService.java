package com.codex.meetingrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MeetingGuardService extends Service {
    private static final String TAG = "MeetingRecorder";
    private static volatile long lastSuccessfulPollElapsed;

    static long lastSuccessfulPollElapsed() { return lastSuccessfulPollElapsed; }
    private static final String CHANNEL = "meeting_guard_foreground";
    private static final int NOTIFICATION_ID = 1000;
    private static final long POLL_MS = 1000;
    private static final long POLL_TIMEOUT_MS = 8000;
    private static final long START_DELAY_MS = 1500;
    private static final long RESTART_COOLDOWN_MS = 6000;
    private static final long STOP_DELAY_MS = StopPolicy.END_CONFIRM_MS;
    private static final long WATCHDOG_MS = 7000;
    private static final long CONTROL_TIMEOUT_MS = 15000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pollInFlight;
    private long pollStartedAt;
    private int pollGeneration;
    private int queryFailures;
    private long lastDiagnosticAt;
    private boolean meetingActive;
    private String audioApp = "";
    private String activeApp = "会议";
    private boolean controlInFlight;
    private long controlStartedAt;
    private int controlGeneration;
    private boolean stopPending;
    private boolean stopInFlight;
    private int stopGeneration;
    private long stopStartedAt;
    private long lastStoppedAt;
    private final int[] targetUids = new int[MeetingTargets.APPS.length];
    private final String[] targetLabels = new String[MeetingTargets.APPS.length];

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, POLL_MS);
            if (!Prefs.isArmed(MeetingGuardService.this)) {
                stopSelf();
                return;
            }
            if (pollInFlight) {
                if (SystemClock.elapsedRealtime() - pollStartedAt < POLL_TIMEOUT_MS) return;
                pollInFlight = false;
                pollGeneration++;
                RecorderController.get(MeetingGuardService.this).recoverFromStalledQuery();
                Prefs.event(MeetingGuardService.this,
                        now() + " 检测查询超时，已自动恢复；请确认录制状态");
                updateNotification("警告：检测查询超时，正在恢复");
            }
            pollInFlight = true;
            pollStartedAt = SystemClock.elapsedRealtime();
            int generation = ++pollGeneration;
            RecorderController.get(MeetingGuardService.this)
                    .getCommunicationOwner(targetUids, targetLabels, (success, app, detail) -> handler.post(() -> {
                        if (generation != pollGeneration) return;
                        pollInFlight = false;
                        if (!success) {
                            Log.w(TAG, "Meeting poll failed: " + detail);
                            queryFailures++;
                            if (queryFailures == 1 || queryFailures % 3 == 0) {
                                Prefs.event(MeetingGuardService.this,
                                        now() + " 无法读取会议状态：" + detail);
                                updateNotification("警告：会议检测失败 " + detail);
                            }
                            return;
                        }
                        queryFailures = 0;
                        if (!app.isEmpty()) Log.i(TAG, "Meeting detected: " + app);
                        lastSuccessfulPollElapsed = SystemClock.elapsedRealtime();
                        if (app.isEmpty() && SystemClock.elapsedRealtime() - lastDiagnosticAt > 5000) {
                            lastDiagnosticAt = SystemClock.elapsedRealtime();
                            String mode = detail.contains("mMode=3") ? "通话模式" : "非通话模式";
                            String uid = detail.replaceAll("(?s).*mUid=([0-9]+),.*", "$1");
                            if (uid.length() > 10) uid = "未知";
                            updateNotification("检测正常：" + mode + " UID=" + uid + " ZoomUID=" + targetUids[0]);
                        }
                        String next = success ? app : "";
                        if (!next.equals(audioApp)) {
                            audioApp = next;
                            evaluate();
                        } else if (next.isEmpty() && Prefs.startedByUs(MeetingGuardService.this)) {
                            // Also recover an unfinished stop after a service restart or failed command.
                            evaluate();
                        }
                    }));
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!meetingActive || audioApp.isEmpty()) return;
            ensureRecording();
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final Runnable startRunnable = () -> {
        if (audioApp.isEmpty() || !Prefs.isArmed(this)) return;
        meetingActive = true;
        activeApp = audioApp;
        Prefs.event(this, now() + " 常驻守护检测到 " + activeApp + "，正在确认录制");
        updateNotification("已检测到 " + activeApp + "，正在启动录屏");
        ensureRecording();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    };

    private final Runnable stopRunnable = () -> {
        stopPending = false;
        if (!audioApp.isEmpty()) return;
        if (stopInFlight) {
            if (StopPolicy.awaitingStop(true, stopStartedAt, SystemClock.elapsedRealtime(), CONTROL_TIMEOUT_MS)) return;
            stopGeneration++;
            stopInFlight = false;
            RecorderController.get(this).recoverFromStalledControl();
        }
        meetingActive = false;
        controlGeneration++;
        controlInFlight = false;
        handler.removeCallbacks(watchdog);
        if (Prefs.startedByUs(this)) {
            stopInFlight = true;
            stopStartedAt = SystemClock.elapsedRealtime();
            int generation = ++stopGeneration;
            RecorderController.get(this).stop((success, recording, detail) -> handler.post(() -> {
                if (generation != stopGeneration) return;
                stopInFlight = false;
                boolean stopped = success && !recording;
                // Keep ownership until the recorder explicitly confirms it has stopped.
                if (stopped) {
                    Prefs.setStartedByUs(this, false);
                    lastStoppedAt = SystemClock.elapsedRealtime();
                }
                Prefs.event(this, now() + (stopped ? " 通话结束，录制已停止并保存" : " 停止录制未确认，正在重试"));
                updateNotification(stopped ? "上次录像已自动保存" : "正在重试停止；必要时请手动停止");
                if (audioApp.isEmpty() && !stopped) evaluate();
                else if (!audioApp.isEmpty()) {
                    meetingActive = false;
                    evaluate();
                }
            }));
        } else {
            updateNotification("自动守护运行中");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Guard created");
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("自动守护运行中"));
        RecorderController.get(this).bindIfReady();
        for (int i = 0; i < MeetingTargets.APPS.length; i++) {
            targetUids[i] = packageUid(MeetingTargets.APPS[i][0]);
            targetLabels[i] = MeetingTargets.APPS[i][1];
        }
        handler.post(poll);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Guard started; armed=" + Prefs.isArmed(this));
        if (!Prefs.isArmed(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(poll);
        handler.post(poll);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        pollGeneration++;
        controlGeneration++;
        stopGeneration++;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void evaluate() {
        handler.removeCallbacks(startRunnable);
        if (!audioApp.isEmpty()) {
            handler.removeCallbacks(stopRunnable);
            stopPending = false;
            if (!meetingActive) handler.postDelayed(startRunnable,
                    Math.max(START_DELAY_MS, RESTART_COOLDOWN_MS - (SystemClock.elapsedRealtime() - lastStoppedAt)));
        } else if (StopPolicy.shouldSchedule(meetingActive, Prefs.startedByUs(this), stopPending)) {
            // Never postpone the deadline on each idle poll.
            stopPending = true;
            handler.postDelayed(stopRunnable, STOP_DELAY_MS);
        }
    }

    private void ensureRecording() {
        if (audioApp.isEmpty() || stopInFlight || !Prefs.isArmed(this)) return;
        RecorderController controller = RecorderController.get(this);
        if (controlInFlight) {
            if (SystemClock.elapsedRealtime() - controlStartedAt < CONTROL_TIMEOUT_MS) return;
            controlInFlight = false;
            controlGeneration++;
            controller.recoverFromStalledControl();
            Prefs.event(this, now() + " 录屏控制超时，正在重试启动");
            updateNotification("警告：录屏控制超时，正在重试");
        }
        if (!controller.isShizukuReady()) {
            Prefs.event(this, now() + " 检测到通话，但 Shizuku 未运行或未授权");
            updateNotification("警告：检测到通话，但录制控制未就绪");
            return;
        }
        controlInFlight = true;
        controlStartedAt = SystemClock.elapsedRealtime();
        int generation = ++controlGeneration;
        controller.getStatus((ok, recording, detail) -> handler.post(() -> {
            if (generation != controlGeneration) return;
            if (audioApp.isEmpty()) { controlInFlight = false; return; }
            if (ok && recording) {
                controlInFlight = false;
                updateNotification(activeApp + " 正在录制");
                return;
            }
            // Persist ownership before enqueueing: a late start may finish after call exit.
            Prefs.setStartedByUs(this, true);
            controller.start((started, nowRecording, startDetail) -> handler.post(() -> {
                if (generation != controlGeneration) return;
                controlInFlight = false;
                if (started && nowRecording) {
                    Prefs.setStartedByUs(this, true);
                    Prefs.event(this, now() + " " + activeApp + " 录制已自动启动");
                    updateNotification(activeApp + " 正在录制");
                } else {
                    Prefs.event(this, now() + " 严重：检测到通话但录制启动失败：" + startDetail);
                    updateNotification("严重警告：录制启动失败，请手动录屏");
                }
            }));
        }));
    }

    private int packageUid(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException ignored) {
            return -1;
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "会议守护常驻服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持通话检测在后台可靠运行");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("会议录音守护")
                .setContentText(message)
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(message));
    }

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
    }
}
