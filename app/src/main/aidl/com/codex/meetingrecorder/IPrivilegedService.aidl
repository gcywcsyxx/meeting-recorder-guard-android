package com.codex.meetingrecorder;

interface IPrivilegedService {
    String runCommand(String command);
    void destroy();
}
