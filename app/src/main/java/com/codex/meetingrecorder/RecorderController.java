package com.codex.meetingrecorder;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public final class RecorderController {
    private static final String TAG = "MeetingRecorder";
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
    private ExecutorService queryWorker = Executors.newSingleThreadExecutor();
    private ExecutorService controlWorker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService cleanupWorker = Executors.newSingleThreadScheduledExecutor();
    private static final String CLEANUP_BASELINE = "cleanup_baseline";
    private static final String MEDIA = "content://media/external/video/media";
    private static final String MEDIA_QUERY = "content query --uri " + MEDIA +
            " --projection _id:duration:relative_path:is_pending:is_trashed";
    private final Queue<Runnable> pendingControls = new ArrayDeque<>();
    private Runnable pendingQuery;
    private final Shizuku.UserServiceArgs serviceArgs;
    private IPrivilegedService service;
    private boolean binding;
    private long bindingStartedAt;

    public synchronized void recoverFromStalledQuery() {
        Log.w(TAG, "Query stalled; replacing worker. bound=" + (service != null) + " binding=" + binding);
        queryWorker.shutdownNow();
        queryWorker = Executors.newSingleThreadExecutor();
        pendingQuery = null;
        if (service == null) bindIfReady();
    }

    public synchronized void recoverFromStalledControl() {
        Log.w(TAG, "Recorder control stalled; replacing worker");
        controlWorker.shutdownNow();
        controlWorker = Executors.newSingleThreadExecutor();
        pendingControls.clear();
        if (service == null) bindIfReady();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (RecorderController.this) {
                Log.i(TAG, "Shizuku service connected; pending=" + pendingControls.size());
                service = IPrivilegedService.Stub.asInterface(binder);
                binding = false;
                bindingStartedAt = 0;
                while (!pendingControls.isEmpty()) controlWorker.execute(pendingControls.remove());
                if (pendingQuery != null) {
                    queryWorker.execute(pendingQuery);
                    pendingQuery = null;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (RecorderController.this) {
                Log.w(TAG, "Shizuku service disconnected");
                service = null;
                binding = false;
                bindingStartedAt = 0;
            }
        }
    };

    private RecorderController(Context context) {
        this.context = context.getApplicationContext();
        serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context, PrivilegedService.class))
                .processNameSuffix("recorder")
                .debuggable(BuildConfig.DEBUG)
                .version(3);
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
        if (!isShizukuReady() || service != null) return;
        if (binding) {
            if (SystemClock.elapsedRealtime() - bindingStartedAt < 10000) return;
            Log.w(TAG, "Shizuku binding timed out; reconnecting");
            try {
                Shizuku.unbindUserService(serviceArgs, connection, false);
            } catch (Throwable error) {
                Log.w(TAG, "Stale binding cleanup failed", error);
            }
            binding = false;
        }
        binding = true;
        bindingStartedAt = SystemClock.elapsedRealtime();
        try {
            Log.i(TAG, "Binding Shizuku user service");
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable error) {
            Log.e(TAG, "Shizuku bind failed", error);
            binding = false;
            bindingStartedAt = 0;
            Prefs.event(context, "Shizuku 服务连接失败：" + error.getMessage());
        }
    }

    private synchronized boolean enqueue(Runnable operation, boolean query) {
        if (!isShizukuReady()) {
            Log.w(TAG, "Shizuku unavailable for queued operation");
            return false;
        }
        if (service == null) {
            if (query) pendingQuery = operation;
            else pendingControls.add(operation);
            bindIfReady();
        } else {
            if (query) queryWorker.execute(operation);
            else controlWorker.execute(operation);
        }
        return true;
    }

    private void submit(Runnable operation, Callback callback) {
        if (!enqueue(operation, false)) {
            callback.onResult(false, false, "Shizuku 未运行或尚未授权");
        }
    }

    public void getCommunicationOwner(int[] uids, String[] labels, MeetingAudioCallback callback) {
        Runnable operation = () -> {
            String output = runCommand(
                    "dumpsys audio | grep -m 1 'mAudioModeOwner:'; " +
                    "dumpsys window | grep -m 1 'mCurrentFocus='; exit 0");
            boolean communication = output.contains("mMode=3");
            String app = "";
            if (communication) {
                int count = Math.min(uids.length, labels.length);
                for (int i = 0; i < count; i++) {
                    if (uids[i] >= 0 && output.contains("mUid=" + uids[i] + ",")) {
                        app = labels[i];
                        break;
                    }
                }
            }
            if (output.contains("us.zoom.videomeetings/") && output.contains("ZmConfActivity")) app = "Zoom";
            callback.onResult(output.contains("EXIT=0") &&
                    (output.contains("mAudioModeOwner:") || output.contains("mCurrentFocus=")), app, output);
        };
        if (!enqueue(operation, true)) callback.onResult(false, "", "Shizuku 未运行或尚未授权");
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
            // Bound cleanup to files added by this recording; never sweep historical videos.
            if (!Prefs.get(context).contains(CLEANUP_BASELINE)) {
                String snapshot = runCommand(MEDIA_QUERY);
                if (ShortRecordingPolicy.validQuery(snapshot)) {
                    long maxId=0;
                    java.util.regex.Matcher ids=java.util.regex.Pattern.compile("(?:^|[ ,])_id=(\\d+)", java.util.regex.Pattern.MULTILINE).matcher(snapshot);
                    while(ids.find()) maxId=Math.max(maxId,Long.parseLong(ids.group(1)));
                    Prefs.get(context).edit().putLong(CLEANUP_BASELINE,maxId).commit();
                }
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
            if (StopPolicy.confirmedStopped(status)) {
                queueShortCleanup();
                callback.onResult(true, false, "当前没有录制");
                return;
            }
            String output = runFunction("stopRecording");
            String verify = runFunction("getRecordingStatus");
            boolean recording = verify.contains("isRecording: true");
            if (StopPolicy.confirmedStopped(verify)) queueShortCleanup();
            callback.onResult(StopPolicy.confirmedStopped(verify), recording, output + "\nVERIFY\n" + verify);
        }, callback);
    }

    private void queueShortCleanup() {
        long baseline=Prefs.get(context).getLong(CLEANUP_BASELINE,-1);
        if(baseline<0) return;
        String snapshot=runCommand(MEDIA_QUERY);
        if(!ShortRecordingPolicy.validQuery(snapshot)) {
            // Lose cleanup eligibility rather than accidentally include later manual recordings.
            Prefs.get(context).edit().remove(CLEANUP_BASELINE).commit();
            return;
        }
        // Freeze the candidate IDs before allowing a subsequent recording to start.
        java.util.regex.Matcher ids=java.util.regex.Pattern.compile("(?:^|[ ,])_id=(\\d+)", java.util.regex.Pattern.MULTILINE).matcher(snapshot);
        while(ids.find()) {
            long id=Long.parseLong(ids.group(1));
            if(id<=baseline) continue;
            for(long delay:new long[]{2,5,12,30}) cleanupWorker.schedule(()->cleanupShort(id),delay,TimeUnit.SECONDS);
        }
        Prefs.get(context).edit().remove(CLEANUP_BASELINE).commit();
    }

    private void cleanupShort(long id) {
        String output=runCommand(MEDIA_QUERY.replace(MEDIA,MEDIA+"/"+id));
        for(ShortRecordingPolicy.Item item:ShortRecordingPolicy.parse(output)) {
            if(item.id!=id || !item.isShort()) continue;
            String result=runCommand("content update --uri "+MEDIA+"/"+id+
                    " --bind is_trashed:i:1 --where 'duration>0 AND duration<5000 AND is_pending=0 AND is_trashed=0'");
            String verify=runCommand(MEDIA_QUERY.replace(MEDIA,MEDIA+"/"+id+"?includeTrashed=1"));
            boolean trashed=false;
            if(result.startsWith("EXIT=0") && ShortRecordingPolicy.validQuery(verify))
                for(ShortRecordingPolicy.Item checked:ShortRecordingPolicy.parse(verify))
                    if(checked.id==id && checked.trashed) trashed=true;
            if(trashed) {
                Log.i(TAG,"Auto-cleaned short recording (system trash)");
                Prefs.event(context,"已自动移入回收站：不足 5 秒的录屏");
            } else Log.w(TAG,"Short recording cleanup not confirmed; retained");
        }
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
            String result = current.runCommand(command);
            if (result.startsWith("ERROR:")) Log.w(TAG, "Privileged command error: " + result);
            return result;
        } catch (RemoteException error) {
            Log.e(TAG, "Privileged command binder error", error);
            synchronized (this) { service = null; }
            return "ERROR: " + error.getMessage();
        }
    }
}
