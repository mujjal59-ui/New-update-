# LXR--AI v2.41.0 (versionCode 59) Release Notes

- **Complete Elimination of "Slide again to unlock" on All 3 Voice Models & Home Transitions**:
  - Extended `HOME_EXIT_UNLOCK_SUPPRESSION_MS` to `6000ms` and hooked `LockVaultManager.markHomeOrAppExitTransition()` into `MainActivity` (`onUserLeaveHint`, `onPause`, `onStop`, Home `BackHandler`, Voice Model selection), `GeminiLive.switchModelAndReconnect()`, and `RiyaAccessibilityService.onAccessibilityEvent()` (Launcher & app-exit transitions).
  - Hardened `LockVaultManager` and `GeminiLive.runToolUiSynchronized` so unlocked screens, Home/Launcher views, and transitions NEVER trigger accessibility swipes or double-taps (`ALREADY_UNLOCKED` enforced strictly whenever OS `KeyguardManager` is unlocked).
- **Direct Phone Alarm Set + Dismiss/Turn Off + `Alarms & reminders` Settings Flow**:
  - Fixed `scheduleExactReminder` Clock dispatch to use `LxrAppDiscoveryEngine.launchActivitySafely(...)` (working reliably under Android 14–16 Background Activity Launch restrictions).
  - Added `dismiss_alarm` tool (`LxrDeviceAndHardwareEngine.dismissOrCancelAlarm`) supporting time (`hour`/`minute`), label (`title`), next alarm, or `dismiss_all` — stopping active alarm tones/notifications, cancelling `AlarmManager` `PendingIntent`s, updating persisted status to `DISMISSED`, and sending `AlarmClock.ACTION_DISMISS_ALARM` to the phone's Clock app.
  - Upgraded `openExactAlarmSettings(ctx)` using `launchActivitySafely` with fallback chain (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` package URI -> general `Alarms & reminders` list -> App info) and Android 16 Restricted Settings guidance.
- **Preserved Features**: Retained `CREATOR_IDENTITY_BN` verbatim, personality, `0s/8s/15s` Reply Delay & `Unlimited/20/50` Reply Limit, all 3 animated themes, all 3 Voice Models, Activity Monitor, Smart Lock Detection, and `com.aistudio.riyaai.krmxvp` package ID for clean in-place upgrade.
