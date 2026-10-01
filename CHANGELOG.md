# Changelog

## 0.4.9

- Automatically trash finalized, positive-duration recordings shorter than 5 seconds created during an app-controlled session. Exactly 5 seconds is retained.
- Freeze candidate media IDs at stop; historical videos, other folders, pending files and unknown durations are not removed.
- Retry delayed media metadata at 2, 5, 12 and 30 seconds without blocking meeting polling.
- Confirm start signals for 1.5 seconds and add a 6-second restart cooldown to reduce rapid Zoom start/stop loops; rapid call-end stopping remains.
- Cleanup uses system trash rather than permanent deletion. No new permissions or network access.

## 0.4.8

- Reduce call-end confirmation from 20 seconds to 1.5 seconds; recording starts remain immediate.
- Retry failed or timed-out stops instead of forgetting recorder ownership.
- Recover pending auto-recordings after guard service restarts.
- Require explicit `isRecording: false` before reporting stop success; command errors are not success.
- Prevent stale status callbacks from starting a recording after call exit; serialize stop/new-call recovery.
- No new capture permissions, network access, or data collection.

Build and regression checks do not replace a real-device call/meeting test. End detection still depends on Android communication audio mode (and the existing Zoom activity fallback).
