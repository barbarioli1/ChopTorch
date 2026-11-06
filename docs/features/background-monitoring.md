# Feature: background monitoring

## User outcome

After the user starts monitoring, ChopTorch continues recognizing double chops while the app is backgrounded or the display is off. A persistent notification gives the user visibility and control.

## Service lifecycle

`GestureDetectionService` is a user-started foreground service. `MainActivity` starts it after the required camera permission flow. Activity pause or destruction does not stop monitoring; the notification provides the stop action.

The service is declared with `foregroundServiceType="specialUse"` and documents the continuous accelerometer/gyroscope use case through the special-use subtype property.

## Runtime responsibilities

While active, the service:

- Registers accelerometer and gyroscope listeners.
- Feeds samples through `MotionProcessor` and `GestureDetector`.
- Loads the current model from `GestureStore` when appropriate.
- Toggles the flashlight through `TorchController` after an accepted detection.
- Publishes a persistent notification.
- Handles notification actions such as opening the app, stopping monitoring, and flagging a false positive where supported.
- Acquires a partial wake lock for display-off processing and releases it when monitoring stops.

## Required capabilities

The manifest declares:

- `CAMERA` for Camera2 torch control.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` for the service.
- `POST_NOTIFICATIONS` for the persistent notification.
- `WAKE_LOCK` for continued CPU activity with the display off.

The camera flash and both motion sensors are optional device features. Startup should surface an actionable unavailable state when a device cannot support the complete feature.

## Torch behavior

`TorchController` owns the Camera2 interaction and reports the torch state through a callback. The detector does not directly access camera APIs. This keeps recognition testable and allows camera permission, unavailable hardware, and camera failures to be handled at the actuator boundary.

A recognition event is not equivalent to a confirmed torch state. The service should preserve that distinction in status and feedback handling, especially when camera access fails or another camera client owns the device.

## Battery and platform constraints

Continuous sensor sampling plus a partial wake lock can materially affect battery life. The persistent notification is part of the product contract, not incidental UI: it communicates that monitoring is active and exposes a user-controlled stop path. Android background limits, sensor batching, OEM power policies, and CPU throttling can change timing and latency.

## Verification

There are currently no Android instrumentation tests for service startup, permission denial, notification actions, wake-lock release, process recreation, or Camera2 failure. These are the highest-value integration tests for this feature.

## Action plan

### Status and scope

The user-started foreground service, notification actions, sensor listeners, torch controller, and partial wake lock are implemented in [GestureDetectionService.java](../../app/src/main/java/com/example/choptorch/GestureDetectionService.java). The existing automated root suite does not exercise Android services, Camera2, sensors, notifications, or wake locks. This plan specifies verification before any lifecycle rewrite; it does not promise that Android or OEM power management will preserve uninterrupted sampling.

### Verification status

Source review found no clearly evidenced service-resource cleanup defect: startup failures and explicit stops unregister sensor listeners and release a held wake lock through `stopMonitoring()`, while `onDestroy()` releases the torch callback and shuts down the trainer. The service's command paths return `START_NOT_STICKY`. The existing `bash tests/run-core-tests.sh` suite passed, including streaming detector coverage; it does not exercise Android lifecycle or resource behavior, so no Android runtime verification is claimed.

**Still pending manual Android/emulator and device gates:** exercise startup and each partial-start failure, repeated start/stop, notification actions, service destruction/process termination, permission denial/revocation, camera contention, wake-lock cleanup, screen-off behavior, and representative OEM power states as listed below. Record observed service, sensor, wake-lock, notification, and torch outcomes; force-stop must not be treated as continued monitoring or automatic restart.

### P0: Verify startup, stop, and failure cleanup

1. Record the baseline with `bash tests/run-core-tests.sh`; this covers detector/model helpers only. Use an Android emulator or device for service behavior. Prefer injected/fake boundaries for deterministic Android tests where available; add wrappers or dependencies only when required to observe system APIs.
2. Exercise `onStartCommand(ACTION_START)` from a visible activity with camera permission, a current model, both sensors, and flash available. Verify foreground notification creation, both sensor registrations, detector reset, partial wake-lock acquisition, and `isRunning()` becoming true only after successful setup.
3. Exercise each startup failure independently: missing sensor, camera permission, model, flash, failed accelerometer registration, failed gyroscope registration, and wake-lock acquisition error. Confirm partial registrations are undone, the wake lock is not left held, foreground state is removed, `running` is false, and `lastError` is actionable.
4. Exercise repeated start, explicit `ACTION_STOP`, notification Stop, and service destruction. Confirm a single active listener set, release of the wake lock, torch callback release, and notification removal. Verify `onDestroy()` cleanup is safe after an earlier stop.
5. Simulate a detector match and a torch access failure separately. Verify a recognition match causes one toggle request; distinguish that request from the later Camera2 callback/state. Confirm torch failure updates the error path without claiming the torch changed. Verify false-positive eligibility is recorded only when the code observes the torch as on, and test that callback timing before changing this behavior.

**Acceptance:** All service resources are released on every tested stop/failure path; no duplicate listener or wake lock is acquired; user-visible active status reflects actual service startup. Preserve `START_NOT_STICKY`: a killed/stopped service is not silently restarted.

### P1: Verify Android lifecycle and permissions

6. Test camera permission granted, denied, and revoked before a start. Test notification permission granted, denied, and previously denied on Android 13+. A notification-permission denial does not itself block foreground-service startup, but visibility differs by platform; verify the notification and disclose degraded visibility accurately.
7. Test notification actions: open app, Stop, and development-only False positive. Verify the false-positive action is absent in shipping and does not retrain there. Check notification refresh when confidence/error state changes.
8. Test unavailable flash, camera contention by another app, and torch callback delay/failure on real hardware. Confirm monitoring reports failure and does not equate a requested toggle with confirmed flashlight state.
9. Distinguish system process kill from user force-stop. Confirm `START_NOT_STICKY` does not restart the service after system termination. Force-stop is an explicit user/system termination and must not be expected to leave monitoring active. Reopen the app and confirm its UI reflects the service's actual state.

### P1: Measure power and device behavior

10. On representative API 26, API 33+, target API and current supported OEM devices, test screen-off monitoring, battery saver, Doze, Low Power Standby where available, and OEM battery controls. Record sensor delivery gaps, missed gestures, false activations, torch outcomes, service state, battery drain versus idle baseline, device/OS/build, and test duration.
11. Follow the proposed user guidance and acceptance cases in [production UI and battery settings](production-ui-and-battery.md); use [Android and Google Play readiness](android-play-readiness.md) for the broader device matrix and release evidence. Do not add a direct battery-optimization exemption request as a substitute for these tests.

**Release evidence:** Attach Android test results and device observations to the release record. Clearly list untested OEMs and power states; do not claim a foreground service or wake lock guarantees continuous sensor events.
