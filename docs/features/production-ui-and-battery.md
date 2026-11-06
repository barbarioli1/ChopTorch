# Feature: production UI and battery settings

## Scope and status

This document describes the existing shipping-mode UI in [MainActivity](../../app/src/main/java/com/example/choptorch/MainActivity.java) and [GestureDetectionService](../../app/src/main/java/com/example/choptorch/GestureDetectionService.java). Optional battery guidance is implemented in `MainActivity` and its pending-start transitions pass the pure-Java core test; Android build, device/OEM, battery-measurement, and Play-policy checks remain pending. Shipping mode means building with `-Pshipping=true`, which sets `ENABLE_TRAINING` to false; it is not synonymous with a signed release build.

## Existing production UI

The app opens to a portrait, vertically scrolling home screen with the title "ChopTorch" and subtitle "Two chops. One flashlight." It uses a dark background with two sections:

| Area | Visible content and behavior |
| --- | --- |
| Flashlight | Shows `FLASHLIGHT ON` or `FLASHLIGHT OFF`, a short double-chop instruction, and `Toggle flashlight manually`. The button requests camera permission if needed, checks for a flash, then toggles it. |
| Background gesture detector | Shows detector status, the latest score and `threshold 82% + 2 peaks`, or the last service error. The button starts or stops background detection and changes its label accordingly. An explanatory text mentions lock-screen operation, the notification, and battery use. |

Detector status is chosen in this order: missing accelerometer or gyroscope; missing or retraining model; monitoring active; otherwise detector stopped. The start/stop button is disabled if sensors or a current model are unavailable. Status and score refresh approximately every 650 ms while the activity is resumed. The torch state comes from the camera torch callback.

When monitoring starts, the service displays an ongoing, low-importance notification titled `ChopTorch · double-chop listening`, with confidence or retraining text. Tapping the notification opens the app; `Stop` stops monitoring. Notification visibility depends on Android version, system settings, and permission. Training, false-positive feedback, model export, and their associated notification action are development-only and are absent from shipping UI.

### Existing permission and error flow

1. Start checks sensors and model, then requests `CAMERA` if missing. A denied request shows a toast and does not start monitoring. Manual toggle uses the same camera request but does not start monitoring.
2. With camera permission, the activity initializes the torch and checks for a flash. No flash or a camera error produces a toast.
3. The activity starts the foreground service. On Android 13+ it then requests `POST_NOTIFICATIONS` if not already granted. Notification permission is not a prerequisite for starting a foreground service; a denial can limit notification visibility. The current callback does not handle its result explicitly.
4. The service independently checks sensors, camera permission, model, flash, and sensor registration. Failures stop the service and expose a last-error message on the home screen.

The current app does **not** check battery optimization, prompt for an exemption, or open battery settings. Monitoring is explicitly started by the user and is not automatically restarted after the system kills the service.

## Proposed: battery guidance when enabling monitoring

### User outcome

When the user starts background monitoring, explain relevant power restrictions and offer a way to adjust the app's battery settings. The choice remains with the user, and monitoring can proceed without an exemption with a warning that reliability may vary.

### Proposed sequence

1. After the user taps `Start background detection`, validate sensors and model. Ask for camera permission only if needed; on denial, show a clear explanation and keep monitoring stopped.
2. After camera permission and flash availability are confirmed, query `PowerManager.isIgnoringBatteryOptimizations(getPackageName())`. This reports the app's power-allowlist status, not a universal reading of every OEM's `Unrestricted`, `Optimized`, or `Restricted` setting.
3. If the app is not allowlisted, show a contextual, dismissible explanation: background detection may be interrupted by power management, changing battery settings may improve reliability, and continuous monitoring can increase battery use. Offer `Open battery settings` and `Continue without changing`.
4. On `Open battery settings`, open the app details settings using `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` and the package URI so the user can navigate to Battery and select `Unrestricted` or the device's equivalent where offered. If unavailable, offer `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; handle missing settings activities and provide manual navigation instructions. Settings labels and paths vary by device; neither intent guarantees a specific battery screen.
5. On return, recheck allowlist status, camera permission, sensors, and model. Do not infer success from returning from Settings or equate allowlist membership with a verified OEM mode. Show the observed status or an honest unknown/unchanged state. Resume the pending user-initiated start only while the activity is visible and the request is still valid; otherwise require another tap. Do not create a prompt loop.
6. If the user declines or leaves settings unchanged, offer to continue with the current settings and a reliability warning. Do not block manual flashlight control. Request notification permission on Android 13+ when appropriate, without conflating it with camera permission or battery settings; denial must not be reported as a successful notification grant.
7. Start the foreground service once per accepted request, and show active state only after the service actually starts. Preserve or cancel the pending action safely across activity recreation; returning from Settings must not start a service after an explicit cancellation or stop.

`isIgnoringBatteryOptimizations` only checks the platform power allowlist. It does not prove that the user selected a particular OEM UI option, that battery saver is off, or that background operation is guaranteed. Doze, Low Power Standby, OEM restrictions, sensor delivery, and process termination can still affect operation; even foreground-service wake locks can be ignored under Low Power Standby. Do not claim that changing settings guarantees detection.

The proposed default is a settings link, not a direct exemption request. Android documents `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` as requiring `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and Google Play restricts direct requests to eligible use cases. Do not add that permission or use the direct request without documented technical necessity and a current policy review; eligibility for ChopTorch is not established.

### Acceptance and test cases

- Already allowlisted: no optimization prompt; normal camera, notification, and service flow proceeds.
- Not allowlisted: the user sees the explanation and can open settings or continue without changing it.
- Changed, unchanged, canceled, unavailable settings, and OEM-specific settings: recheck on return, report only what is observable, avoid repeated prompts, and allow a deliberate retry.
- Camera denied, revoked while in Settings, or flash unavailable: no monitoring start; manual control remains available when its own prerequisites are met.
- Notification permission granted, denied, or already denied: no false grant state; confirm Android's notification/task-manager behavior on each supported version.
- Activity recreation, backgrounding, repeated taps, and user stop during the flow: no duplicate service starts or unintended restart.
- Test physical devices across supported Android versions and OEM battery interfaces with the screen locked, Doze, Low Power Standby where supported, and settings both changed and unchanged. Measure detection and battery usage; document deviations.

## References

- [Android Doze, App Standby, and exemption policy](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [PowerManager allowlist and Low Power Standby APIs](https://developer.android.com/reference/android/os/PowerManager)
- [Android Settings intents](https://developer.android.com/reference/android/provider/Settings)
- [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)

## Action plan

### Status and scope

The home screen and permission flow described above are implemented. The optional battery-guidance flow is implemented and its state transitions pass `bash tests/run-core-tests.sh`; this does not verify Android builds, both build variants, real-device/OEM behavior, or battery impact. Those checks and current Play-policy review remain pending. The battery check is in the shared background-monitoring start path; training, false-positive, and export controls remain development-only. Do not implement this as an automatic exemption request or assume an OEM's "Unrestricted" label is observable through the Android power-allowlist API.

### P0: Implement the user-controlled battery guidance in both variants

1. **Capture existing behavior first.** Trace `MainActivity.changeListening()`, `askForCamera()`, `onRequestPermissionsResult()`, `completeCameraAction()`, `onResume()`, and `updateLabels()`. Preserve manual flashlight behavior, sensor/model prechecks, camera permission requirements, and shipping/dev feature gates. The current notification permission request is made after service start and its result is not explicitly handled.
2. **Model the pending start explicitly.** Use a small state machine for idle, waiting for camera permission, battery guidance, returning from settings, and service start. Ignore duplicate start taps while a request is pending. A dismissed dialog, canceled settings visit, activity/process loss, explicit stop, or invalidated prerequisite must clear the pending request; returning from Settings must not start monitoring unexpectedly. Persist only enough transient state for safe activity recreation, and require a fresh user action when the original intent is ambiguous.
3. **Check the supported platform state.** With the app minimum SDK 26, query `PowerManager.isIgnoringBatteryOptimizations(packageName)` when the user requests background monitoring and camera/flash prerequisites are satisfied. If true, continue without the battery dialog. If false, explain possible background limitations and the battery cost, then offer `Open battery settings` and `Continue without changing`.
4. **Open settings safely.** Prefer `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with the app package URI. If unavailable, optionally open `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; catch `ActivityNotFoundException` and offer manual navigation. Neither intent promises a specific OEM battery screen. Never infer that a particular "Unrestricted" setting changed solely because Settings returned.
5. **Revalidate on return.** Recheck camera permission, sensor/model availability, and the platform allowlist value. Report only observable state; do not loop into another dialog. If unchanged, let the user choose whether to continue under the current restrictions or cancel. If camera permission was revoked, flash/model/sensors are unavailable, or the request was canceled, do not start the service.
6. **Keep notification permission distinct.** On Android 13+, handle the `POST_NOTIFICATIONS` result explicitly if requesting it. Denial must not be confused with camera denial or battery status; do not block service startup solely on this permission, and explain that notification visibility may be limited. Ensure service-active UI is shown only after the service actually starts.
7. **Preserve dev/shipping separation.** Run the same start-flow acceptance tests in default dev and `-Pshipping=true` builds. Confirm battery guidance and background monitoring work in both; confirm training/feedback/export remain dev-only. This is a shared behavior change, not a shipping-only feature.

**Acceptance:** Already-allowlisted users are not prompted. Non-allowlisted users can open settings, continue without changing them, or cancel. No path starts more than one service or starts after cancellation. Permission and settings failures have clear recovery. Manual flashlight control is unaffected.

### P1: Test the state machine and platform variations

8. Add Android-aware tests using a fake power-state provider and controllable activity-result flow where practical. Cover allowlisted/not allowlisted, settings changed/unchanged/unavailable, user cancel, permission denial/revocation, missing flash/sensors/model, notification grant/denial, repeated taps, activity recreation, and explicit stop while pending. Do not put these in `tests/run-core-tests.sh` unless they can run without Android framework dependencies.
9. On real devices from the readiness matrix, verify settings destination and labels by OEM, screen-off detection with the app allowlisted and not allowlisted, Doze, battery saver, and Low Power Standby where available. Record that `isIgnoringBatteryOptimizations()` reports only platform allowlist membership; it does not confirm OEM mode, disable battery saver, or guarantee operation.
10. Keep the default interaction advisory. Do not add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` unless a separate policy review establishes eligibility and the user explicitly authorizes that implementation.

**Release evidence:** Test results must cover both build variants and identify the Android versions/OEMs tested. Do not claim that a settings choice guarantees recognition or uninterrupted monitoring.