# ChopTorch — double-chop flashlight (Android)

A small **native Android Java** app that learns your double-chop phone gesture and toggles the rear camera's flashlight. All training and prediction happen **locally on the phone**, with no network permissions or cloud model.

## What's included

- **Two pages:** flashlight + detection control, and Record / Confirm / Train.
- **6-axis IMU:** accelerometer and gyroscope at an approximately 50 Hz requested rate (the device chooses the actual rate).
- **Kalman fusion:** separate 2-state Kalman filters (orientation angle and gyroscope bias) for roll and pitch, with accelerometer measurement-noise adjustment during rapid motion. Linear acceleration is estimated by subtracting fused gravity; yaw is *not* corrected by a magnetometer.
- **Features:** 27 normalized statistics (per-axis accelerations and rotations, rotational peak timing, angular integrals, tilts, four time bins).
- **Neural net:** custom lightweight `27 → 18 → 1` MLP trained from scratch using local, labeled examples and supervised backpropagation. No TensorFlow runtime needed.
- **Detection:** needs two separate angular-speed peaks 190–1100 ms apart, a learned score of at least 0.82 for two consecutive windows, and a 3-second cooldown.
- **Camera2:** `CameraManager.setTorchMode` toggles the flashlight, with Android camera permission requested at runtime.
- **Persistence:** only labeled 27-number feature vectors and trained MLP weights are stored in private app preferences; raw time-series data stays in memory until each recording is labeled, then is discarded.

## Build and install

### Easiest way to get an APK: GitHub Actions

The project includes `.github/workflows/build-apk.yml`, which installs Java, Gradle,
Android SDK and compiles a **debug APK** on a GitHub-hosted build runner. To use it:

1. Create a GitHub repository and upload **all the project files**, including the
   hidden `.github` directory and its `workflows/build-apk.yml` file.
2. Go to the repository's **Actions** tab, select **Build Android APK**, then
   select **Run workflow**. Pushing to `main` or `master` also triggers a build.
3. Open the completed workflow run; download the **ChopTorch-debug-apk** artifact.
4. Unzip that artifact and install `app-debug.apk` on your Android phone. Android
   may ask you to allow installs from the app you used to open the APK.

**Note:** This workflow is supplied but has **not actually run in this sandbox**.
A GitHub account/repository and an internet-connected GitHub Actions runner are required.

### Build from Android Studio

1. Install Android Studio, Android SDK API 36 and JDK 17 or newer.
2. Extract the ChopTorch ZIP, then open the extracted project in Android Studio.
3. If prompted to obtain the missing Gradle wrapper JAR, run the bundled `gradlew`
   script once in a terminal, or install **Gradle 8.13** locally and use
   `gradle assembleDebug` to build without the wrapper JAR.
4. Allow Gradle sync to download the Android Gradle Plugin 8.13.2 and other required files.
5. Connect an Android phone with USB debugging enabled and press **Run**, or use
   **Build > Build APK(s)**. The resulting APK is in `app/build/outputs/apk/debug/`.

The wrapper scripts download the official Gradle 8.13 wrapper JAR over HTTPS,
verify the published SHA-256, then use Gradle normally. The initial Gradle
distribution download and Android dependencies also require internet access.

**Compatibility:** API 26+ (Android 8.0+), physical accelerometer + gyroscope +
camera flash. A camera without flash can collect training examples but cannot
control a torch. Targets Android SDK 35, compiles with SDK 36; Android Gradle
Plugin 8.13.2 / Gradle 8.13.

## Train the double-chop gesture

1. Open **Open training page**, tap **Record a 2-second movement**.
2. Wait for **MOVE NOW** (850 ms preparation time); firmly hold **just your phone** and make **two brisk chopping movements** during the 1.9-second capture.
3. Tap **Yes — two chops** to label the sample positive. Repeat at least **6** times, preferably 15–25, varying the angle and speed.
4. Record **other movements** such as shaking once, lifting the phone, walking or an accidental jolt, then tap **No — another movement** to label the sample negative. Do at least **6**, preferably 15–25.
5. Tap **Train neural network**. The model is saved automatically; the displayed accuracy is **training-set fit only**, *not* proof of real-world accuracy.
6. Return to the home page and tap **Start listening**. Make two chops to **toggle** the flashlight. Add examples of incorrectly detected or missed movements and retrain.

**Important:** Do not hold or swing any actual knife. Hold the phone securely away from people and objects.

## Important limitations

- **Foreground only.** Motion monitoring stops when the app is paused or the phone is locked. A background/lock-screen version would need a dedicated foreground-service design and device-specific power-management testing.
- **No suitable pre-trained double-chop dataset was verified.** Public smartphone IMU datasets [for drawing digits](https://github.com/computersniper/Android-IMU-Digits-Gesture-Dataset) and [for drawing letters](https://huggingface.co/datasets/ravenwing/cheedeh-IMU-data) are not correctly labeled for this motion. The app trains on your actual recordings instead.
- **Early prototype.** Thresholds and time window are starting values; test in ordinary situations to avoid accidental flashlight activation. Gesture performance will depend on your phone's sensors, grip, speed and how diverse your negative examples are.
- **No APK was produced in the sandbox.** Java unit smoke tests and a source compile against temporary API stubs passed, but this environment lacks Android SDK/Gradle and cannot download them. This is **not an Android SDK build verification**. To obtain an APK, use the included GitHub Actions workflow or Android Studio.

## Testing the sensor fusion and neural network without Android

The pure Java core is covered by a synthetic smoke test (it does **not** validate real-world gesture accuracy). On macOS/Linux with a JDK installed:

```sh
./tests/run-core-tests.sh
```

Expected: `PASS: 27 gesture features ...` with distinct predicted probabilities for a synthetic double chop and a single-chop negative.

## Source map

- `MainActivity.java` — two pages, recording confirmation, live detection, permissions.
- `MotionProcessor.java`, `AngleKalman.java` — synchronized IMU samples and roll/pitch Kalman filtering.
- `GestureFeatures.java` — fixed-length feature generation and two-peak prefilter.
- `TinyNeuralNet.java` — on-device MLP training and inference.
- `GestureStore.java` — private saved labels and model parameters.
- `TorchController.java` — Camera2 flashlight control.

## References

- [Motorola's chopping gesture](https://en-us.support.motorola.com/app/answers/detail/a_id/190308/~/gestures)
- [Android CameraManager.setTorchMode](https://developer.android.com/reference/android/hardware/camera2/CameraManager#setTorchMode(java.lang.String,%20boolean))
- [Android motion sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Gradle 8.13 wrapper checksum](https://gradle.org/release-checksums/)
