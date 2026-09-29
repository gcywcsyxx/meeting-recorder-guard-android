package com.codex.meetingrecorder;

import android.content.Context;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class PrivilegedService extends IPrivilegedService.Stub {
    public PrivilegedService() {}

    @Keep
    public PrivilegedService(Context context) {}

    @Override
    public String runCommand(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final Process running = process;
            FutureTask<String> reader = new FutureTask<>(() -> readAll(running.getInputStream()));
            Thread readerThread = new Thread(reader, "meeting-command-output");
            readerThread.setDaemon(true);
            readerThread.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "ERROR: command timeout";
            }
            String output = reader.get(1, TimeUnit.SECONDS);
            return "EXIT=" + process.exitValue() + "\n" + output;
        } catch (Throwable error) {
            return "ERROR: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private String readAll(InputStream input) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
