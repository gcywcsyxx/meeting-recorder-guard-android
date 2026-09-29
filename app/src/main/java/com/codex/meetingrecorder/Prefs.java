package com.codex.meetingrecorder;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "meeting_guard";
    private static final String ARMED = "armed";
    private static final String STARTED_BY_US = "started_by_us";
    private static final String LAST_EVENT = "last_event";

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isArmed(Context context) {
        return get(context).getBoolean(ARMED, true);
    }

    static void setArmed(Context context, boolean value) {
        get(context).edit().putBoolean(ARMED, value).apply();
    }

    static boolean startedByUs(Context context) {
        return get(context).getBoolean(STARTED_BY_US, false);
    }

    static void setStartedByUs(Context context, boolean value) {
        get(context).edit().putBoolean(STARTED_BY_US, value).apply();
    }

    static void event(Context context, String value) {
        get(context).edit().putString(LAST_EVENT, value).apply();
    }

    static String lastEvent(Context context) {
        return get(context).getString(LAST_EVENT, "尚无事件");
    }
}
