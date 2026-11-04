# ChopTorch v2.1 — faster background detection, corrected recovered-example count

ChopTorch uses accelerometer + gyroscope data, a Kalman-fused attitude estimate,
27 motion features, and an offline 27→18→1 neural network to turn the phone flashlight
on/off with a double-chop gesture. The app has background monitoring, false-positive
feedback, model export, and a production build without training functionality.

## What changed in v2.1

**Faster detection (without changing your pretrained neural network):**

- Evaluate the unchanged 1.9-second sensor-feature window every **60 ms** instead
  of every 160 ms, keeping the same 0.82 confidence threshold and two consecutive
  positive evaluations.
- Recognize the **newest double-peak event**, rather than allowing the same peaks
  to be detected repeatedly as the window slides. Only recent completed pairs
  can trigger. Use a **1.4-second event guard** in place of the old 3-second timer.
- Deliver 50 Hz sensor callbacks on a dedicated Android `HandlerThread` with
  batching latency set to zero to avoid main-thread congestion.

**Recovered-example bug:** In v2, first-launch initialization loaded your recovered
19 examples twice into memory (making 7 + 12 look like 38). v2.1 loads them once.
It can also repair an *exact*, accidentally persisted repeat of the known 19-item
seed without deleting any other recordings or feedback you added.

**Model currency:** When you add new examples, the old bundled model is not
misreported as retrained on them. Train again before resuming recognition.

The original learned **27 feature definitions, 1.9-second window, and weights**
remain unchanged. Your recovered `choptorch_model.json` is included.

> Recognition is not instantaneous at startup: the detector first needs roughly
> 1.6 seconds of sensor history. After that, it can recognize a fresh double-chop
> much sooner than before, depending on its confidence, the phone's sensor/LED
> hardware and Android scheduling. Real-world latency is not yet measured.

## Your recovered v1 data is included

This project contains:

- `app/src/main/assets/choptorch_model.json` — your original trained network.
- `app/trainingAssets/choptorch_seed_examples.json` — your **19 original examples**:
  **7 double-chops + 12 non-double-chops**.

On the very first launch of a new **v2.1 development APK**, the app restores
these examples, then loads the original model, so you can immediately test and
continue training. The seed never overwrites already-existing data. For shipping
builds the gesture examples asset is excluded and the training/feedback/export
screens are disabled; only the pretrained model is bundled.

**Important privacy note:** Gesture examples and model weights are files within
this source project; if you publish it on a public GitHub repository, anyone can
inspect them. Use a private repository if that is not what you intend.

## Safely install the new development version

New GitHub debug builds may have different signing certificates. An APK with a
new signature cannot update an installed APK of the same package name.
Uninstalling an earlier APK permanently removes its private data unless you
backed it up first.

**To protect your v1 and v2 apps, v2.1 training uses a NEW package name:**
`com.example.choptorch.devfast`. It installs **side-by-side** with the original
v1 (`com.example.choptorch`) and v2 dev (`com.example.choptorch.dev`).
It does **not** read any extra recordings or learned corrections from your earlier
v2 `.dev` install. If those are important, export the old v2 model and keep that
app installed until you have backed up any data you want to retain.

### Build on GitHub Actions

1. Upload this project, including its `.github` folder, into your GitHub repository.
2. Open **Actions → Build Android APK → Run workflow**.
3. Leave `shipping` **unchecked** to make the development/training APK.
4. Download the `ChopTorch-debug-apk` artifact from the successful run, extract
   `app-debug.apk`, and install it on your Android phone.
5. On first launch, open the training page: the count should be **7 positive**
   and **12 negative**. Return home and start background detection.
6. Try double chops and test everyday movements. A false activation turning the
   torch on can be reported with **Flag last ON as false positive**; the recorded
   triggering features become a negative example and retraining runs on-device.

Build with Android Studio if preferred. Android SDK 36, Java 17, AGP 8.13.2 and
Gradle 8.13 are specified by the project. The GitHub workflow runs the pure-Java
tests before Gradle's Android build.

### Tests (requires Java, not the Android SDK)

```bash
bash tests/run-core-tests.sh
```

The tests check basic Kalman fusion, synthetic NN training/classification, two
distinct streaming double-chops (trigger once each), no detection for single
chops, and safe duplicate recovery. In the synthetic 50 Hz test, recognition
follows the completed pair by 120–140 ms. This does **not** guarantee that
latency on your device, where the timing and model confidence will differ.

## Export your model, then build production mode

1. Continue training on the **new v2.1 development app** if needed. Then tap
   **Export NN weights (JSON)** and save the exported model to your phone.
2. Replace `app/src/main/assets/choptorch_model.json` in your GitHub project
   with the exported weights.
3. In **Actions → Build Android APK → Run workflow**, enable `shipping`.
   Alternatively, run `gradle assembleDebug -Pshipping=true`.
4. The production-mode APK bundles the model but omits the training page,
   feedback and export functionality. It does not import local training data.

Shipping mode uses application ID `com.example.choptorch`, which can conflict
with v1; a different APK signature may prevent an in-place install. Keep any
valuable v1 data backed up before uninstalling v1. For real distribution, use
an intentionally configured **persistent release signing key** and build a
release APK/AAB. A GitHub debug APK is suitable for testing, not storefront release.

## Background monitoring and limits

- A user-initiated foreground service owns the sensor listeners and flashlight,
  including when the phone is locked, subject to Android battery restrictions.
  The service shows an ongoing notification with a Stop control. It does not
  secretly auto-start after restart or phone reboot.
- A partial wake lock is held while monitoring non-wakeup IMU sensors; this
  can use significant battery. Android device-specific battery restrictions
  and camera torch arbitration may still limit background performance.
- `CAMERA`, foreground-service (`specialUse`), notification, and wake lock
  declarations are included. Publishing on Google Play may require review of
  the foreground-service subtype.
- Feedback is available only in development mode, only for the **latest detected
  gesture that turned the torch ON**, and only within 10 minutes. It uses the
  exact 27 inputs that caused that activation, not the phone movement when you
  touch the feedback button. You can submit an individual detection only once.
- Keep the phone securely in your hand during training; “chop” means moving the
  phone, not wielding an actual knife.

## Main files

- `GestureDetector.java`: faster, event-based inference using the same NN features.
- `GestureFeatures.java`: the original 27 features plus a non-model event timestamp.
- `GestureDetectionService.java`: foreground sensor thread, notification, torch.
- `GestureStore.java`: corrected seed loading, model management, feedback, export.
- `RecoveredExampleDeduper.java`: narrowly targeted exact-seed repair.
- `MainActivity.java`: feedback and optional training/export controls.
- `app/build.gradle`: separate development ID, `-Pshipping=true` production flag.
- `.github/workflows/build-apk.yml`: selectable development/production APK builds.

**Build status:** Pure-Java tests passed in the sandbox. A full Android SDK
compilation and test on a real phone have **not** been performed in this
sandbox. Use the included GitHub Actions workflow to produce and verify an APK.
