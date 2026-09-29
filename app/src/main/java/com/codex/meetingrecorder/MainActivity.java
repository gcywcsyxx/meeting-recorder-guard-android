package com.codex.meetingrecorder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST = 48123;
    private TextView shizukuStatus;
    private TextView guardStatus;
    private TextView recorderStatus;
    private TextView lastEvent;
    private Switch armedSwitch;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST) {
                    RecorderController.get(this).bindIfReady();
                    runOnUiThread(this::refresh);
                }
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        Shizuku.addRequestPermissionResultListener(permissionListener);
        RecorderController.get(this).bindIfReady();
        if (Prefs.isArmed(this)) startGuardService();
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(28), pad, dp(28));
        content.setBackgroundColor(Color.rgb(247, 248, 252));

        TextView title = text("会议录音守护", 28, Color.rgb(26, 31, 44));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title);
        TextView subtitle = text("自动守护主流会议与通话应用，录制可靠性优先", 15, Color.DKGRAY);
        subtitle.setPadding(0, dp(6), 0, dp(20));
        content.addView(subtitle);

        armedSwitch = new Switch(this);
        armedSwitch.setText("自动守护已开启");
        armedSwitch.setTextSize(18);
        armedSwitch.setChecked(Prefs.isArmed(this));
        armedSwitch.setPadding(dp(16), dp(14), dp(16), dp(14));
        armedSwitch.setBackgroundColor(Color.WHITE);
        armedSwitch.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setArmed(this, checked);
            button.setText(checked ? "自动守护已开启" : "自动守护已暂停");
            if (checked) startGuardService();
            else stopService(new Intent(this, MeetingGuardService.class));
        });
        content.addView(armedSwitch, matchWrap());

        content.addView(section("运行条件"));
        shizukuStatus = statusLine();
        guardStatus = statusLine();
        recorderStatus = statusLine();
        content.addView(shizukuStatus);
        content.addView(guardStatus);
        content.addView(recorderStatus);

        Button shizuku = button("授权录制控制");
        shizuku.setOnClickListener(v -> requestShizuku());
        content.addView(shizuku, matchWrap());

        Button battery = button("允许后台持续运行");
        battery.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable error) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        content.addView(battery, matchWrap());

        content.addView(section("录制验证"));
        Button test = button("开始测试录制");
        test.setOnClickListener(v -> {
            recorderStatus.setText("正在启动并验证……");
            RecorderController.get(this).start((success, recording, detail) -> runOnUiThread(() -> {
                if (success && recording) {
                    Prefs.setStartedByUs(this, true);
                    Prefs.event(this, "手动测试：录制启动成功");
                    toast("录制已经启动，请稍后点击停止并保存");
                } else {
                    Prefs.event(this, "手动测试：录制启动失败");
                    toast("启动失败，请检查授权");
                }
                refresh();
            }));
        });
        content.addView(test, matchWrap());

        Button stop = button("停止录制并保存");
        stop.setOnClickListener(v -> RecorderController.get(this).stop((success, recording, detail) -> runOnUiThread(() -> {
            if (success) {
                Prefs.setStartedByUs(this, false);
                Prefs.event(this, "手动测试：录制已停止并保存");
                toast("录像已保存到相册");
            } else toast("停止失败，请使用系统录屏按钮停止");
            refresh();
        })));
        content.addView(stop, matchWrap());

        content.addView(section("最近状态"));
        lastEvent = text("", 15, Color.DKGRAY);
        lastEvent.setPadding(dp(14), dp(14), dp(14), dp(14));
        lastEvent.setBackgroundColor(Color.WHITE);
        content.addView(lastEvent, matchWrap());

        TextView note = text("工作方式：每秒检测 Zoom 会议界面和主流会议、通话应用的通信音频状态，支持 Chrome、Edge、Firefox 等浏览器网页通话。检测到会议即启动系统录屏；通话结束 20 秒后停止并保存。", 13, Color.GRAY);
        note.setPadding(0, dp(18), 0, 0);
        content.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private void refresh() {
        boolean shizukuReady = RecorderController.get(this).isShizukuReady();
        shizukuStatus.setText((shizukuReady ? "✓" : "!") + " 录制控制：" + (shizukuReady ? "已就绪" : "未授权或 Shizuku 未运行"));
        long lastPoll = MeetingGuardService.lastSuccessfulPollElapsed();
        long age = lastPoll == 0 ? Long.MAX_VALUE : android.os.SystemClock.elapsedRealtime() - lastPoll;
        guardStatus.setText((age < 10000 ? "✓" : "!") + " 后台检测：" +
                (age < 10000 ? "运行中" : "尚未完成检查，请确认 Shizuku"));
        lastEvent.setText(Prefs.lastEvent(this));
        RecorderController.get(this).getStatus((ok, recording, detail) -> runOnUiThread(() ->
                recorderStatus.setText((ok ? "✓" : "!") + " 系统录屏：" + (ok ? (recording ? "正在录制" : "当前未录制") : "状态不可读"))));
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (launch != null) startActivity(launch);
                else toast("请先安装并启动 Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_REQUEST);
            } else {
                RecorderController.get(this).bindIfReady();
                toast("录制控制已经授权");
                refresh();
            }
        } catch (Throwable error) {
            toast("Shizuku 尚未就绪");
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    private void startGuardService() {
        Intent intent = new Intent(this, MeetingGuardService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private TextView section(String value) {
        TextView view = text(value, 18, Color.rgb(26, 31, 44));
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(24), 0, dp(10));
        return view;
    }

    private TextView statusLine() {
        TextView view = text("", 15, Color.DKGRAY);
        view.setPadding(dp(14), dp(11), dp(14), dp(11));
        view.setBackgroundColor(Color.WHITE);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
