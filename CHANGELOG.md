# Changelog

## 0.4.8

- Reduce call-end confirmation from 20 seconds to 1.5 seconds; recording starts remain immediate.
- Retry failed or timed-out stops instead of forgetting recorder ownership.
- Recover pending auto-recordings after guard service restarts.
- Require explicit `isRecording: false` before reporting stop success; command errors are not success.
- Prevent stale status callbacks from starting a recording after call exit; serialize stop/new-call recovery.
- No new capture permissions, network access, or data collection.

Build and regression checks do not replace a real-device call/meeting test. End detection still depends on Android communication audio mode (and the existing Zoom activity fallback).
