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

## Screen-locked sensor delivery reliability

### Status

**Implemented; automated verification complete; post-change device verification pending.** The screen-lock buffering hypothesis remains unconfirmed. The code facts below were initially audited at commit `64451a2`; the implementation is additive and preserves the detector/model settings.

Verification completed:

- The pre-change core baseline passed all four existing tests. The post-change core suite passed all five tests, including `SensorDeliveryGuardTest`; the streaming test still reports `[3360, 7360]`. This Windows checkout stores the runner as CRLF, so verification used a temporary LF-normalized copy and left the tracked runner's line endings unchanged.
- `assembleDebug assembleDebugAndroidTest`, `assembleDebug -Pshipping=true`, and `lintDebug` all completed successfully. Lint reported four warnings: `LockedOrientationActivity`, `DiscouragedApi`, `WakelockTimeout`, and `ObsoleteSdkInt`, all at existing manifest, wake-lock, or resource locations; no lint errors were reported.
- A pre-change dev APK and passing 2/2 instrumentation results already exist for the same moto g53 5G (Android 14/API 34) in `test-results/device/20261105-182035-ZF524979X8-dev/`. Its source revision is an ancestor of the baseline commit; the only relevant source delta is a package-private detector test seam, with production scoring unchanged.
- Post-change device-script attempts for dev and shipping stopped at preflight with exit code 2 because serial `ZF524979X8` was not connected. No post-change APK was installed, and no service diagnostics or physical gesture/battery tests were collected. Existing instrumentation tests launch `MainActivity` but do not start the monitoring service.

### Problem and hypothesis

**Observed by the user.** After the phone stays locked for a long time:

1. The first double chop often does nothing.
2. The user repeats the gesture.
3. The flashlight turns on after a noticeable delay.
4. Sometimes it then turns off again without another intentional gesture.

Unrestricted battery usage did not fix it.

**Hypothesis (not confirmed).** While the screen is off, Android may suspend the CPU. Non-wake-up accelerometer and gyroscope events can then be held by the sensor hardware and delivered in a burst when the CPU wakes, still carrying their original timestamps. The detector would treat those old samples as live motion.

This fits the symptom because the cooldown is measured in event time (see audit row 7). Two buffered gestures recorded several seconds apart pass the 1,600 ms check even if they are processed milliseconds apart, so the torch toggles ON then OFF.

A second possibility is that sensor delivery simply pauses and resumes. Step 9 logs separate the two cases. Do not state a cause in release notes until the logs show it.

### Constraints (do not change)

- Neural-network architecture, weights, input features, Kalman filtering, and feature normalization.
- Threshold `0.82`, prediction interval `160 ms`, feature window `1,900 ms`, double-chop validation, and cooldown `1,600 ms`.
- The requested sampling period of `20000` microseconds and the model assets.
- Existing training examples and recorded feedback.
- The foreground service, its notification, and the unrestricted-battery guidance.
- No retraining, no new peak-tracking algorithm, and no unsupported power-management bypass.

### Pre-change implementation audit

These answers describe the source before this change. The last column says how to confirm runtime behavior on a device.

| # | Question | Finding in the current code | Confirm at runtime |
| --- | --- | --- | --- |
| 1 | Wake-up or non-wake-up sensors? | `onCreate()` calls `getDefaultSensor(TYPE_ACCELEROMETER)` and `getDefaultSensor(TYPE_GYROSCOPE)` with no wake-up argument. The default is usually a non-wake-up sensor, but a device may return a wake-up one. | `wakeUp=` in the inventory log (Step 7). |
| 2 | Is batching enabled? | `registerListener(this, sensor, 20000)` uses the three-argument overload, which requests no batching (`maxReportLatencyUs` of 0). Sampling is 20,000 µs. | `adb shell dumpsys sensorservice`. |
| 3 | Which thread runs callbacks? | No `Handler` is passed, so callbacks run on the main looper. Kalman fusion, feature extraction, the model, and `torch.toggle()` all run inside `onSensorChanged()` on the main thread. | Not needed; keep as is. |
| 4 | Does the service survive long screen-off? | Unknown. All paths return `START_NOT_STICKY`, and nothing logs process death. | Test C and `dumpsys activity services`. |
| 5 | Is a partial wake lock held? | Yes: `newWakeLock(PARTIAL_WAKE_LOCK, "ChopTorch:IMU")`, `acquire()` with no timeout, released in `stopMonitoring()`. | `adb shell dumpsys power` while locked. |
| 6 | Are timestamps preserved? | The accelerometer `event.timestamp` goes to `MotionProcessor`, which stores `tNs / 1_000_000` as `MotionSample.timeMs`. The gyroscope timestamp is ignored: `onGyroscope()` keeps only the latest values. Nothing compares any timestamp with the current time. | Step 9 logs. |
| 7 | What does the cooldown use? | `GestureDetector` checks `sample.timeMs - lastTriggeredAt < 1600`, so it uses event time, not activation time. | Test D and Test F. |
| 8 | Does history span long interruptions? | `MotionProcessor` resets its Kalman state when the step is not in `(0, 0.5 s]`, but `GestureDetector.history` is not cleared. Only entries older than 2,060 ms (window plus 160) are dropped. Shorter gaps leave pre-gap samples in the window. `GestureDetector.reset()` also zeroes `lastTriggeredAt`, so it must **not** be used for recovery. | Test E and Test F. |
| 9 | Is stale-event rejection present? | No. | Not applicable. |

### Design

All new decision logic is pure Java so the root `javac` suite can test it.

1. **`SensorDeliveryGuard`** (new). One instance per sensor. For each event it compares the event timestamp with `SystemClock.elapsedRealtimeNanos()` and returns `ACCEPT`, `DROP_STALE`, or `DROP_OUT_OF_ORDER`. It also tracks recovery and enforces a wall-clock toggle cooldown.
2. **`SensorTimingStats`** (new). Builds a throttled one-line timing summary. It holds numbers only, never raw sensor values.
3. **`GestureDetector.resetForRecovery()`** (new method). Clears motion state, history, prediction time, consecutive hits, and score. It **keeps** `lastTriggeredAt`, so recovery cannot bypass the 1,600 ms cooldown.
4. **Service integration.** Every event passes through the guard before it reaches the detector. Torch toggles also pass the wall-clock cooldown.
5. **Diagnostics.** Verbose timing logs run only in development builds (`BuildConfig.ENABLE_TRAINING`). Shipping builds keep only the rare warning lines.

Rules the guard implements:

| Situation | Result |
| --- | --- |
| Event age is between -100 ms and 500 ms | Accept. This also marks the sensor clock as trusted. |
| Event age is above 500 ms, or more than 100 ms in the future | Drop as stale. The first drop of an episode requests **one** detector recovery reset. Later drops in the same episode do not. |
| Timestamp is not newer than the last accepted one | Drop as out of order. No reset. |
| Accepted timestamp is more than 500 ms after the previous accepted one | Accept and request one recovery reset. |
| First accepted sample after a stale episode | Accept. Recovery ends with no second reset. |
| 100 early stale samples with nearly constant age (spread of 250 ms or less), and no plausible age ever seen | Fail open: assume the sensor clock uses a different base, so stop rejecting. Without this, such a device would never detect anything. |
| Stale samples for 30 s continuously | Fail open: this is a steady delay, not a replay burst. |
| Toggle requested less than 1,600 ms after the last toggle in real elapsed time | Ignore the detection. |

Known limits of the design:

- A burst whose newest events are under 500 ms old keeps those newest events. They are real recent motion, and the detector still needs more than 1,550 ms of history before it can fire.
- A steady delay is indistinguishable from a clock-base mismatch, so the guard fails open and behaves as today.
- Stale rejection removes false replays. It does **not** make the first chop after a long lock respond faster. If the hardware or OS holds sensor data until wake, that chop is lost. See the P2 gate below.

### Implementation runbook

This runbook is written for an implementer who follows steps literally. Do one step at a time and run its **Verify** before the next.

#### Ground rules

1. Do not change any value listed under "Constraints". In particular, do not edit `THRESHOLD`, `WINDOW_MS`, `160`, or `1600` in `GestureDetector`, and do not touch `TinyNeuralNet`, `GestureFeatures`, `AngleKalman`, `MotionProcessor`, `MotionSample`, `GestureStore`, or any model or training asset.
2. Make additive edits only. Other work may be uncommitted in this tree. Do not run `git checkout`, `git restore`, `git reset`, or `git stash`.
3. Do not commit, push, or edit `.github/workflows/*`, `versionCode`, `versionName`, or signing configuration.
4. Do not put sensor values, secrets, or personal data in logs, files, or screenshots.
5. Use PowerShell 5.1 from the repository root. Run one command at a time. Use `;` to chain commands, never `&&`.
6. If a Verify fails twice, stop and report the step, the command, and the exact error text. Do not guess a fix.
7. If a Find text in Step 7 does not match exactly once, stop. The file has changed since this plan was written.
8. Set these in every new terminal before Gradle commands:

   ```powershell
   $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Programs\ChopTorchToolchain\Android\Sdk'
   $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
   ```

#### Step 0: Record the baseline

```powershell
git status --short
bash tests/run-core-tests.sh
```

Expect four `PASS` lines (`CoreSmokeTest`, `SeedDedupTest`, `MonitoringStartFlowTest`, `StreamingDetectorTest`).

With the phone connected, unlocked, and awake, run the baseline build through the device script. It saves the baseline APK as `app.apk` in the evidence folder, which you need later for the battery comparison:

```powershell
.\scripts\device-test.ps1 -Serial <serial> -Variant dev
```

Expect `Device test exit code: 0`. Write down the evidence folder path.

#### Step 1: Confirm the audit against the current source

Run these searches and compare with the expected content. If any result differs, stop and report it.

```powershell
Select-String -Path app\src\main\java\com\example\choptorch\GestureDetectionService.java -Pattern 'getDefaultSensor|registerListener|newWakeLock|acquire\(\)|START_NOT_STICKY'
Select-String -Path app\src\main\java\com\example\choptorch\GestureDetector.java -Pattern 'lastTriggeredAt|public void reset|THRESHOLD|WINDOW_MS|1600|160'
Select-String -Path app\src\main\java\com\example\choptorch\MotionProcessor.java -Pattern 'dt <= 0|dt > 0.5'
```

Expected:

- `getDefaultSensor(Sensor.TYPE_ACCELEROMETER)` and `getDefaultSensor(Sensor.TYPE_GYROSCOPE)` with no second argument.
- Two `registerListener(this, ..., 20000)` calls with three arguments.
- One `newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChopTorch:IMU")` and one `wakeLock.acquire()`.
- `reset()` in `GestureDetector` assigns `lastTriggeredAt = 0`.
- `MotionProcessor` contains `if (dt <= 0 || dt > 0.5)`.

#### Step 2: Create `SensorDeliveryGuard.java`

Create `app/src/main/java/com/example/choptorch/SensorDeliveryGuard.java` with exactly this content:

```java
package com.example.choptorch;

/** Pure-Java guard that rejects stale, out-of-order, or gapped IMU samples and enforces a wall-clock toggle cooldown. */
public final class SensorDeliveryGuard {
    public enum Decision { ACCEPT, DROP_STALE, DROP_OUT_OF_ORDER }

    public static final long DEFAULT_STALE_NS = 500_000_000L;
    public static final long MAX_GAP_NS = 500_000_000L;
    public static final long TOGGLE_COOLDOWN_NS = 1_600_000_000L;
    private static final long FUTURE_TOLERANCE_NS = 100_000_000L;
    private static final int TIME_BASE_PROBE_LIMIT = 100;
    private static final long TIME_BASE_MAX_AGE_SPREAD_NS = 250_000_000L;
    private static final long STALE_GIVE_UP_NS = 30_000_000_000L;

    private final long staleNs;
    private long lastAcceptedNs;
    private long staleSinceNs;
    private boolean recovering;
    private boolean resetRequested;
    private boolean timeBaseTrusted;
    private boolean timeBaseUnreliable;
    private int probeCount;
    private long probeMinAgeNs, probeMaxAgeNs;
    private boolean toggled;
    private long lastToggleNs;

    public SensorDeliveryGuard() {
        this(DEFAULT_STALE_NS);
    }

    public SensorDeliveryGuard(long staleNs) {
        this.staleNs = staleNs;
    }

    /** Both timestamps must come from the elapsed-realtime clock base. */
    public Decision check(long eventNs, long nowNs) {
        long ageNs = nowNs - eventNs;
        boolean stale = false;
        if (!timeBaseUnreliable) {
            boolean plausible = ageNs >= -FUTURE_TOLERANCE_NS && ageNs <= staleNs;
            if (plausible) {
                timeBaseTrusted = true;
                probeCount = 0;
                staleSinceNs = 0;
            } else {
                stale = true;
                if (staleSinceNs == 0) staleSinceNs = nowNs;
                if (!timeBaseTrusted && probeAgesLookConstant(ageNs)) {
                    // A constant huge age means the sensor clock base differs; fail open.
                    timeBaseUnreliable = true;
                    stale = false;
                } else if (nowNs - staleSinceNs >= STALE_GIVE_UP_NS) {
                    // A replay burst ends within seconds; 30 s of continuous lag is a steady delay, not a replay.
                    timeBaseUnreliable = true;
                    stale = false;
                }
            }
        }
        if (stale) {
            if (!recovering) {
                recovering = true;
                resetRequested = true;
            }
            return Decision.DROP_STALE;
        }
        if (lastAcceptedNs != 0 && eventNs <= lastAcceptedNs) return Decision.DROP_OUT_OF_ORDER;
        if (!recovering && lastAcceptedNs != 0 && eventNs - lastAcceptedNs > MAX_GAP_NS) resetRequested = true;
        recovering = false;
        lastAcceptedNs = eventNs;
        return Decision.ACCEPT;
    }

    private boolean probeAgesLookConstant(long ageNs) {
        if (probeCount == 0) probeMinAgeNs = probeMaxAgeNs = ageNs;
        probeMinAgeNs = Math.min(probeMinAgeNs, ageNs);
        probeMaxAgeNs = Math.max(probeMaxAgeNs, ageNs);
        if (++probeCount < TIME_BASE_PROBE_LIMIT) return false;
        boolean constant = probeMaxAgeNs - probeMinAgeNs <= TIME_BASE_MAX_AGE_SPREAD_NS;
        probeCount = 0;
        return constant;
    }

    /** True once per recovery episode; the caller must then call GestureDetector.resetForRecovery(). */
    public boolean consumeResetRequest() {
        boolean requested = resetRequested;
        resetRequested = false;
        return requested;
    }

    public boolean toggleAllowed(long nowNs) {
        return !toggled || nowNs - lastToggleNs >= TOGGLE_COOLDOWN_NS;
    }

    public void recordToggle(long nowNs) {
        toggled = true;
        lastToggleNs = nowNs;
    }

    public boolean isTimeBaseUnreliable() { return timeBaseUnreliable; }
}
```

**Verify:** the file exists and `Select-String -Path app\src\main\java\com\example\choptorch\SensorDeliveryGuard.java -Pattern 'class SensorDeliveryGuard'` returns one match. Compilation is checked in Step 6.

#### Step 3: Create `SensorTimingStats.java`

Create `app/src/main/java/com/example/choptorch/SensorTimingStats.java` with exactly this content:

```java
package com.example.choptorch;

import java.util.Locale;

/** Pure-Java rolling timing summary for one sensor; holds numbers only, never raw sensor values. */
public final class SensorTimingStats {
    private final String name;
    private final long intervalNs;
    private long windowStartNs, lastEventNs, lastArrivalNs;
    private long count, dropped, sumAgeNs, minAgeNs, maxAgeNs, maxEventGapNs, maxArrivalGapNs;

    public SensorTimingStats(String name, long intervalNs) {
        this.name = name;
        this.intervalNs = intervalNs;
    }

    /** Returns a summary line when the interval has elapsed, otherwise null. */
    public String record(long eventNs, long arrivalNs, boolean wasDropped) {
        if (windowStartNs == 0) windowStartNs = arrivalNs;
        long ageNs = arrivalNs - eventNs;
        if (count == 0 || ageNs < minAgeNs) minAgeNs = ageNs;
        if (count == 0 || ageNs > maxAgeNs) maxAgeNs = ageNs;
        sumAgeNs += ageNs;
        count++;
        if (wasDropped) dropped++;
        if (lastEventNs != 0) maxEventGapNs = Math.max(maxEventGapNs, eventNs - lastEventNs);
        if (lastArrivalNs != 0) maxArrivalGapNs = Math.max(maxArrivalGapNs, arrivalNs - lastArrivalNs);
        lastEventNs = eventNs;
        lastArrivalNs = arrivalNs;
        if (arrivalNs - windowStartNs < intervalNs) return null;
        String line = String.format(Locale.US,
                "%s n=%d dropped=%d ageMs[min=%.1f avg=%.1f max=%.1f] eventGapMs[max=%.1f] arrivalGapMs[max=%.1f]",
                name, count, dropped, minAgeNs / 1e6, sumAgeNs / 1e6 / count, maxAgeNs / 1e6,
                maxEventGapNs / 1e6, maxArrivalGapNs / 1e6);
        windowStartNs = arrivalNs;
        count = dropped = sumAgeNs = minAgeNs = maxAgeNs = maxEventGapNs = maxArrivalGapNs = 0;
        return line;
    }
}
```

**Verify:** the file exists. Compilation is checked in Step 6.

#### Step 4: Add `resetForRecovery()` to `GestureDetector`

Open `app/src/main/java/com/example/choptorch/GestureDetector.java`. Find this block, which must match exactly once:

```java
        consecutiveHits = 0;
        latestScore = 0;
    }

    public void onGyroscope(float x, float y, float z) { motion.onGyroscope(x, y, z); }
```

Replace it with:

```java
        consecutiveHits = 0;
        latestScore = 0;
    }

    /** Drops sensor history after a delivery interruption but keeps the activation cooldown. */
    public void resetForRecovery() {
        motion.reset();
        history.clear();
        lastPredictionAt = 0;
        consecutiveHits = 0;
        latestScore = 0;
    }

    public void onGyroscope(float x, float y, float z) { motion.onGyroscope(x, y, z); }
```

Do not change `reset()`. It must still clear `lastTriggeredAt`.

**Verify:** `Select-String -Path app\src\main\java\com\example\choptorch\GestureDetector.java -Pattern 'resetForRecovery|lastTriggeredAt'` shows `resetForRecovery` once, and the only `lastTriggeredAt = 0;` line is inside `reset()`.

#### Step 5: Create `SensorDeliveryGuardTest.java`

Create `tests/com/example/choptorch/SensorDeliveryGuardTest.java` with exactly this content. It mirrors the service's call order, so the replay tests cover the real integration logic. It reads the private field `lastTriggeredAt` by reflection, so keep that field name.

```java
package com.example.choptorch;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure-Java tests for stale-sensor rejection, recovery, cooldown integrity, and timing statistics. */
public final class SensorDeliveryGuardTest {
    private static final long DT_MS = 20;
    private static final long BASE_MS = 100_000;
    private static final float GRAVITY = 9.80665f;
    private static final long MS = 1_000_000L;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // ---- synthetic signal (same shape as StreamingDetectorTest) ----

    private static double bump(double t, double center, double width) {
        return Math.exp(-Math.pow((t - center) / width, 2));
    }

    private static float[] gyroscope(long t, boolean doubleChop, int repetitions, Random random) {
        double p = 0;
        for (int i = 0; i < repetitions; i++) {
            long offset = i * 4000L;
            p += bump(t, 2380 + offset, 85);
            if (doubleChop) p += bump(t, 3120 + offset, 85);
        }
        float noise = (float) (random.nextGaussian() * .02);
        return new float[] { (float) (4.2 * p) + noise, (float) p, (float) (0.4 * p) };
    }

    private static List<float[]> signal(boolean doubleChop, int repetitions, int seed, long endMs) {
        ArrayList<float[]> samples = new ArrayList<>();
        Random random = new Random(seed);
        for (long t = 0; t <= endMs; t += DT_MS) samples.add(gyroscope(t, doubleChop, repetitions, random));
        return samples;
    }

    private static GestureFeatures.Result example(boolean doubleChop, int seed) {
        ArrayList<MotionSample> recording = new ArrayList<>();
        MotionProcessor motion = new MotionProcessor();
        Random random = new Random(seed);
        for (long t = 1300; t <= 3200; t += DT_MS) {
            float[] gyro = gyroscope(t, doubleChop, 1, random);
            motion.onGyroscope(gyro[0], gyro[1], gyro[2]);
            recording.add(motion.onAccelerometer(t * MS, 0, 0, GRAVITY));
        }
        return GestureFeatures.extract(recording);
    }

    private static TinyNeuralNet trainedModel() {
        List<TinyNeuralNet.Example> train = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            train.add(new TinyNeuralNet.Example(example(true, 100 + i).values, true));
            train.add(new TinyNeuralNet.Example(example(false, 300 + i).values, false));
        }
        TinyNeuralNet model = new TinyNeuralNet();
        model.train(train);
        return model;
    }

    private static TinyNeuralNet alwaysPositiveModel() {
        return TinyNeuralNet.fromParameters(new double[TinyNeuralNet.HIDDEN][TinyNeuralNet.INPUT],
                new double[TinyNeuralNet.HIDDEN], new double[TinyNeuralNet.HIDDEN], 20);
    }

    // ---- harness mirroring GestureDetectionService.onSensorChanged ordering ----

    private static final class Harness {
        final SensorDeliveryGuard accelGuard;
        final SensorDeliveryGuard gyroGuard;
        final GestureDetector detector = new GestureDetector();
        final TinyNeuralNet model;
        final List<Long> detectionMs = new ArrayList<>();
        final List<Long> toggleMs = new ArrayList<>();
        int recoveryResets;

        Harness(TinyNeuralNet model, SensorDeliveryGuard accelGuard, SensorDeliveryGuard gyroGuard) {
            this.model = model;
            this.accelGuard = accelGuard;
            this.gyroGuard = gyroGuard;
        }

        Harness(TinyNeuralNet model) {
            this(model, new SensorDeliveryGuard(), new SensorDeliveryGuard());
        }

        void deliver(long eventMs, long nowMs, float[] gyro) {
            long eventNs = eventMs * MS, nowNs = nowMs * MS;
            SensorDeliveryGuard.Decision g = gyroGuard.check(eventNs, nowNs);
            if (gyroGuard.consumeResetRequest()) { detector.resetForRecovery(); recoveryResets++; }
            if (g == SensorDeliveryGuard.Decision.ACCEPT) detector.onGyroscope(gyro[0], gyro[1], gyro[2]);

            SensorDeliveryGuard.Decision a = accelGuard.check(eventNs, nowNs);
            if (accelGuard.consumeResetRequest()) { detector.resetForRecovery(); recoveryResets++; }
            if (a != SensorDeliveryGuard.Decision.ACCEPT) return;
            GestureDetector.Detection match = detector.onAccelerometer(eventNs, 0, 0, GRAVITY, model);
            if (match == null) return;
            detectionMs.add(eventMs - BASE_MS);
            if (!accelGuard.toggleAllowed(nowNs)) return;
            accelGuard.recordToggle(nowNs);
            toggleMs.add(eventMs - BASE_MS);
        }
    }

    private static void liveFeed(Harness h, List<float[]> samples, long fromMs, long toMs) {
        for (long t = fromMs; t <= toMs; t += DT_MS) {
            h.deliver(BASE_MS + t, BASE_MS + t, samples.get((int) (t / DT_MS)));
        }
    }

    private static void burstFeed(Harness h, List<float[]> samples, long fromMs, long toMs, long deliveredAtMs) {
        for (long t = fromMs; t <= toMs; t += DT_MS) {
            h.deliver(BASE_MS + t, BASE_MS + deliveredAtMs, samples.get((int) (t / DT_MS)));
        }
    }

    private static List<Long> rawTriggers(TinyNeuralNet model, List<float[]> samples, long endMs) {
        GestureDetector detector = new GestureDetector();
        ArrayList<Long> triggers = new ArrayList<>();
        for (long t = 0; t <= endMs; t += DT_MS) {
            float[] g = samples.get((int) (t / DT_MS));
            detector.onGyroscope(g[0], g[1], g[2]);
            if (detector.onAccelerometer((BASE_MS + t) * MS, 0, 0, GRAVITY, model) != null) triggers.add(t);
        }
        return triggers;
    }

    // ---- guard unit tests ----

    private static void testGuardRules() {
        SensorDeliveryGuard guard = new SensorDeliveryGuard();
        long now = 10_000 * MS;
        check(guard.check(now - 10 * MS, now) == SensorDeliveryGuard.Decision.ACCEPT, "fresh sample rejected");
        check(!guard.consumeResetRequest(), "reset requested for a fresh sample");

        check(guard.check(now - 10 * MS, now) == SensorDeliveryGuard.Decision.DROP_OUT_OF_ORDER, "duplicate timestamp accepted");
        check(guard.check(now - 30 * MS, now) == SensorDeliveryGuard.Decision.DROP_OUT_OF_ORDER, "older timestamp accepted");
        check(!guard.consumeResetRequest(), "out-of-order sample must not request a reset");

        long later = 20_000 * MS;
        for (int i = 0; i < 50; i++) {
            check(guard.check(later - 3000 * MS + i * 20 * MS, later) == SensorDeliveryGuard.Decision.DROP_STALE,
                    "stale sample accepted");
        }
        check(guard.consumeResetRequest(), "stale batch did not request a reset");
        check(!guard.consumeResetRequest(), "stale batch requested more than one reset");

        check(guard.check(later - 100 * MS, later) == SensorDeliveryGuard.Decision.ACCEPT, "first fresh sample rejected");
        check(!guard.consumeResetRequest(), "recovery end requested a second reset");
        check(guard.check(later - 80 * MS, later) == SensorDeliveryGuard.Decision.ACCEPT, "next fresh sample rejected");

        long afterGap = later + 2000 * MS;
        check(guard.check(afterGap, afterGap) == SensorDeliveryGuard.Decision.ACCEPT, "fresh sample after a gap rejected");
        check(guard.consumeResetRequest(), "timestamp gap did not request a reset");
        check(!guard.consumeResetRequest(), "timestamp gap requested more than one reset");

        SensorDeliveryGuard future = new SensorDeliveryGuard();
        check(future.check(now + 5000 * MS, now) == SensorDeliveryGuard.Decision.DROP_STALE, "far-future timestamp accepted");
    }

    private static void testTimeBaseFailOpen() {
        SensorDeliveryGuard unreliable = new SensorDeliveryGuard();
        long now = 500_000 * MS;
        int accepted = 0;
        for (int i = 0; i < 300; i++) {
            if (unreliable.check(1_000 * MS + i * 20 * MS, now + i * 20 * MS)
                    == SensorDeliveryGuard.Decision.ACCEPT) accepted++;
        }
        check(unreliable.isTimeBaseUnreliable(), "constant huge age did not mark the time base unreliable");
        check(accepted == 201, "expected fail-open after 100 implausible samples, accepted=" + accepted);

        SensorDeliveryGuard trusted = new SensorDeliveryGuard();
        check(trusted.check(now, now) == SensorDeliveryGuard.Decision.ACCEPT, "trusted baseline rejected");
        for (int i = 1; i <= 1000; i++) {
            check(trusted.check(now + i * 20 * MS, now + 30_000 * MS) == SensorDeliveryGuard.Decision.DROP_STALE,
                    "stale sample accepted after the time base was trusted");
        }
        check(!trusted.isTimeBaseUnreliable(), "a trusted time base must never fail open");

        SensorDeliveryGuard lagging = new SensorDeliveryGuard();
        long start = 100_000 * MS;
        check(lagging.check(start, start) == SensorDeliveryGuard.Decision.ACCEPT, "lag baseline rejected");
        int laggedAccepted = 0;
        for (int i = 1; i <= 2000; i++) {
            long event = start + i * 20 * MS;
            if (lagging.check(event, event + 3_000 * MS) == SensorDeliveryGuard.Decision.ACCEPT) laggedAccepted++;
        }
        check(lagging.isTimeBaseUnreliable(), "a steady 3 s lag never gave up");
        check(laggedAccepted == 500, "expected to accept after 30 s of continuous lag, accepted=" + laggedAccepted);
    }

    private static void testToggleCooldownBoundaries() {
        SensorDeliveryGuard guard = new SensorDeliveryGuard();
        check(guard.toggleAllowed(1), "first toggle blocked");
        guard.recordToggle(5_000 * MS);
        check(!guard.toggleAllowed(5_000 * MS), "toggle allowed at the same instant");
        check(!guard.toggleAllowed(5_000 * MS + 1_599_999_999L), "toggle allowed 1 ns before the cooldown ends");
        check(guard.toggleAllowed(5_000 * MS + 1_600_000_000L), "toggle blocked at exactly 1600 ms");
    }

    private static void testTimingStats() {
        SensorTimingStats stats = new SensorTimingStats("accel", 1_000_000_000L);
        String line = null;
        for (int i = 0; i <= 50; i++) {
            long event = 1_000 * MS + i * 20 * MS;
            String result = stats.record(event, event + 10 * MS, false);
            if (result != null) line = result;
        }
        check(line != null, "summary line not produced after the interval");
        check(line.contains("accel n=51 dropped=0"), "unexpected counters: " + line);
        check(line.contains("min=10.0 avg=10.0 max=10.0"), "unexpected ages: " + line);

        SensorTimingStats gap = new SensorTimingStats("gyro", 1_000_000_000L);
        gap.record(1_000 * MS, 1_005 * MS, false);
        gap.record(1_500 * MS, 1_505 * MS, true);
        String summary = gap.record(2_100 * MS, 2_105 * MS, false);
        check(summary != null && summary.contains("dropped=1"), "dropped count missing: " + summary);
        check(summary.contains("eventGapMs[max=600.0]") && summary.contains("arrivalGapMs[max=600.0]"),
                "gap measurements missing: " + summary);
    }

    // ---- replay and recovery scenarios ----

    private static void testNormalBehaviorUnchanged(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 2, 505, 10_500);
        List<Long> expected = rawTriggers(model, samples, 10_500);
        check(expected.size() == 2, "baseline detector expected two detections, got " + expected);
        Harness h = new Harness(model);
        liveFeed(h, samples, 0, 10_500);
        check(h.toggleMs.equals(expected), "guarded fresh stream changed detections: " + h.toggleMs + " vs " + expected);
        check(h.recoveryResets == 0, "fresh stream triggered a recovery reset");
    }

    private static void testBufferedReplayIsDiscarded(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 2, 505, 10_500);
        check(rawTriggers(model, samples, 10_500).size() == 2, "replay test needs a stream that detects twice");

        Harness allStale = new Harness(model);
        burstFeed(allStale, samples, 0, 10_500, 10_500 + 5_000);
        check(allStale.detectionMs.isEmpty() && allStale.toggleMs.isEmpty(),
                "stale batch produced detections " + allStale.detectionMs);
        check(allStale.recoveryResets == 2, "expected one reset per sensor guard, got " + allStale.recoveryResets);

        Harness freshTail = new Harness(model);
        burstFeed(freshTail, samples, 0, 10_500, 10_500 + 100);
        check(freshTail.toggleMs.isEmpty(), "batch with a fresh tail produced toggles " + freshTail.toggleMs);
    }

    private static void testFreshGestureAfterRecovery(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 5, 505, 21_000);
        Harness h = new Harness(model);
        burstFeed(h, samples, 0, 8_000, 12_000);
        check(h.toggleMs.isEmpty(), "replayed gestures toggled: " + h.toggleMs);
        liveFeed(h, samples, 12_000 + DT_MS, 21_000);
        check(h.recoveryResets == 2, "recovery reset more than once per guard: " + h.recoveryResets);
        check(h.toggleMs.size() == 2, "expected two fresh toggles, got " + h.toggleMs);
        check(h.toggleMs.get(0) > 15_120 && h.toggleMs.get(0) < 17_000, "first fresh toggle out of range: " + h.toggleMs);
        check(h.toggleMs.get(1) > 19_120, "second fresh toggle out of range: " + h.toggleMs);
    }

    private static void testCooldownIntegrity(TinyNeuralNet model) throws Exception {
        TinyNeuralNet positive = alwaysPositiveModel();
        GestureDetector detector = new GestureDetector();
        Field field = GestureDetector.class.getDeclaredField("lastTriggeredAt");
        field.setAccessible(true);
        List<float[]> samples = signal(true, 2, 707, 6_500);
        GestureDetector.Detection match = null;
        for (long t = 0; t <= 6_500 && match == null; t += DT_MS) {
            float[] g = samples.get((int) (t / DT_MS));
            detector.onGyroscope(g[0], g[1], g[2]);
            match = detector.onAccelerometer((BASE_MS + t) * MS, 0, 0, GRAVITY, positive);
        }
        check(match != null, "always-positive model never detected");
        long stamped = field.getLong(detector);
        check(stamped > 0, "detection did not record its time");
        detector.resetForRecovery();
        check(field.getLong(detector) == stamped, "resetForRecovery cleared the cooldown timestamp");
        detector.reset();
        check(field.getLong(detector) == 0, "reset() no longer clears the cooldown timestamp");

        // Second line of defense: even with stale rejection disabled, replayed gestures
        // delivered within 1600 ms of real time cannot toggle twice.
        List<float[]> burst = signal(true, 2, 505, 10_500);
        long hugeNs = Long.MAX_VALUE / 4;
        Harness noStaleGate = new Harness(model, new SensorDeliveryGuard(hugeNs), new SensorDeliveryGuard(hugeNs));
        burstFeed(noStaleGate, burst, 0, 10_500, 10_500 + 5_000);
        check(noStaleGate.detectionMs.size() == 2, "expected the ungated replay to detect twice, got " + noStaleGate.detectionMs);
        check(noStaleGate.toggleMs.size() == 1, "wall-clock cooldown failed to block the second toggle: " + noStaleGate.toggleMs);
    }

    public static void main(String[] args) throws Exception {
        testGuardRules();
        testTimeBaseFailOpen();
        testToggleCooldownBoundaries();
        testTimingStats();
        TinyNeuralNet model = trainedModel();
        testNormalBehaviorUnchanged(model);
        testBufferedReplayIsDiscarded(model);
        testFreshGestureAfterRecovery(model);
        testCooldownIntegrity(model);
        System.out.println("PASS: stale-sensor guard, replay rejection, recovery, cooldown, and timing statistics");
    }
}
```

What the tests prove, mapped to the acceptance tests below:

| Method | Covers |
| --- | --- |
| `testNormalBehaviorUnchanged` | Test A and part of Test G: a fresh stream gives the same detections as the unguarded detector, with no recovery reset. |
| `testBufferedReplayIsDiscarded` | Test D: two buffered double chops, whether the whole batch is stale or has a fresh tail, produce zero toggles. |
| `testFreshGestureAfterRecovery` | Test E: after a discarded batch, fresh gestures toggle normally, with exactly one reset per sensor. |
| `testCooldownIntegrity` | Test F: `resetForRecovery()` keeps the cooldown timestamp, `reset()` still clears it, and the wall-clock cooldown blocks a second toggle even with stale rejection disabled. |
| `testGuardRules`, `testTimeBaseFailOpen`, `testToggleCooldownBoundaries`, `testTimingStats` | Each rule in the design table, including both fail-open paths. |

#### Step 6: Add the new sources and test to the core runner

Open `tests/run-core-tests.sh`. Preserve the tracked file's existing line endings. This Windows checkout uses CRLF, which `sh` cannot execute; verify a temporary LF-normalized copy under Bash and then remove it. Do not pipe the script to Bash because it resolves the repository root from `$0`.

Find this block, which must match exactly once:

```sh
  "$SRC/GestureDetector.java" \
  "$SRC/MonitoringStartFlow.java" \
```

Replace it with:

```sh
  "$SRC/GestureDetector.java" \
  "$SRC/SensorDeliveryGuard.java" \
  "$SRC/SensorTimingStats.java" \
  "$SRC/MonitoringStartFlow.java" \
```

Find this line, which must match exactly once:

```sh
  "$ROOT/tests/com/example/choptorch/StreamingDetectorTest.java"
```

Replace it with:

```sh
  "$ROOT/tests/com/example/choptorch/StreamingDetectorTest.java" \
  "$ROOT/tests/com/example/choptorch/SensorDeliveryGuardTest.java"
```

Add this as the last line of the file, after the `StreamingDetectorTest` run:

```sh
java -cp "$OUT" com.example.choptorch.SensorDeliveryGuardTest
```

**Verify:** run the temporary LF-normalized copy with Bash. Expect exit code 0 and these five `PASS` lines, the last one new:

```text
PASS: 27 gesture features, ...
PASS: restored dataset and duplicate-seed repair
PASS: monitoring-start flow transitions
PASS: streaming double-chops toggled once each at [3360, 7360], ...
PASS: stale-sensor guard, replay rejection, recovery, cooldown, and timing statistics
```

If `StreamingDetectorTest` output changes, stop: an unchanged detector must give `[3360, 7360]`. Also confirm the CI workflow needs no edit: it already calls this script.

#### Step 7: Integrate the guard into `GestureDetectionService`

Open `app/src/main/java/com/example/choptorch/GestureDetectionService.java`. Make these six edits in order. Each Find must match exactly once.

**7a. Imports.** Find:

```java
import android.os.IBinder;
import android.os.PowerManager;
```

Replace with:

```java
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
```

**7b. Constants.** Find:

```java
    private static final String CHANNEL = "gesture_detector";
```

Replace with:

```java
    private static final String CHANNEL = "gesture_detector";
    private static final String TAG = "ChopTorch/Sensors";
    private static final boolean DIAGNOSTICS = BuildConfig.ENABLE_TRAINING;
    private static final long STATS_INTERVAL_NS = 5_000_000_000L;
```

**7c. Fields.** Find:

```java
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return running; }
```

Replace with:

```java
    private PowerManager.WakeLock wakeLock;
    private SensorDeliveryGuard accelGuard = new SensorDeliveryGuard();
    private SensorDeliveryGuard gyroGuard = new SensorDeliveryGuard();
    private SensorTimingStats accelStats = new SensorTimingStats("accel", STATS_INTERVAL_NS);
    private SensorTimingStats gyroStats = new SensorTimingStats("gyro", STATS_INTERVAL_NS);

    public static boolean isRunning() { return running; }
```

**7d. Torch callback logging.** Find:

```java
        torch = new TorchController(this, on -> { });
```

Replace with:

```java
        torch = new TorchController(this, on -> {
            if (DIAGNOSTICS) Log.i(TAG, "torch state=" + on + " atNs=" + SystemClock.elapsedRealtimeNanos());
        });
```

**7e. Fresh guards at startup.** Find:

```java
            detector.reset();
            boolean acc = manager.registerListener(this, accelerometer, 20000);
```

Replace with:

```java
            detector.reset();
            accelGuard = new SensorDeliveryGuard();
            gyroGuard = new SensorDeliveryGuard();
            accelStats = new SensorTimingStats("accel", STATS_INTERVAL_NS);
            gyroStats = new SensorTimingStats("gyro", STATS_INTERVAL_NS);
            if (DIAGNOSTICS) logSensorInventory();
            boolean acc = manager.registerListener(this, accelerometer, 20000);
```

Do not change the two `registerListener(..., 20000)` calls.

**7f. Replace `onSensorChanged` and add helpers.** Find this whole method:

```java
    @Override public void onSensorChanged(SensorEvent event) {
        if (!running) return;
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            detector.onGyroscope(event.values[0], event.values[1], event.values[2]);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            GestureDetector.Detection match = detector.onAccelerometer(event.timestamp,
                    event.values[0], event.values[1], event.values[2], store.getModel());
            latestScore = detector.latestScore();
            if (match != null) {
                try {
                    torch.toggle();
                    if (torch.isOn()) store.rememberTorchOn(match.features, match.score);
                    updateNotification();
                } catch (Exception failure) {
                    lastError = "Torch unavailable: " + failure.getMessage();
                }
            }
        }
    }
```

Replace it with:

```java
    @Override public void onSensorChanged(SensorEvent event) {
        if (!running) return;
        boolean gyro = event.sensor.getType() == Sensor.TYPE_GYROSCOPE;
        if (!gyro && event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        long arrivalNs = SystemClock.elapsedRealtimeNanos();
        SensorDeliveryGuard guard = gyro ? gyroGuard : accelGuard;
        SensorDeliveryGuard.Decision decision = guard.check(event.timestamp, arrivalNs);
        if (guard.consumeResetRequest()) {
            detector.resetForRecovery();
            Log.w(TAG, String.format(Locale.US, "%s recovery reset: %s ageMs=%.1f %s",
                    gyro ? "gyro" : "accel", decision, (arrivalNs - event.timestamp) / 1e6, powerState()));
        }
        String summary = DIAGNOSTICS ? (gyro ? gyroStats : accelStats).record(event.timestamp, arrivalNs,
                decision != SensorDeliveryGuard.Decision.ACCEPT) : null;
        if (summary != null) Log.i(TAG, summary + " " + powerState());
        if (decision != SensorDeliveryGuard.Decision.ACCEPT) return;
        if (gyro) {
            detector.onGyroscope(event.values[0], event.values[1], event.values[2]);
            return;
        }
        GestureDetector.Detection match = detector.onAccelerometer(event.timestamp,
                event.values[0], event.values[1], event.values[2], store.getModel());
        latestScore = detector.latestScore();
        if (match == null) return;
        long detectedNs = SystemClock.elapsedRealtimeNanos();
        if (!accelGuard.toggleAllowed(detectedNs)) {
            Log.w(TAG, String.format(Locale.US, "detection ignored by wall-clock cooldown score=%.3f", match.score));
            return;
        }
        accelGuard.recordToggle(detectedNs);
        if (DIAGNOSTICS) {
            Log.i(TAG, String.format(Locale.US, "detect score=%.3f eventAgeMs=%.1f toggleRequestNs=%d %s",
                    match.score, (detectedNs - event.timestamp) / 1e6, detectedNs, powerState()));
        }
        try {
            torch.toggle();
            if (DIAGNOSTICS) {
                Log.i(TAG, "toggle returned atNs=" + SystemClock.elapsedRealtimeNanos() + " torchOn=" + torch.isOn());
            }
            if (torch.isOn()) store.rememberTorchOn(match.features, match.score);
            updateNotification();
        } catch (Exception failure) {
            lastError = "Torch unavailable: " + failure.getMessage();
        }
    }

    private String powerState() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return String.format(Locale.US, "interactive=%b deviceIdle=%b powerSave=%b",
                power.isInteractive(), power.isDeviceIdleMode(), power.isPowerSaveMode());
    }

    private void logSensorInventory() {
        describeSensor("accelerometer(default)", manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
        describeSensor("accelerometer(wakeUp)", manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true));
        describeSensor("gyroscope(default)", manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE));
        describeSensor("gyroscope(wakeUp)", manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true));
        describeSensor("significantMotion", manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION));
        describeSensor("motionDetect", manager.getDefaultSensor(Sensor.TYPE_MOTION_DETECT));
        for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) {
            String type = sensor.getStringType();
            if (type != null && type.contains("pick_up")) describeSensor("pickUp", sensor);
        }
    }

    private static void describeSensor(String label, Sensor sensor) {
        if (sensor == null) {
            Log.i(TAG, label + ": unavailable");
            return;
        }
        Log.i(TAG, String.format(Locale.US,
                "%s: name=%s vendor=%s wakeUp=%b reportingMode=%d minDelayUs=%d maxDelayUs=%d fifoMax=%d fifoReserved=%d powerMa=%.3f",
                label, sensor.getName(), sensor.getVendor(), sensor.isWakeUpSensor(), sensor.getReportingMode(),
                sensor.getMinDelay(), sensor.getMaxDelay(), sensor.getFifoMaxEventCount(),
                sensor.getFifoReservedEventCount(), sensor.getPower()));
    }
```

Notes for the implementer:

- `Sensor.TYPE_PICK_UP_GESTURE` is **not** a public constant in compileSdk 36, so the inventory finds pick-up sensors by matching their string type. Do not reference the constant.
- Pick-up, significant-motion, and motion-detect sensors are one-shot trigger sensors. This change only **lists** them. Do not register or request them.
- The guard check runs **before** `detector.onGyroscope()` and `detector.onAccelerometer()`, so stale values never reach the Kalman filter, history, or model.
- The wall-clock cooldown uses `accelGuard`, because only accelerometer events produce detections.

**Verify:**

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
.\gradlew.bat assembleDebug -Pshipping=true
.\gradlew.bat lintDebug
```

Expect `BUILD SUCCESSFUL` for all three and no lint errors. The earlier baseline was five lint warnings; record the new count and explain any warning that is new.

#### Step 8: Re-run the automated checks on the device

```powershell
bash tests/run-core-tests.sh
.\scripts\device-test.ps1 -Serial <serial> -Variant dev
.\scripts\device-test.ps1 -Serial <serial> -Variant shipping
```

Expect exit code 0 each time. These runs only confirm that the app still installs and launches. The existing instrumentation tests do **not** start the monitoring service, so they say nothing about this change. Step 9 covers that.

Then confirm no forbidden file changed:

```powershell
git diff --stat -- app/src/main/assets app/src/main/java/com/example/choptorch/TinyNeuralNet.java app/src/main/java/com/example/choptorch/GestureFeatures.java app/src/main/java/com/example/choptorch/AngleKalman.java app/src/main/java/com/example/choptorch/MotionProcessor.java app/src/main/java/com/example/choptorch/GestureStore.java app/src/main/trainingAssets app/build.gradle
```

Expect no output. Also confirm `THRESHOLD`, `WINDOW_MS`, `160`, and `1600` are unchanged with `git diff -- app/src/main/java/com/example/choptorch/GestureDetector.java`; the only added lines are the `resetForRecovery()` method.

#### Step 9: Smoke-test the service and collect diagnostics

Use the **dev** build, so verbose logs are on.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb -s <serial> logcat -c
```

Open the app on the phone and start monitoring (grant camera permission and follow the battery prompts). Then:

```powershell
& $adb -s <serial> logcat -d -v threadtime "ChopTorch/Sensors:I" "*:S" | Set-Content -Encoding utf8 sensor-log.txt
Get-Content sensor-log.txt -TotalCount 40
```

Expect, within about 6 seconds of starting:

- Inventory lines for `accelerometer(default)`, `accelerometer(wakeUp)`, `gyroscope(default)`, `gyroscope(wakeUp)`, `significantMotion`, `motionDetect`, and any `pickUp`. Each shows `unavailable` or its `name`, `vendor`, `wakeUp`, `reportingMode`, delay, FIFO, and power values.
- Periodic lines starting with `accel n=` and `gyro n=`, about every 5 seconds, ending in `interactive=… deviceIdle=… powerSave=…`.
- With the phone sitting still and fresh: `dropped=0` and small `ageMs` values.

Save `sensor-log.txt` outside the repository, or under the ignored `test-results/` folder. Do not commit it.

If the app crashes on start, or no `ChopTorch/Sensors` lines appear, stop and report the `adb logcat` crash text.

Optional extra evidence:

```powershell
& $adb -s <serial> shell dumpsys sensorservice | Set-Content -Encoding utf8 sensorservice.txt
& $adb -s <serial> shell dumpsys power | Set-Content -Encoding utf8 power.txt
```

Look in `sensorservice.txt` for ChopTorch's active connections and their requested rate and batching. Look in `power.txt` for the `ChopTorch:IMU` wake lock.

### Device test protocol

Run each test on the dev build with monitoring started. Put results in the Results table at the end.

**Setup for long screen-off tests.** A phone on USB power is charging, which hides battery behavior and can keep it out of deep idle. For Tests C and H, use wireless debugging: enable **Wireless debugging** in Developer options, pair with `adb pair`, connect with `adb connect`, then unplug the cable. The device script accepts the `ip:port` serial.

Screen controls over adb: `adb shell input keyevent KEYCODE_SLEEP` turns the screen off and `KEYCODE_WAKEUP` turns it on.

| Test | What to do | Expected result |
| --- | --- | --- |
| **A. Normal double chop** | Screen on, monitoring running. Do ten double chops about 4 s apart. | Behavior matches the pre-change build. The pure-Java `testNormalBehaviorUnchanged` shows identical detections for a fresh stream; the device run shows no `recovery reset` lines. |
| **B. Short screen-off** | Lock the phone for 30 to 60 s. Do one fresh double chop without touching the screen. | One toggle. Record whether `recovery reset` appeared. |
| **C. Prolonged screen-off** | Lock for 10, 20, and 30 minutes, unplugged, with no touch in between. After each wait, do one double chop. Record whether the first chop worked and how long until the torch changed. Pull logs afterward. | Record, do not assume. From the logs, report whether summary lines continued through the lock, the maximum `ageMs` and `eventGapMs`, and any `recovery reset`. |
| **D. Buffered replay** | Pure-Java `testBufferedReplayIsDiscarded` is the required check: zero toggles from a stale batch. Optional on-device simulation (unverified technique): find the app PID with `adb shell pidof com.example.choptorch.devclassic`, run `adb shell run-as com.example.choptorch.devclassic kill -STOP <pid>`, do two double chops, wait 6 s, then `... kill -CONT <pid>`. | Zero toggles caused by those chops, and a `recovery reset: DROP_STALE` line. If the kernel drops queued events, the test shows nothing; report that rather than claiming a pass. |
| **E. Fresh gesture after recovery** | After a recovery line appears (Test C or D), wait 3 s, then do a double chop. | Normal toggle. Pure-Java `testFreshGestureAfterRecovery` covers the logic. |
| **F. Cooldown integrity** | Pure-Java `testCooldownIntegrity`. On device: do a double chop, then immediately repeat. | The second toggle is blocked inside 1,600 ms, including after any recovery. Look for `detection ignored by wall-clock cooldown`. |
| **G. Model compatibility** | `bash tests/run-core-tests.sh`, then the Step 8 `git diff --stat` command. On the dev app, open the training page, confirm saved examples and the model are listed, and run the existing export. | Tests pass, no forbidden file changed, examples and the model load, and export works. |
| **H. Battery and callback availability** | Wireless adb, unplugged, same room and settings. For each build, record `adb shell dumpsys battery` level, run monitoring with the screen off for at least 60 minutes, record the level again, and capture `adb shell dumpsys batterystats` for the app. Repeat at least three times per build. Baseline build: the `app.apk` saved in Step 0 (`adb install -r`). New build: the current dev APK. For callback availability, sum `n=` over the summary lines and divide by the expected count (about 50 samples per second per sensor at the requested rate). | Compare the two builds. Battery percentage is coarse, so report the spread, not a single number. Callback availability can only be measured on the new build because only it logs counts. Do not claim a power improvement without repeated runs. |

#### Reading the diagnostic lines

| Log pattern | Meaning |
| --- | --- |
| `ageMs[max=…]` large and `arrivalGapMs[max=…]` small | Samples arrived in a burst: buffered delivery, which supports the hypothesis. |
| `eventGapMs[max=…]` large while `ageMs[max=…]` stays small | Delivery paused and resumed with fresh data: the sensors stopped, not buffering. |
| No `accel n=` lines for minutes while monitoring is on (check logcat timestamps) | Callbacks stopped or the process was frozen or killed. Check `dumpsys activity services` and `dumpsys power`. |
| `recovery reset: DROP_STALE ageMs=…` | A stale event started recovery. The `ageMs` shows how old it was. |
| `recovery reset: ACCEPT ageMs=…` | A timestamp gap above 500 ms with fresh data triggered the reset. |
| `interactive=false deviceIdle=true` | The phone was in Doze when the line was written. |
| `detect … eventAgeMs=…` large | A detection came from old data, which should not happen unless the guard failed open. Investigate. |
| `toggleRequestNs` and `toggle returned atNs` | Their difference is the `setTorchMode` call time. |
| Second `torch state=` line after the request | The camera service's own callback. The first `torch state=` is `TorchController` reporting the requested state immediately. The gap to the second line is camera latency. |

These lines separate sensor acquisition delay, app processing delay, and camera delay.

### P2 gate: wake-up sensors

Do not start this until Step 9 and Test C have produced evidence.

| Evidence | Conclusion | Action |
| --- | --- | --- |
| Logs show stale bursts or gaps after long lock, and the inventory shows both `accelerometer(wakeUp)` and `gyroscope(wakeUp)` available | Wake-up variants may help. | Run the experiment below, behind owner approval. |
| Logs show stale bursts, but no wake-up variants exist | Android or the hardware prevents immediate recognition on this device. | Keep the guard. Document the limit. Do not add workarounds. |
| Logs show no stale or gap events but the first chop is still missed | Buffering is not the cause. | Investigate other causes: Doze, process death, OEM background limits, camera contention, or the gesture itself. Do not change sensors. |
| The default sensors already report `wakeUp=true` | Already wake-up; the hypothesis is unlikely. | Look for another cause. |

**Experiment (only if the first row applies).** Add a local constant `USE_WAKE_UP_SENSORS = false` to the service. When it is `true` and both wake-up variants exist, register `getDefaultSensor(type, true)` instead of the default sensors, keeping the `20000` µs period and zero batching. Flip it locally, rebuild, and rerun Tests B, C, and H. Wake-up sensors wake the processor on every event, so expect a power cost similar to or higher than the wake lock. Do not merge the constant as `true` without a recorded battery comparison and owner approval. Any user-facing option must be opt-in and must state the battery cost.

**Out of scope for this change.** One-shot trigger sensors (significant motion, motion detect, pick-up) need `requestTriggerSensor`, correct re-arming after each trigger, and evidence that they fire early enough to catch the first chop. They may trigger too late. This plan only lists them. Using them needs its own design and evidence.

### Android limits and power trade-offs to document

Add a short, accurate note to this document with the measured results. Cover these points and mark each as measured or not:

- Non-wake-up sensors do not hold the processor awake. Android documents that events can be delivered later with their original timestamps when the processor wakes. Whether that happens on a given device here is unconfirmed until the logs show it.
- The partial wake lock is held for the whole monitoring session and drains battery. An OEM may still restrict a foreground service, so neither the wake lock nor the unrestricted-battery setting guarantees uninterrupted sensor delivery.
- Stale rejection prevents false replays but cannot make the first chop respond if sensor data is withheld until wake.
- Wake-up sensors may improve responsiveness on some hardware at a battery cost.
- A steady delay is not distinguishable from a clock-base mismatch, so the guard fails open and behaves like the old code there.
- Diagnostics log only timing numbers, scores, power state, and sensor hardware names, never raw sensor values. Verbose logs run only in development builds; shipping builds keep only the rare warning lines.
- An optional high-power monitoring mode is not part of this change. If proposed later it must be opt-in, state the battery cost, and avoid unbounded or leaked wake locks.

#### Current evidence status

| Point | Status | Evidence |
| --- | --- | --- |
| Non-wake-up sensors can defer events with original timestamps; whether this device does so is unknown. | Not measured on the device. | No post-change sensor log or sensor inventory was collected. |
| The partial wake lock is held for the monitoring session; actual drain and OEM restrictions are unknown. | Source-confirmed; power behavior not measured. | `GestureDetectionService` acquires/releases `ChopTorch:IMU`; no post-change battery run was possible. |
| Stale rejection blocks tested replays but cannot recover a chop withheld until wake. | Replay logic measured; hardware responsiveness not measured. | `testBufferedReplayIsDiscarded` passes; Tests B and C were not run on-device. |
| Wake-up sensors might improve responsiveness at a battery cost. | Not measured. | No sensor inventory or wake-up sensor experiment was performed. |
| A steady delay eventually fails open because it cannot be distinguished from a clock-base mismatch. | Guard behavior measured; device clock behavior not measured. | `testTimeBaseFailOpen` passes; no device timing log exists. |
| Diagnostics avoid raw sensor values and verbose output is development-only. | Source-reviewed and build-verified; runtime logging not measured. | Debug and shipping builds succeed; no post-change service log was collected. |
| Optional high-power monitoring mode. | Not implemented or measured; out of scope. | No high-power mode was added. |

### Files changed by this plan

| File | Change | Reason |
| --- | --- | --- |
| `app/src/main/java/com/example/choptorch/SensorDeliveryGuard.java` | New | Stale, out-of-order, and gap handling plus wall-clock toggle cooldown. |
| `app/src/main/java/com/example/choptorch/SensorTimingStats.java` | New | Throttled timing summaries without raw sensor values. |
| `app/src/main/java/com/example/choptorch/GestureDetector.java` | Add `resetForRecovery()` | Clears history without clearing the cooldown. |
| `app/src/main/java/com/example/choptorch/GestureDetectionService.java` | Guard integration and diagnostics | Rejects stale input before the detector and logs timing. |
| `tests/com/example/choptorch/SensorDeliveryGuardTest.java` | New | Automated replay, recovery, and cooldown tests. |
| `tests/run-core-tests.sh` | Add sources, test, and run line | Run the new test in the core suite and CI. |
| `docs/features/background-monitoring.md` | Record results | Evidence and limits. |

### Results

| Item | Result | Evidence |
| --- | --- | --- |
| Baseline core tests (Step 0) | PASS: four existing test entry points. | Direct PowerShell `javac`/`java` run of the runner's source list; the CRLF shell wrapper could not run directly. |
| Core tests after the change (Step 6) | PASS: all five lines; streaming detections remain `[3360, 7360]`. | Temporary LF-normalized copy of `tests/run-core-tests.sh`. |
| Debug, shipping, and lint builds (Step 7) | PASS: debug + androidTest, shipping-configured debug, and lint. Four warnings; no errors. | Gradle output and `app/build/reports/lint-results-debug.xml`; warning IDs listed above. |
| Device script, dev and shipping (Step 8) | Post-change: NOT RUN beyond preflight; both returned device-preflight exit code 2 because the serial was disconnected. Pre-change: both variants passed 2/2 instrumentation tests. | Current summaries under ignored `test-results/device/20261108-030050-ZF524979X8-dev/` and `20261108-030054-ZF524979X8-shipping/`; prior dev APK and results at `../../test-results/device/20261105-182035-ZF524979X8-dev/`, prior shipping results at `../../test-results/device/20261105-182920-ZF524979X8-shipping/`. |
| Sensor inventory for the test device | NOT MEASURED. | No post-change device connection or service log. |
| Test A | Device: NOT RUN. Fresh-stream automated equivalent PASS. | `testNormalBehaviorUnchanged`. |
| Test B | NOT RUN on-device. | Requires screen-lock gesture test. |
| Test C: 10, 20, 30 min | NOT RUN. | Requires unplugged, prolonged screen-off device sessions. |
| Test D | Automated replay test PASS; on-device simulation NOT RUN. | `testBufferedReplayIsDiscarded`; no device attached. |
| Test E | Automated recovery/fresh-gesture test PASS; on-device test NOT RUN. | `testFreshGestureAfterRecovery`. |
| Test F | Automated cooldown integrity test PASS; on-device test NOT RUN. | `testCooldownIntegrity`. |
| Test G | Core/model source checks PASS; training UI and export NOT RUN on-device. | Five core `PASS` lines and empty protected-file diff. |
| Test H | NOT RUN. | Requires repeated 60-minute unplugged battery and callback measurements per build. |

### Done when

- Steps 2 to 7 are applied exactly, the five `PASS` lines appear, and `git diff` shows no change to any constrained file or value.
- Debug, shipping, and lint builds succeed. Post-change device-script success remains pending reconnection of the test device.
- Step 9 logs and sensor inventory remain pending; no claim of device verification is made.
- Tests A to H are recorded above, with automated results separated from device results and every unrun test marked.
- No document claims the screen-locked responsiveness improved unless a device run shows it.

**Acceptance (from the product owner):** the app never replays old sensor gestures as new flashlight commands, a delayed batch never turns the flashlight on and then off, fresh recognition keeps the previous model and settings, and the limits Android imposes are documented where it prevents immediate recognition.
