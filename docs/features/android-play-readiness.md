# Feature: Android and Google Play readiness

## Goal and status

Establish a repeatable, evidence-based process for building, testing, publishing, and operating ChopTorch at scale. This is a **proposed readiness plan**, not a claim that the current app is production-ready, policy-approved, or tested on the device matrix below. Check current Play Console requirements and policies at each release: they vary by account, app, region, and date.

## Current baseline

- A Java 17 Android app with minimum SDK 26, target SDK 35, compile SDK 36, and a user-started `specialUse` foreground service.
- `-Pshipping=true` disables training UI and requires the model asset to exist. The repository documents a debug APK workflow, not a completed signed Play release pipeline.
- `bash tests/run-core-tests.sh` runs the synthetic core smoke, seed-deduplication, monitoring-start-flow, and streaming-detector checks. These are pure-Java checks; Android integration, device, performance, and distribution tests are not established by that script.
- Monitoring uses a partial wake lock and continuous sensor sampling. Background behavior and battery impact require measurement on real devices.

## Architecture and implementation practices

Apply [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations) proportionately to this small Java/Views app:

- Separate UI rendering and actions from recognition, service control, storage, and Camera2 operations. Move toward a single observable UI state and lifecycle-aware updates; keep the service authoritative about its actual running state.
- Keep `GestureStore` behind a data boundary and inject or otherwise substitute sensor, clock, torch, persistence, and power-state dependencies in tests. Prefer simple fakes where useful.
- Keep sensor processing and inference independently testable; prevent long operations and training from blocking the UI. Handle permission changes, process death, service-start restrictions, and asynchronous callbacks explicitly.
- Retain the existing Java/Views approach unless a migration is justified. Google's Compose, Kotlin Flow, ViewModel, and DI suggestions are recommendations to adapt, not a requirement to rewrite the app.
- Validate model metadata, dimensions, and numerical values before use; fail safely on incompatible or corrupt assets. Avoid recording raw sensor data or personal data in release logs.
- Review accessibility, TalkBack labels, touch targets, font scaling, contrast, localization, and small/large displays. Do not assume the portrait layout works for all screen sizes.

## Test and robustness plan

| Layer | Required coverage before wide rollout |
| --- | --- |
| Core unit tests | Feature vector and model compatibility, malformed inputs, deterministic detection windows, threshold boundaries, peak pairing, consecutive hits, cooldown, timestamp irregularities, and deduplication. Repair or replace the unrun streaming test. |
| Android integration and UI | Permission grant/denial/revocation, missing flash or sensors, unavailable/corrupt model, start/stop, service failure, notification actions, activity recreation, camera contention, and wake-lock/sensor cleanup. Exercise the proposed battery flow only after it is implemented. |
| Physical-device matrix | At minimum, sample API 26, API 33, API 34, target/latest supported API, multiple OEMs and hardware tiers, and missing-hardware cases where practical. Include fresh install, update, lock screen, app backgrounding, system process kill, user stop, Doze, battery saver, Low Power Standby where available, and OEM power settings. Record actual OS/build and results. |
| Recognition | Collect consented, representative sessions across users, phone positions, device models, and ordinary movements. Separate participants/sessions for training and held-out evaluation. Measure true-positive rate, false activations per monitoring hour, missed gestures, and detection latency with confidence intervals; define release thresholds before testing. |
| Performance and soak | Measure startup, UI responsiveness, ANRs, sensor processing, memory, thermal behavior, and battery drain against an idle baseline during hours of screen-off monitoring. Repeat with power restrictions and without; set device-specific budgets before release. |
| Accessibility and usability | Test TalkBack, keyboard/focus order, large font sizes, translations, denied permissions, clear error recovery, and user comprehension of battery costs and stop controls. |

Use Android Lint and automated unit/UI tests in CI; run relevant emulator and physical-device tests before release. Establish baselines and thresholds with product owners, record device/OS/build, sample size, logs without sensitive motion data, failures, and known limitations. A foreground service and battery exemption are not guarantees of uninterrupted sensor delivery.

## Play policy and store listing

- Verify developer account and app identity, contact details, current target API deadline, required app bundle format, signing, versioning, and account-specific testing eligibility directly in Play Console before submission.
- Review foreground-service policy and declare the appropriate service type and use case in Play Console. The existing manifest declares `specialUse` and a subtype; Google reviews the justification, so acceptance must not be assumed. Provide requested demonstration material and explain why user-started continuous sensing is necessary.
- Review camera, notification, wake-lock, battery guidance, and any future permissions for least privilege and current policy. Direct battery-exemption requests have special restrictions; see [production UI and battery settings](production-ui-and-battery.md).
- Complete Data safety, privacy policy, content rating, target audience, ads declaration, app access, and other applicable forms accurately for the actual release and all included SDKs. Audit data collection and retention before making claims; update disclosures whenever behavior changes.
- Follow [Google's store listing guidance](https://support.google.com/googleplay/android-developer/answer/13393723?hl=en): accurate name and description, real in-app screenshots, suitable artwork, no misleading ranking or promotional claims, and appropriate rights for assets. Current guidance limits the title to 30 characters, short description to 80, and full description to 4,000; verify before submitting.
- Clearly describe the app's gesture-triggered torch, user-controlled background monitoring, battery impact, and hardware requirements. Avoid promising uninterrupted background operation or accuracy unsupported by measurements.

## Release procedure

1. Review current Play policies, target API and account-specific test-track requirements; record owner, date, and unresolved blockers. Do not assume a closed-test rule applies to every account.
2. Freeze the release candidate, update version code/name, audit dependencies and permissions, verify the production model asset, and run CI plus the device matrix. Review accessibility and privacy disclosures.
3. Configure a stable signing and key-recovery plan, including Play App Signing and protected upload credentials. Build a signed shipping Android App Bundle with installed Gradle, e.g. `gradle bundleRelease -Pshipping=true` after release signing is configured; the current project does not define that signing configuration. Test APKs generated from the same bundle, including upgrade from the previous production version.
4. Upload to internal testing, inspect the Play-generated artifacts and pre-launch reports, and run smoke tests on installed builds. Use closed/open testing as appropriate and satisfy applicable account-specific Play requirements before requesting production access.
5. Prepare the compliant store listing and declarations, submit for review, then use a staged rollout where available. Define a release owner, monitoring period, halt criteria, and incident contact before increasing exposure.
6. Monitor Android Vitals crashes, ANRs, excessive wake locks, battery issues, device-specific failures, reviews, and support reports. Halt rollout on a serious regression; ship a tested corrective update. Keep prior artifacts and symbols/mapping files for diagnosis. A halted rollout does not automatically downgrade devices already updated, so maintain a forward-fix and recovery plan.

## Release gates

- [ ] Current policy, target API, account/testing requirements, and `specialUse` justification reviewed; Play declarations completed.
- [ ] Signed shipping bundle and Play-delivered installation/upgrade verified with a valid model; development controls are absent.
- [ ] Automated checks pass and documented physical-device, recognition, accessibility, performance, battery, and soak criteria are met or explicitly accepted by the release owner.
- [ ] Store listing, screenshots, support contact, privacy policy, Data safety, and applicable app-content forms match the shipped behavior.
- [ ] Staged rollout, monitoring, incident response, and forward-fix plans have named owners.

## Sources

- [Google Play: best practices for your store listing](https://support.google.com/googleplay/android-developer/answer/13393723?hl=en)
- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [National Wire: deploying Android apps on Google Play](https://nationalwire.com.ng/deploying-android-apps-on-google-play-store-best-practices-and-tips/) (general background; defer to current official requirements).
- [Foreground service types and Play declarations](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Play developer policy center](https://play.google.com/policy)
- [Play app testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Android testing fundamentals](https://developer.android.com/training/testing/fundamentals)
- [Android Vitals](https://developer.android.com/topic/performance/vitals)

## Action plan

### Status and scope

The current GitHub Actions workflow runs the pure-Java core test script and builds separate development and shipping **debug APKs**. `-Pshipping=true` selects shipping behavior; it does not select or sign a release build. The repository now includes a Gradle wrapper JAR generated with the installed Gradle 8.13 distribution; `gradle-wrapper.properties` pins that distribution's SHA-256. A per-user toolchain is installed with Temurin JDK 17.0.21, Gradle 8.13, Android API 36, Build Tools 35.0.0, command-line tools 23, and platform-tools 37.0.1.

**Local verification (2026-11-05):** `gradlew.bat --version` succeeds. Development and shipping debug APK builds succeed with the wrapper, and `assembleDebugAndroidTest` succeeds for both variants. All four pure-Java core test entry points pass, and `lintDebug` succeeds with zero errors and five warnings. On a moto g53 5G running Android 14 (SDK 34), device preflight passed; both dev and shipping instrumentation runs passed both tests (2 passed, 0 failed, 0 skipped per variant). Evidence: `test-results/device/20261105-182035-ZF524979X8-dev/summary.json` and `test-results/device/20261105-182920-ZF524979X8-shipping/summary.json`. Locked-device and invalid-serial checks and failure injection remain pending. The remaining lint warnings concern the pinned Gradle version, portrait orientation, the continuous-service wake lock, and the API-26 adaptive-icon resource qualifier. The launcher icon and explicit no-backup extraction rules are now present. Emulator virtualization remains disabled; release signing, a signed AAB, and Play Console review are also outstanding.

Use the feature-specific plans for [gesture detection](gesture-detection.md), [training](personalized-training.md), [background monitoring](background-monitoring.md), [model shipping](model-storage-and-shipping.md), and [production UI/battery guidance](production-ui-and-battery.md). This plan is the cross-feature release sequence; no step implies Google approval.

### P0: Establish reproducible candidates

1. Record the release candidate's source revision, Java version, installed Gradle version, Android SDK/build-tools versions, OS, and build variant. Check `.github/workflows/build-apk.yml`, `app/build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, and the restored `gradle-wrapper.jar`. The wrapper distribution is pinned to Gradle 8.13 with its SHA-256 checksum.
2. Run `bash tests/run-core-tests.sh` and archive the result. The script runs `CoreSmokeTest`, `SeedDedupTest`, `MonitoringStartFlowTest`, and `StreamingDetectorTest`, and CI invokes it on every workflow run. These checks do not replace Android integration or device testing.
3. Build the default development debug APK and the shipping debug APK with `gradle assembleDebug` and `gradle assembleDebug -Pshipping=true`. CI now assembles and uploads both variants separately. Confirm package IDs, version values, bundled model behavior, and development-only UI separation. A debug shipping APK is useful for verification but is not a Play release artifact.
4. `lintDebug` has been run successfully with zero errors and five warnings; review the remaining warnings before release. The initial Android instrumentation suite and install-and-test script are implemented; run them with the device script described in [Device install and test automation](#device-install-and-test-automation). Expand coverage to permissions, service startup/stop, notification actions, model load failures, and the shared battery-start flow in both variants only after Phase 1 device validation. Do not report compilation or installation alone as a passing device test. The current workstation has virtualization disabled and no connected device, so installation and instrumentation execution remain pending.

**Acceptance:** Build/test logs identify the exact revision and variants; current core tests pass; gaps are listed rather than marked complete. See feature plans for expected test cases.

### P0: Prepare a signed shipping bundle

5. Select a release owner and a key-management method. Configure release signing only after the owner has chosen secure storage and recovery, such as a protected CI secret store or a documented offline process. Never commit keystores, passwords, private keys, or credentials. Decide how Google Play App Signing and the upload key will be managed.
6. `app/build.gradle` now accepts upload signing values from the environment and fails closed for shipping release tasks when they are absent. After the upload key and GitHub secrets are configured, verify a **shipping** Android App Bundle with `bash ./gradlew bundleRelease -Pshipping=true -PversionCode=<new-code>` on Linux/macOS, or `gradlew.bat bundleRelease -Pshipping=true -PversionCode=<new-code>` on Windows. Do not put secrets in command history or logs. Local release signing has not been configured or verified because no upload key was supplied.
7. Verify the resulting AAB signature, application ID `com.example.choptorch`, monotonically increasing version code, shipping `ENABLE_TRAINING=false`, model asset, absence of development training assets/UI, and installation/upgrade path from the previous production version. Install APKs generated from the same AAB with bundletool or through a Play testing track. Retain the exact AAB, build logs, model hash/provenance, and signing metadata without retaining secrets in the artifact.

**Acceptance:** A signed shipping AAB installs via the chosen test route and has the expected identity/version and model. This gate remains blocked until release signing and key custody are actually configured.

### P0: Complete current Play Console and policy checks

8. Before each submission, the account owner checks Play Console for the current target API deadline, account-specific testing/production-access requirements, required bundle format, forms, and review status. Record the policy review date and links; do not hard-code a testing rule or deadline from a third-party article.
9. Review the manifest and actual runtime for every permission and foreground service. Complete the Play Console `specialUse` service declaration with a truthful user-started sensor-monitoring justification and any evidence requested. Approval is an external review, not guaranteed by this plan.
10. Audit the app and all dependencies for data collection, storage, sharing, identifiers, SDK behavior, and retention. Only then complete Data safety, privacy policy, content rating, target audience, ads, app access, and other applicable forms. Do not claim that sensor data is never stored: development examples and models are stored locally; disclose actual release behavior accurately.
11. Prepare an accurate store listing and screenshots that show the real shipped UI and describe hardware requirements, user-started monitoring, torch use, power costs, and known background limitations. Follow current official Play metadata and intellectual-property policies. Treat the National Wire article as general context, not a source of policy requirements.

**Acceptance:** All required account forms are complete and consistent with the tested artifact; special-use and other policy reviews have an explicit outcome; unresolved external gates are named and block publication where required.

### P1: Prove readiness for broad distribution

12. Define device coverage and pass thresholds before collecting results. Include API 26, API 33/34, target and latest supported Android versions where available; multiple OEMs/hardware tiers; missing flash/sensor behavior where available; fresh install and upgrade; permission grant/denial/revocation; lock screen; backgrounding; Doze; battery saver; Low Power Standby where supported; and the battery guidance flow. Record device, OS/build, variant, steps, result, and defect reference.
13. For gesture quality, use representative consented data with held-out people/sessions. Measure true detections, missed gestures, false torch activations per monitoring hour, and latency. The product owner must set acceptable thresholds before testing; do not adopt sample thresholds from an unverified plan.
14. Run screen-off soak tests against an idle baseline and measure battery drain, wake-lock behavior, thermal effects, crashes, ANRs, memory, and sensor callback gaps. Test long enough to expose degradation. Do not assume a foreground service, wake lock, or battery allowlist prevents all restrictions.
15. Audit accessibility and device adaptability: TalkBack/focus order, meaningful control labels, touch targets, text scaling, contrast, permission/error recovery, and supported screen sizes. Fix release-blocking issues and record accepted residual limitations.

**Acceptance:** A release report contains actual results, method, sample size, approved thresholds, device gaps, defects, and owner sign-off. No untested scenario is reported as passed.

### P1: Roll out and operate safely

16. Before production, name the release owner and support contact; define staged rollout percentages/timing, monitoring window, stop criteria, and escalation path. Monitor Android Vitals crashes, ANRs, battery/wake-lock reports, device-specific failures, and user feedback.
17. If a serious regression appears, halt further rollout and use a tested forward-fix plan. A halted rollout does not downgrade users who already received the update. Keep prior artifacts and any required mapping/symbol files, and document the corrective release path.
18. After release, reconcile field failures with the test matrix and update regression tests, user documentation, and known limitations before increasing rollout or shipping a follow-up.

**Release record:** Preserve candidate revision, variant, signed artifact identifier, model hash/provenance, core/Android/device test results, policy review date, Play Console status, rollout decisions, and named approvals. Never mark manual or external checks complete without evidence.

## Device install and test automation

### Status

Proposed and not implemented. The repository has no `androidTest` source set, no instrumentation test dependencies, and no install-and-test script. Pure-Java checks in `tests/run-core-tests.sh` do not satisfy this feature. Device-level behavior remains unverified until the suite and script exist and have run on an emulator or physical device.

### Goal

Provide one repeatable command that builds a selected ChopTorch variant, installs it on exactly one chosen emulator or device, runs the Android instrumentation suite, and writes an evidence folder with the device, build, results, and filtered logs. The script must fail visibly on any setup, build, install, or test problem. It must not report a partial run as a pass.

### Scope

In scope:

- Development variant (`com.example.choptorch.devclassic`) and shipping variant (`com.example.choptorch`, built with `-Pshipping=true`).
- Installing the app APK and the test APK, with in-place upgrade by default and an explicit clean-install option.
- Instrumentation tests for permissions, service lifecycle, notification actions, model load failures, torch availability, activity recreation, and the battery-start flow.
- Evidence capture: results, build identity, filtered app logs, and a screenshot on failure.

Out of scope:

- Play Console upload or tester-track installation, which belong to the publishing workflow and release procedure.
- Gesture recognition quality, false activations, latency, battery drain, and soak tests. These need the consented-data and measurement protocols in the sections above.
- Orchestrating a multi-device matrix. Run the script once per device for now.
- Running emulators in GitHub Actions. That is a later decision requiring its own review.

### Prerequisites

- Windows PowerShell 5.1 or later as the script entry point, JDK 17, Android SDK platform-tools, build-tools 35.0.0, and platform 36, as already installed in the local toolchain.
- The Gradle wrapper (`gradlew.bat`) and the test dependencies added in Phase 1.
- Exactly one target: a running emulator, or a physical device with USB debugging authorized. Wireless ADB is accepted only when selected by serial.
- Device API 26 or later, matching `minSdk`.
- Device screen awake and unlocked. The script does not attempt to unlock a device because the PIN or pattern is unknown.

### Parameters

| Parameter | Required | Meaning |
| --- | --- | --- |
| `-Variant dev\|shipping` | Yes | Selects the variant. `shipping` adds `-Pshipping=true`. Default `dev`. |
| `-Serial <id>` | When more than one target is attached | Selects the device. The script never picks one silently. |
| `-CleanInstall` | No | Uninstalls the app and test packages first. Off by default because it deletes local examples and model data. |
| `-TestFilter <class or method>` | No | Limits the run. Release evidence uses the full suite. |
| `-PreflightOnly` | No | Runs device checks and stops before building or installing. |
| `-OutputDir <path>` | No | Defaults to `test-results/device/<UTC timestamp>-<serial>-<variant>/`. This folder must be git-ignored. |
| `-TimeoutMinutes <n>` | No | Overall run limit. Defaults to 30. |

### Procedure

1. **Preflight.** Find `adb` on `PATH` or in the SDK platform-tools folder, then start the server. List devices with `adb devices -l`. Require exactly one entry in the `device` state, or the `-Serial` target. Reject `unauthorized`, `offline`, and missing targets. Read model, SDK level, release, build fingerprint, and ABI. Stop if SDK is below 26, the screen is off, or the keyguard is showing. Exit code 2 covers all preflight failures.
2. **Build.** Run `gradlew.bat assembleDebug`, or `assembleDebug -Pshipping=true` for shipping. Build the test APK with `assembleDebugAndroidTest` using the same flag. Phase 2 must confirm that the test APK targets the selected application ID. Record the git revision, whether the working tree is dirty, and SHA-256 hashes of both APKs. Build failure exits with code 3.
3. **Install.** With `-CleanInstall`, uninstall both packages and ignore "not installed" responses. Otherwise install in place with `adb -s <serial> install -r`. Install the app APK first, then the test APK. After installation, confirm with `dumpsys package` that the `versionCode` matches the build. A signature mismatch, such as a CI-built APK over a local build, stops the run with code 3. The script does not uninstall automatically in that case.
4. **Run.** Clear logcat, then run the instrumentation through `adb shell am instrument -w -r` with the test runner and the optional `-TestFilter`. Stop the process after `-TimeoutMinutes` and exit with code 4. Parse the runner's result stream into per-test statuses and totals. The parser must be unit-tested against recorded sample outputs. The script must not treat missing or unparseable results as a pass. Retries are off by default, and any retry must be recorded as a separate attempt.
5. **Evidence.** Save the raw runner output, the filtered logcat, and `summary.json`. The summary records the script version, git revision and dirty flag, variant, application ID, version name and code, APK hashes, device serial, model, API level, build fingerprint, UTC start and end times, per-test results, totals, exit code, and file names. On failure, capture a screenshot with `adb exec-out screencap -p`.
6. **Cleanup.** Run `am force-stop` on the app. Stop any monitoring service the tests left running. Remove permission grants that the test changed. Uninstall only when `-CleanInstall` was requested. Tests should restore their own state in teardown.

### Exit codes

| Code | Meaning |
| --- | --- |
| 0 | All selected tests passed and evidence was written. |
| 1 | One or more tests failed or errored. |
| 2 | Preflight failed: no device, several devices without `-Serial`, unauthorized, offline, locked, or API below 26. |
| 3 | Build or install failed, including signature mismatch. |
| 4 | Instrumentation crashed, timed out, or produced no parseable result. |

### Logging and privacy

- Keep only logcat lines from the app's process IDs and the test runner. Do not keep system-wide logs.
- Logs and screenshots must not contain raw motion samples or personal data. The app must not log sensor values at release levels.
- Evidence folders stay local and are not committed. Keystores, passwords, and tokens never enter the script, logs, or summary.

### Required test cases (to be written in Phase 1)

These names are proposals. Each test needs an explicit pass condition.

- `PermissionFlowTest`: denied CAMERA or notification permission shows a recovery path and does not crash.
- `MonitoringServiceLifecycleTest`: start and stop update the UI, and activity recreation keeps the displayed service state correct.
- `NotificationActionTest`: the stop action stops the service, and the open action launches the activity.
- `ModelLoadFailureTest`: a missing or corrupt model is reported without crashing. The missing-asset case applies to shipping builds.
- `TorchUnavailableTest`: a Camera2 or missing-flash failure is handled and reported.
- `ShippingBuildTest`: the shipping variant exposes no training UI and has `ENABLE_TRAINING` set to false.
- `BatteryStartFlowTest`: the guidance is optional and non-blocking, and returning from Settings requires explicit confirmation.

### Phases

1. **Test infrastructure.** Add the `androidTest` source set, runner, and rules. Add one smoke test that launches `MainActivity`. Confirm that `assembleDebugAndroidTest` builds for both variants.
2. **Script.** Implement preflight, build, install, run, parsing, evidence, and cleanup. Add `-PreflightOnly` and unit-test the result parser against recorded output.
3. **Validation.** Run on an emulator at API 26 and the latest available API, and on at least one physical device. Record device, build, and results in the release report.
4. **Later.** Multi-device matrix runs and any CI emulator job require a separate decision.

### Acceptance

- The smoke test passes on a clean emulator or device and produces the full evidence folder.
- Injected failures (for example, a denied permission or a killed service) produce exit code 1 and a failure screenshot.
- No device produces exit code 2. Two devices without `-Serial` produce exit code 2.
- Signature mismatch produces exit code 3 without uninstalling.
- A forced timeout produces exit code 4.
- `test-results/` is git-ignored, and `git diff --check` passes.

### Implementation runbook

This runbook is written for an implementer who follows steps literally. Complete each step in order, run its Verify command, and record the output before moving on.

#### Ground rules

1. Do not change app behavior. Do not edit `MainActivity.java`, `GestureDetectionService.java`, `GestureDetector.java`, `GestureStore.java`, `TinyNeuralNet.java`, `MonitoringStartFlow.java`, `TorchController.java`, or any model file. If a step seems to require one of them, stop and report.
2. Do not edit `.github/workflows/*`, `versionCode`, `versionName`, or signing configuration.
3. Do not commit, push, or create branches.
4. Do not place secrets, keystores, or tokens in any file, command, or log.
5. Run one terminal command at a time from the repository root, `C:\Users\barba\Downloads\ChopTorch-Fresh-Download\ChopTorch`. Use `.\gradlew.bat`. Use `;` to chain commands, never `&&`.
6. If a Verify fails, retry that step once. If it fails again, stop and report the exact error text and step number. Do not guess a fix.
7. Do not uninstall the app, clear data, or change device permissions except where a step says so.

#### Facts verified in the repository

- Application IDs: dev is `com.example.choptorch.devclassic` (`applicationIdSuffix` applies when `shipping` is false). Shipping is `com.example.choptorch`. Source: `app/build.gradle`.
- Dev and shipping both use the `debug` build type, so both write to `app/build/outputs/apk/debug/app-debug.apk`. Copy each APK out of the build folder immediately after each build.
- The service is `com.example.choptorch.GestureDetectionService`. It exposes public static `isRunning()` and `command(Context, String)`. Actions are `com.example.choptorch.START`, `.STOP`, and `.FALSE_POSITIVE`. `ACTION_STOP` calls `stopMonitoring()` and `stopSelf()`.
- On Android 8 and later, starting a service from the background can be refused. Launch `MainActivity` in an `ActivityScenario` before any service call from a test.
- `BuildConfig.ENABLE_TRAINING` is `true` for dev and `false` for shipping.
- `app/build.gradle` has no `dependencies` block and no `testInstrumentationRunner` yet.
- The UI controls are created in code with no resource IDs. Do not write tests that depend on resource IDs.
- `tests/run-core-tests.sh` is pure Java and must keep passing.

#### Scope check

The seven test cases in "Required test cases" are a target list. Phase 1 writes two tests, `SmokeLaunchTest` and `ShippingBuildFlagTest`. `ShippingBuildFlagTest` covers the `ShippingBuildTest` case. The other six remain deferred until Phase 3 passes. Do not start them early.

#### Phase 1: Test infrastructure

##### Step 1.1: Add the runner and test dependencies to app/build.gradle

Inside `defaultConfig { ... }`, after the line that begins with `buildConfigField 'boolean', 'ENABLE_TRAINING'`, add:

```groovy
        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
```

At the end of the file, after the closing brace of `android { ... }`, add:

```groovy
dependencies {
    androidTestImplementation 'androidx.test:core:1.6.1'
    androidTestImplementation 'androidx.test:runner:1.6.2'
    androidTestImplementation 'androidx.test:rules:1.6.1'
    androidTestImplementation 'androidx.test.ext:junit:1.2.1'
}
```

Verify: `.\gradlew.bat assembleDebugAndroidTest` succeeds. If a dependency fails to resolve, change only that one version to the newest 1.x release that resolves from Google Maven, record the version, and run the verify once more.

##### Step 1.2: Create SmokeLaunchTest

Create `app/src/androidTest/java/com/example/choptorch/SmokeLaunchTest.java` with exactly this content:

```java
package com.example.choptorch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SmokeLaunchTest {
    @Test
    public void mainActivityLaunchesAndStaysOpen() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity);
                assertFalse(activity.isFinishing());
            });
        }
    }
}
```

Verify: `.\gradlew.bat assembleDebugAndroidTest` succeeds.

##### Step 1.3: Create ShippingBuildFlagTest

Create `app/src/androidTest/java/com/example/choptorch/ShippingBuildFlagTest.java` with exactly this content:

```java
package com.example.choptorch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ShippingBuildFlagTest {
    @Test
    public void trainingFlagMatchesRequestedVariant() {
        String expected = InstrumentationRegistry.getArguments().getString("expectTraining");
        assertNotNull("Pass -e expectTraining true or false", expected);
        assertTrue("expectTraining must be true or false", expected.equals("true") || expected.equals("false"));
        assertEquals(Boolean.parseBoolean(expected), BuildConfig.ENABLE_TRAINING);
    }
}
```

Verify: both of these succeed:

- `.\gradlew.bat assembleDebugAndroidTest`
- `.\gradlew.bat assembleDebugAndroidTest -Pshipping=true`

If `BuildConfig` does not resolve, stop and report the exact error. Do not add imports or change the package.

##### Step 1.4: Check that nothing else broke

- Run `.\gradlew.bat lintDebug`. Expect zero errors. The baseline is five warnings. If lint reports an error in the new test files, fix only those test files.
- Run `bash tests/run-core-tests.sh`. Expect all four entry points to pass.

##### Step 1.5: Ignore the results folder

Add the line `test-results/` to `.gitignore` at the end of the file. Verify with `Select-String -Path .gitignore -Pattern '^test-results/$'`, which must show one match.

#### Phase 2: Install-and-test script

##### Step 2.1: Create scripts/device-test.ps1

Create `scripts/device-test.ps1`. Start with this parameter block exactly:

```powershell
param(
    [ValidateSet('dev', 'shipping')] [string]$Variant = 'dev',
    [string]$Serial = '',
    [switch]$CleanInstall,
    [string]$TestFilter = '',
    [switch]$PreflightOnly,
    [string]$OutputDir = '',
    [int]$TimeoutMinutes = 30
)
```

PowerShell gotchas to respect:

- Do not name any parameter or variable `$Args`. It is reserved. Use `$AdbArgs`.
- Set `$ErrorActionPreference = 'Continue'`. Native stderr under `'Stop'` throws. Check `$LASTEXITCODE` after every native call.
- Never pipe `adb exec-out` binary output through PowerShell. It corrupts PNG data. Take screenshots on the device, then `adb pull` the file.
- Some `adb install` versions exit 0 on failure. Check that the output contains a line exactly equal to `Success`.

Add this helper, and use it for every adb call:

```powershell
function Invoke-Adb {
    param([string]$Target, [string[]]$AdbArgs)
    $prefix = @()
    if ($Target) { $prefix = @('-s', $Target) }
    $out = & $script:AdbPath @prefix @AdbArgs 2>&1
    [pscustomobject]@{ Code = $LASTEXITCODE; Lines = @($out | ForEach-Object { "$_" }) }
}
```

Use `$script:Serial` as the target for every device call after preflight.

##### Step 2.2: Locate adb and define paths

In this order, use the first match:

1. `$env:ANDROID_HOME\platform-tools\adb.exe`
2. `$env:ANDROID_SDK_ROOT\platform-tools\adb.exe`
3. `adb` from `where.exe adb`

If none exists, write a message and exit with code 2.

Set `$script:Variant` to `dev` or `shipping`. Set:

- `$appId`: `com.example.choptorch.devclassic` for dev, `com.example.choptorch` for shipping
- `$testPkg`: `$appId + '.test'`
- `$expectTraining`: `'true'` for dev, `'false'` for shipping

If `-OutputDir` is empty, use `test-results\device\<yyyyMMdd-HHmmss>-<serial with ':' replaced by '_'>-<variant>`. Create the folder with `New-Item -ItemType Directory -Force`. Record the UTC start time.

##### Step 2.3: Preflight

Run these checks in order. Each failure exits with code 2 and writes the summary.

1. `adb start-server`.
2. `adb devices -l`. Parse the lines after `List of devices attached`. Each line has the serial, then the state.
   - Keep only entries with state `device`. Ignore `unauthorized`, `offline`, and `no permissions`. Report them in the summary.
   - With no `-Serial`, exactly one `device` entry must exist. Zero or several exit with code 2.
   - With `-Serial`, that exact serial must have state `device`.
3. Read device properties with `shell getprop <name>`: `ro.product.model`, `ro.build.version.sdk`, `ro.build.version.release`, `ro.build.fingerprint`, and `ro.product.cpu.abi`. Trim each value.
4. If `ro.build.version.sdk` is below 26, exit with code 2.
5. Run `shell dumpsys power`. Find the line containing `mWakefulness=`. It must say `Awake`, otherwise exit with code 2.
6. Run `shell dumpsys window`. If any line contains `isKeyguardShowing=true` or `mShowingLockscreen=true`, exit with code 2.
   Verify this pattern on the target device in Phase 3. Lock and unlock the device, compare the output, and correct the pattern if needed.
7. With `-PreflightOnly`, write the summary with `exitCode` 0 and stop.

##### Step 2.4: Build

Set the task list:

- dev: `assembleDebug`, `assembleDebugAndroidTest`
- shipping: `assembleDebug -Pshipping=true`, `assembleDebugAndroidTest -Pshipping=true`

Run `& .\gradlew.bat @tasks *>&1 | Tee-Object -FilePath <evidence>\build.log`. A nonzero `$LASTEXITCODE` exits with code 3.

After the build, copy these files immediately into the evidence folder. Stop with code 3 if either is missing:

- `app\build\outputs\apk\debug\app-debug.apk` to `app.apk`
- `app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk` to `test.apk`

Both variants write to the same paths. Copying before the next build keeps the evidence correct.

Record the SHA-256 of both copies with `Get-FileHash -Algorithm SHA256`. Record `git rev-parse HEAD` and whether `git status --porcelain` is non-empty as `gitDirty`.

##### Step 2.5: Install

1. If `-CleanInstall`, run `uninstall $appId` and `uninstall $testPkg`. Ignore failures.
2. Run `install -r <app.apk>`. Require a `Success` line, otherwise exit with code 3. Keep the full output in the evidence folder.
3. Run `install -r <test.apk>`. Require a `Success` line, otherwise exit with code 3.
4. Run `shell pm list instrumentation`. It must contain `$testPkg/androidx.test.runner.AndroidJUnitRunner`. If it does not, exit with code 3.
5. Run `shell dumpsys package $appId`. Record the first `versionCode=` and `versionName=` values in the summary.

The script never uninstalls automatically. A signature mismatch fails at step 2 with code 3.

##### Step 2.6: Run instrumentation

1. Run `logcat -c`.
2. Build the arguments in this order:
   `shell am instrument -w -r -e expectTraining $expectTraining`, then `-e class $TestFilter` only if `-TestFilter` was set, then `$testPkg/androidx.test.runner.AndroidJUnitRunner`.
3. Run adb with `Start-Process -FilePath $script:AdbPath -ArgumentList $list -RedirectStandardOutput run.txt -RedirectStandardError run.err.txt -NoNewWindow -PassThru`. Use `-s $serial` in `$list`.
4. Call `WaitForExit($TimeoutMinutes * 60 * 1000)`. If it returns false, call `Kill()` and exit with code 4.

##### Step 2.7: Parse the raw output

Run the parser on `run.txt`. Build the parser from a real captured output in the first run. Save that output as a fixture at `tests/device/fixtures/raw-run-sample.txt`, and write the parser to match it.

Apply these rules, then verify each one against the real output:

- A test result is a block of `INSTRUMENTATION_STATUS:` lines that ends with `INSTRUMENTATION_STATUS_CODE:`. Read `class=` and `test=` from the same block.
- Status codes: `1` is start, which is ignored. `0` is pass. `-1` is error. `-2` is failure. `-3` is ignored. `-4` is assumption failure.
- The run is complete only when an `INSTRUMENTATION_CODE:` line exists. If it is missing, exit with code 4.
- Zero parsed test results exit with code 4.
- Any status other than `0` exits with code 1. This includes skipped tests. The suite must not use `Assume` in Phase 2.
- Write `results.txt` with one line per test: `class#test  STATUS`.

##### Step 2.8: Evidence and cleanup

On a failed run only, take a screenshot:

1. `shell screencap -p /sdcard/choptorch-fail.png`
2. `pull /sdcard/choptorch-fail.png <evidence>\failure.png`
3. `shell rm /sdcard/choptorch-fail.png`

Capture logs:

1. Run `shell pidof -s $appId` and record the PID if one is returned.
2. Run `logcat -d -v threadtime` to a temporary file.
3. Keep only lines containing the PID, or the `$appId` text if there is no PID. Save them as `app-logcat.txt`.
4. Delete the temporary full dump. Do not keep system-wide logs.

Cleanup: run `shell am force-stop $appId`. Do not clear data, and do not uninstall unless `-CleanInstall` was set.

##### Step 2.9: Write summary.json and exit

Before every exit, write `summary.json` with these fields:

- `scriptVersion`: `"1.0"`
- `gitRevision`, `gitDirty`
- `variant`, `applicationId`, `testPackage`
- `versionCode`, `versionName`
- `appApkSha256`, `testApkSha256`
- `device`: `serial`, `model`, `sdk`, `release`, `fingerprint`, `abi`
- `startedUtc`, `endedUtc`
- `tests`: array of `{ "class", "name", "status" }`
- `totals`: `passed`, `failed`, `skipped`
- `exitCode`
- `files`: the list of evidence file names

Exit codes:

| Code | Meaning |
| --- | --- |
| 0 | All selected tests passed and evidence was written. |
| 1 | One or more tests failed, errored, or were skipped. |
| 2 | Preflight failed. |
| 3 | Build or install failed. |
| 4 | Instrumentation crashed, timed out, or produced no parseable result. |

#### Phase 3: Validation on a device

The physical moto g53 5G is connected and available. Emulator virtualization remains disabled. The preflight and dev run below have passed; remaining checks are marked pending.

Run these in order. Record each command's exit code and the summary file path.

1. `-Serial ZF524979X8 -PreflightOnly`: passed, exit 0. Summary: `test-results/device/20261105-181837-ZF524979X8-dev/summary.json`.
2. `-Serial ZF524979X8 -Variant dev`: passed, exit 0; 2 passed, 0 failed, 0 skipped. Summary: `test-results/device/20261105-182035-ZF524979X8-dev/summary.json`.
3. `-Serial ZF524979X8 -Variant shipping`: passed, exit 0; 2 passed, 0 failed, 0 skipped. Summary: `test-results/device/20261105-182920-ZF524979X8-shipping/summary.json`.
4. `.\scripts\device-test.ps1 -Serial NOTREAL`: pending. Expect exit 2.
5. Lock the phone and run `.\scripts\device-test.ps1 -PreflightOnly`: pending. Expect exit 2; unlock afterward.
6. Failure injection: pending. Temporarily create `app/src/androidTest/java/com/example/choptorch/FailureInjectionTest.java` with one test that calls `org.junit.Assert.fail("injected")`. Run the dev variant. Expect exit 1, a `failure.png`, and `app-logcat.txt`. Then delete the file and run `git status --short`; the file must not appear.
7. Record each remaining device result and summary path here after it is run.

Stop and report if the parser cannot read real output, or if `pm list instrumentation` does not show the test package.

#### Phase 4: Out of scope

Multi-device matrix runs, emulator runs in GitHub Actions, and the deferred test cases need separate approval.

#### Done when

- Phase 1 files exist. Both `assembleDebugAndroidTest` variants build, `lintDebug` reports zero errors, and the core tests pass.
- `scripts/device-test.ps1` implements steps 2.1 through 2.9.
- Phase 3 steps 1 through 7 are recorded with real results. Any step not run is listed as not run.
- No app source, workflow, signing, or version file changed except as listed above. `git status --short` is the only check needed for this.

## GitHub Actions Internal Publishing

`.github/workflows/publish-play-internal.yml` is manual-only, runs from `main` or `master`, runs the core tests, builds a signed shipping AAB, and uploads only to the Google Play **internal** track. It never uploads to production. The workflow requires a version-code input greater than the latest code in Play Console. The existing CI workflow remains responsible for development and shipping debug APKs.

Before the first workflow run:

1. Create the Play Console app for `com.example.choptorch`, complete any initial app/bootstrap upload that the account requires, and link Play Console to a Google Cloud project with the Android Publisher API enabled.
2. Create a Google service account and grant it only the Play Console permissions required to upload to testing tracks. Configure Google Workload Identity Federation for GitHub Actions; restrict the provider condition to this repository and the `main`/`master` publishing workflow. Grant the federated identity permission to impersonate that service account.
3. In repository Settings, create the `play-internal` environment and configure required reviewers. Add these environment variables: `GCP_WORKLOAD_IDENTITY_PROVIDER` (full provider resource ID) and `GCP_SERVICE_ACCOUNT` (service-account email). GitHub OIDC is used instead of a long-lived Play JSON key.
4. Add the existing Play upload keystore as the `ANDROID_UPLOAD_KEYSTORE_BASE64` environment secret, and add `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, and `ANDROID_UPLOAD_KEY_PASSWORD` as separate environment secrets. Use the existing Play upload key if the app is already enrolled in Play App Signing; never commit the keystore or pass secret values through chat or logs.
5. Dispatch **Publish shipping AAB to Play internal testing** from `main` or `master`, enter a version code greater than the current Play track, approve the protected environment, then verify the uploaded release and tester installation in Play Console.

The workflow and Gradle signing guard are repository-configured, but publishing cannot run until the external Play/Cloud setup and protected GitHub environment values above exist. Production promotion remains a separate, human-approved Play Console decision.