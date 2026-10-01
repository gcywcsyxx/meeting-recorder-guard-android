package com.codex.meetingrecorder;

/** Pure stop decisions, shared with the regression harness. */
final class StopPolicy {
    static final long END_CONFIRM_MS = 1500;
    static boolean shouldSchedule(boolean active, boolean owned, boolean pending) {
        return (active || owned) && !pending;
    }
    static boolean awaitingStop(boolean inFlight, long startedAt, long now, long timeout) {
        return inFlight && now - startedAt < timeout;
    }
    static boolean confirmedStopped(String output) {
        return output.contains("isRecording: false") && !output.contains("isRecording: true");
    }
    private StopPolicy() {}
}
