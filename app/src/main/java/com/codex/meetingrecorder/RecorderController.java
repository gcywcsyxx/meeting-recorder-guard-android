package com.codex.meetingrecorder;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public final class RecorderController {
    public interface Callback {
        void onResult(boolean success, boolean recording, String detail);
    }

    public interface MeetingAudioCallback {
        void onResult(boolean success, String app, String detail);
    }

    private static final String PACKAGE = "com.miui.screenrecorder";
    private static final String PREFIX = "com.miui.screenrecorder.appfunction.ScreenRecorderAppFunction#";
    private static RecorderController instance;

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final Shizuku.UserServiceArgs serviceArgs;
    private IPrivilegedService service;
    private boolean binding;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (RecorderController.this) {
                service = IPrivilegedService.Stub.asInterface(binder);
                binding = false;
                while (!pending.isEmpty()) worker.execute(pending.remove());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (RecorderController.this) {
                service = null;
                binding = false;
            }
        }
    };

    private RecorderController(Context context) {
        this.context = context.getApplicationContext();
        serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context, PrivilegedService.class))
                .processNameSuffix("recorder")
                .debuggable(BuildConfig.DEBUG)
                .version(1);
        Shizuku.addBinderReceivedListenerSticky(this::bindIfReady);
    }

    public static synchronized RecorderController get(Context context) {
        if (instance == null) instance = new RecorderController(context);
        return instance;
    }

    public boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized void bindIfReady() {
        if (!isShizukuReady() || service != null || binding) return;
        binding = true;
        try {
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable error) {
            binding = false;
            Prefs.event(context, "Shizuku 服务连接失败：" + error.getMessage());
        }
    }

    private synchronized boolean enqueue(Runnable operation) {
        if (!isShizukuReady()) {
            return false;
        }
        if (service == null) {
            pending.add(operation);
            bindIfReady();
        } else {
            worker.execute(operation);
        }
        return true;
    }

    private void submit(Runnable operation, Callback callback) {
        if (!enqueue(operation)) {
            callback.onResult(false, false, "Shizuku 未运行或尚未授权");
        }
    }

    public void getCommunicationOwner(int[] uids, String[] labels, MeetingAudioCallback callback) {
        Runnable operation = () -> {
            String output = runCommand(
                    "dumpsys audio | grep -m 1 'mAudioModeOwner:'; " +
                    "dumpsys window | grep 'mCurrentFocus=' | tail -n 1");
            boolean communication = output.contains("mMode=3");
            String app = "";
            if (communication) {
                int count = Math.min(uids.length, labels.length);
                for (int i = 0; i < count; i++) {
                    if (uids[i] >= 0 && output.contains("mUid=" + uids[i])) {
                        app = labels[i];
                        break;
                    }
                }
            }
            if (output.contains("us.zoom.videomeetings/") && output.contains("ZmConfActivity")) app = "Zoom";
            callback.onResult(output.contains("EXIT=0"), app, output);
        };
        if (!enqueue(operation)) callback.onResult(false, "", "Shizuku 未运行或尚未授权");
    }

    public void getStatus(Callback callback) {
        submit(() -> {
            String output = runFunction("getRecordingStatus");
            boolean recording = output.contains("isRecording: true");
            boolean ok = output.contains("success: true") || output.contains("EXIT=0");
            callback.onResult(ok, recording, output);
        }, callback);
    }

    public void start(Callback callback) {
        submit(() -> {
            String status = runFunction("getRecordingStatus");
            if (status.contains("isRecording: true")) {
                callback.onResult(true, true, "已经处于录制状态");
                return;
            }
            String output = runFunction("startRecording");
            boolean ok = output.contains("success: true") &&
                    (output.contains("isRecording: true") || output.toLowerCase().contains("start"));
            String verify = runFunction("getRecordingStatus");
            boolean recording = verify.contains("isRecording: true");
            callback.onResult(ok && recording, recording, output + "\nVERIFY\n" + verify);
        }, callback);
    }

    public void stop(Callback callback) {
        submit(() -> {
            String status = runFunction("getRecordingStatus");
            if (status.contains("isRecording: false")) {
                callback.onResult(true, false, "当前没有录制");
                return;
            }
            String output = runFunction("stopRecording");
            String verify = runFunction("getRecordingStatus");
            boolean recording = verify.contains("isRecording: true");
            callback.onResult(!recording, recording, output + "\nVERIFY\n" + verify);
        }, callback);
    }

    private String runFunction(String function) {
        String command = "cmd app_function execute-app-function --package " + PACKAGE +
                " --function '" + PREFIX + function + "' --parameters '{}' --brief-yaml";
        return runCommand(command);
    }

    private String runCommand(String command) {
        IPrivilegedService current;
        synchronized (this) { current = service; }
        if (current == null) return "ERROR: service unavailable";
        try {
            return current.runCommand(command);
        } catch (RemoteException error) {
            synchronized (this) { service = null; }
            return "ERROR: " + error.getMessage();
        }
    }
}
