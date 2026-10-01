package com.codex.meetingrecorder;

public final class StopPolicyTest {
    private static int checks;
    private static void check(boolean result) {
        if (!result) throw new AssertionError("Check " + (checks + 1));
        checks++;
    }
    public static void main(String[] args) {
        check(StopPolicy.END_CONFIRM_MS == 1500);
        check(StopPolicy.shouldSchedule(true, true, false));
        check(StopPolicy.shouldSchedule(false, true, false)); // service restart / retry
        check(!StopPolicy.shouldSchedule(false, false, false)); // manual recording untouched
        check(!StopPolicy.shouldSchedule(true, true, true)); // idle polls cannot postpone deadline
        check(StopPolicy.awaitingStop(true, 1000, 15999, 15000));
        check(!StopPolicy.awaitingStop(true, 1000, 16000, 15000)); // timed-out command retries
        check(!StopPolicy.awaitingStop(false, 1000, 1001, 15000));
        check(StopPolicy.confirmedStopped("EXIT=0\nisRecording: false"));
        check(!StopPolicy.confirmedStopped("ERROR: command timeout"));
        check(!StopPolicy.confirmedStopped("EXIT=0\nsuccess: false"));
        check(!StopPolicy.confirmedStopped("isRecording: true"));
        check(!StopPolicy.confirmedStopped("isRecording: false\nisRecording: true"));
        System.out.println("PASS: " + checks + " stop-policy regression checks");
    }
}
