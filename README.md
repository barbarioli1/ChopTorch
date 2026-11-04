# ChopTorch v2.2 — original v2 detector, 1.6-second cooldown

This update **reverses the v2.1 detection experiments** and restores the v2
recognition behavior, changing **only the 3,000 ms cooldown to 1,600 ms**.

## Detector settings (restored)

- **1,900 ms** sensor feature window; original **27 features**, Kalman fusion,
  trained model and **0.82** probability threshold unchanged.
- Predictions **every 160 ms**, requiring **two consecutive positive checks**.
- **1,600 ms** cooldown between accepted detections (formerly 3,000 ms).
- The original v2 sensor callback registration is restored, with no dedicated
  `HandlerThread` and no freshness/peak-event gating from v2.1.

The **recovered-example duplicate fix** from v2.1 is intentionally retained:
first-launch restoration loads the 19 original gestures once (7 positive, 12
negative) and can repair an exact duplicated seed from v2 without deleting any
additional recordings. The original pretrained weights are still bundled.
Background monitoring, false-positive feedback, training, model export, and
shipping mode are retained.

## Building via GitHub Actions

1. Replace your GitHub repository files with the contents of the `ChopTorch`
   folder, including `.github/workflows/build-apk.yml`.
2. In **Actions → Build Android APK → Run workflow**, leave `shipping` unchecked.
3. Download the generated `ChopTorch-debug-apk` artifact; extract and install
   `app-debug.apk`.
4. Start monitoring and test two chops. Android sensor delivery, camera torch
   hardware, and battery policies still affect real-world timing.

**Installation safety:** Development builds use the distinct application ID
`com.example.choptorch.devclassic`. This means the test APK installs **alongside**
v1 (`com.example.choptorch`), v2 (`.dev`), and v2.1 (`.devfast`) rather than
risking their saved model/data with a new GitHub debug signing certificate.
On a new v2.2 install, the 19 recovered examples and original v1-trained model
are preloaded. Extra feedback/training you recorded in the other builds is
**not** automatically imported. Do not uninstall the old builds until you have
exported anything you want to keep.

## Shipping mode

Export any newly trained model from the development app and replace
`app/src/main/assets/choptorch_model.json` in the source. Run the workflow with
`shipping` checked (or `gradle assembleDebug -Pshipping=true`) to disable
training, feedback, and export; only the trained weights are shipped. Shipping
uses `com.example.choptorch` and may conflict with installed v1, so back up
its data and use a stable release signing key before distributing the app.

## Checks

Run `bash tests/run-core-tests.sh`. These tests cover the original synthetic
feature extraction/neural network and recovery deduplication; the detector's
source has been compared against v2 and differs in the cooldown number only.
The Android APK itself and real-device recognition have not been tested here.
