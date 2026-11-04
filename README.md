# ChopTorch v2 — background double-chop flashlight + adaptive training

An offline Android Java app that uses accelerometer and gyroscope measurements, a Kalman-fused attitude estimate, a 27-feature double-chop detector, and a tiny 27→18→1 neural network to toggle the camera LED.


## This special project includes your recovered v1 training

This copy includes `app/src/main/assets/choptorch_model.json` (your original trained weights)
and `app/trainingAssets/choptorch_seed_examples.json` (your 19 v1 gesture examples).
On the **first launch of a new v2 development install**, it restores the 7 chop and 12
other-movement labels into the v2 app's own private storage, and uses the v1-trained
model immediately, so future false-positive reports can retrain from your real samples.
It will never replace an existing v2 training dataset (the seed is only applied if no
saved dataset exists). If you already used v2, the existing data wins.

In `-Pshipping=true` the development seed asset is **excluded** from the APK and the
training interface stays disabled; the weights file remains bundled as the pretrained
model. Note: the original motion examples ARE included in the project source and therefore
would be visible if you upload this project to a **public** GitHub repository. Use a
private repository if you do not want to publish those examples.

If you need to replace this model later, use the new v2 export button and update
`app/src/main/assets/choptorch_model.json` with the exported version.

## What's new in v2

1. **Continue listening in the background / with screen locked:** tapping **Start background detection** starts a visible foreground service. The service, not the Activity, owns the IMU listener, classifier, and torch. It continues after you switch apps or lock the phone, subject to Android power-management restrictions. The notification has a **Stop** action; **Stop background detection** in the app does the same. Detection does not begin automatically on boot or after a system kill. A `PARTIAL_WAKE_LOCK` is held ONLY while listening and released on Stop/service destruction; this can drain the battery.
2. **One-tap false-positive feedback:** after a double-chop detection turns the torch **ON**, the exact 27-element feature vector from the triggering sensor window is saved locally. For 10 minutes, tap **Flag last ON as false positive** on the home page or the notification action. This labels that **same window as a negative example**, clears it so it cannot be submitted twice, switches the torch off, and retrains from all saved labeled samples on a worker thread. Monitoring pauses predictions while the model is stale and resumes when training completes. The UI displays the result. This feature is available only in training builds.
3. **Export trained weights:** tap **Export trained NN weights (JSON)**, choose a destination using Android's document picker, and save `choptorch_model.json`. The JSON contains `modelVersion`, `featureVersion`, `featureCount`, `hiddenCount`, detection gate/threshold/window information, `w1`, `b1`, `w2`, `b2`, and `trainedCount`. **Only model parameters are exported, not your individual gesture examples.**
4. **Production mode:** build with `-Pshipping=true` after adding the exported JSON to `app/src/main/assets/choptorch_model.json`. The shipping build loads that bundled model, hides and disables all training, labeling, model export, and erase controls, and does not load/store the device's training samples. The build FAILS if the weights asset is missing; no random/untrained model is silently shipped.

## Updating from the original ChopTorch v1 (important)

GitHub-hosted debug builds may be signed with **different keys on different runs**. An APK signed with a different key cannot update the same package without uninstalling the existing app, and **uninstalling deletes your private v1 training examples and model** (`android:allowBackup=false`).

For this reason the **v2 training APK deliberately installs side-by-side** as `com.example.choptorch.dev`, leaving your original `com.example.choptorch` and its training data untouched. You will need to record/train fresh examples in the new v2 dev app because Android prevents an app from accessing another app's private data. After training v2, export its model and bundle it in the v2 shipping build. The shipping build uses the original application ID `com.example.choptorch`, so if the previous install has a different signing key, Android may require uninstalling that older app before shipping can be installed. The exported model is baked into the shipping app and will not be lost when the development app is later removed. For a real product configure a consistent private signing key.

## First build (training/development)

Requirements: Android 8.0+, Java 17, Android SDK 36, Android Gradle Plugin 8.13.2, Gradle 8.13. Use Android Studio or GitHub Actions.

```bash
gradle assembleDebug
```

Run tests without the Android SDK:

```bash
bash tests/run-core-tests.sh
```

For GitHub Actions: push the entire project including `.github/workflows/build-apk.yml`. Go to **Actions → Build Android APK → Run workflow**, leave **shipping** unchecked, and download the `ChopTorch-debug-apk` artifact from the successful run. The debug APK can be installed by allowing installation from your browser/file manager.

## How to train and improve it

1. Open **Open training page**; this stops background detection to avoid conflict with recording.
2. Gather at least 6 positive double chops and 6 negative everyday motions. For usable results record 20 or more of each, including fast phone pickups, walking, pocket movements and single chops. Perform the gesture with your **phone**, not a real knife.
3. Tap **Train neural network**, then **Back to flashlight → Start background detection**. Grant Camera and Notification permissions when prompted. **Lock the phone and try two chops.** The foreground notification indicates monitoring is active.
4. When an incorrect double-chop detection turns the torch ON, immediately tap **Flag last ON as false positive** in the app or the notification. The flashlight is forced off, that triggering window becomes a *negative* training sample, and the model retrains automatically. Only the most recent ON detection is eligible, for 10 minutes.
5. Continue training, especially with negatives. A high training-set accuracy is not proof of reliability; test on movements not used for training.

No sensor traces, feature vectors, or model weights are transmitted by this app. Data stays on the device unless you deliberately export the JSON model.

## Build with a pretrained model and NO training UI

1. On the trained development build, tap **Export trained NN weights (JSON)**. Save the file as `choptorch_model.json`.
2. Copy the exported file to `app/src/main/assets/choptorch_model.json` within your source project/repository. You can use GitHub's **Add file → Upload files** in this folder.
3. In GitHub **Actions → Build Android APK → Run workflow**, check **shipping**. This runs:

   ```bash
   gradle assembleDebug -Pshipping=true
   ```

   The output is a *debug-signed production-mode preview APK* — suitable for sideload testing only. Training/feedback/export are absent from the interface and inaccessible at runtime, even though the executable's UI code is shared.
4. For an actual store distribution, create your signing configuration, choose the appropriate release artifact (APK or AAB), and run a release build with the same flag:

   ```bash
   gradle assembleRelease -Pshipping=true
   ```

   The release variant is unsigned unless you configure a release signing key. Google Play may require you to justify the `specialUse` foreground service declaration before publication.

**Important:** don't put `choptorch_model.json` in `.gitignore` or rename its model JSON keys. Use a model exported by this app, not random third-party model weights. A production build must have a valid exported model. If you replace sensor preprocessing or feature extraction later, increment the `featureVersion` and retrain/re-export.

## Android-specific behavior and limits

- The background detector uses the Android 14+ foreground-service type **`specialUse`** with a manifest explanation; Google Play reviews such declarations. It starts only from a visible user action, never silently in the background. Android 13+ notification permission is requested for notification visibility.
- Accelerometer and gyroscope are often non-wake-up sensors. A partial wake lock keeps the CPU active while the screen is off, but OEM-specific energy policies can still stop/limit monitoring. **Continuous 50 Hz monitoring will consume battery.** Use Stop whenever not needed; test several hours of real-world use.
- If your phone aggressively terminates the service, try Android Settings → Apps → ChopTorch → Battery → Unrestricted, where supported. Do not expect operation after a force-stop, reboot, or manual service stop. This app does not bypass the device's power-management rules.
- Some camera APIs or manufacturer customizations may block torch control while the phone is locked; test on your exact model. The app is not a system-level gesture recognizer.
- The saved false-positive vector corresponds to the **classification window that switched the torch ON**, not whatever your phone was doing when you tapped the button. The latest candidate expires after 10 minutes; no feedback is stored in a production build.
- Torch permission is `CAMERA`; no camera image is captured.
- `POST_NOTIFICATIONS` is requested on Android 13+; foreground service still has to exist under Android's notification rules even if the runtime notification permission is denied.
- The model threshold remains 0.82, with the original two-peak gate and 3-second cooldown.

## Changes and key files

- `GestureDetectionService.java`: lifecycle, notification, sensor callbacks, wake lock, false-positive feedback, automatic retraining.
- `GestureDetector.java`: standalone streaming Kalman + feature + NN detector used by the service.
- `GestureStore.java`: labeled dataset, exact triggering-window feedback, JSON export, bundled model load.
- `MainActivity.java`: training UI (dev only), background detector start/stop, feedback and export UI.
- `app/build.gradle`: `-Pshipping=true` compile-time production flag and mandatory pretrained model asset check.
- `.github/workflows/build-apk.yml`: selectable development/production-mode APK build.

## Build/test status

Local tests passed for sensor fusion and synthetic neural-network data. All Java sources also passed a type/syntax compilation check using temporary Android API stubs, in both training and shipping modes. **A full Android APK build and physical-device test have NOT been run in this sandbox** because the Android SDK/Gradle downloads are not available here. Use the included GitHub Actions workflow for a real APK build; share the build log if GitHub reports an error.
