# ChopTorch: Shake Flashlight

ChopTorch is an independent Android flashlight app that toggles the rear torch
when it recognizes a double-chop motion. Motion recognition runs locally using
the phone's accelerometer, gyroscope, and a small bundled neural-network model.

The production app has no user accounts, ads, or app-to-server data transmission.
Read the [privacy policy in English](docs/privacy.html) or
[Português (Brasil)](docs/privacy-pt.html). The source code is licensed under
the [MIT License](LICENSE).

## Features

- Toggle the flashlight with a double-chop gesture.
- Run gesture recognition offline on the device.
- Start monitoring from the app and stop it from the persistent Android
   notification.
- Use optional vibration feedback when flashlight state changes.
- Install the shipping app separately from the development app.

Background operation is not guaranteed. Android, battery settings, and
device-specific policies may interrupt monitoring. Continuous sensor monitoring
uses battery power.

## Requirements

- Android 8.0 (API 26) or later.
- A rear camera flash, accelerometer, and gyroscope for the primary feature.
- Android SDK 36 and JDK 17 to build with the repository's current configuration.

The shipping application ID is `com.barbarioli1.choptorch`. Development builds
use `com.example.choptorch.devclassic` and have optional training features.

## Offline recognition and local data

Accelerometer and gyroscope readings are used only while monitoring or recording
training examples in a development build. Production recognition runs locally;
the shipping build disables personal training, false-positive feedback, and
model/example export. It stores the vibration preference in app-private storage
and includes the pretrained model in the app package. Operational timing and
flashlight-state messages may be written to Android's local system log; raw
sensor samples are not logged or transmitted by the production build.

Development builds may save gesture features, examples, and trained weights in
app-private storage. The training UI can export examples or a model and erase
training data. Clearing ChopTorch storage in Android Settings or uninstalling it
removes the app's local private data. Android backup and device-transfer backup
are disabled for the app's private data.

## Permissions

- `CAMERA`: control the flashlight through Android's camera torch API; the app
   does not take photos or video.
- `VIBRATE`: optional flashlight-state feedback.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: user-started
   continuous gesture monitoring while the app is not visible.
- `POST_NOTIFICATIONS`: show the ongoing monitoring notification on Android
   versions that require notification permission.
- `WAKE_LOCK`: keep the CPU available while monitoring non-wake-up sensors.
- Accelerometer and gyroscope access is provided by Android sensor APIs and does
   not use a runtime permission prompt.

## Build and test

Open the repository root in Android Studio, use JDK 17, and sync with Gradle.
Build the development variant for testing. A local development or shipping build
needs the private model asset at `app/src/main/assets/choptorch_model.json`;
development builds may also use `app/trainingAssets/choptorch_seed_examples.json`.
These model and example files are intentionally excluded from the public Git
repository and must not be added to commits.

The GitHub Actions **Build Android APK** workflow restores these files from the
private Actions secrets `CHOPTORCH_MODEL_JSON` and
`CHOPTORCH_SEED_EXAMPLES_JSON`. APK artifacts are uploaded only for private
repositories. The separate Play publishing workflow requires upload-signing
secrets and Google Play Workload Identity Federation configuration.

Run the core tests from a Bash shell:

```sh
bash tests/run-core-tests.sh
```

The Play upload package is `com.barbarioli1.choptorch`; the development package
is separate and does not share app data. Do not change the shipping application
ID, signing key, or version code for an existing Play app without checking the
consequences first.

## Google Play materials

- [Privacy policy](docs/privacy.html)
- [Google Play submission notes](docs/google-play-submission.md)
- [English and Brazilian Portuguese store listing](docs/store-listing.md)
- [512 × 512 store icon](docs/choptorch-google-play-icon-512.png)

GitHub Pages is prepared to publish from branch `main`, directory `/docs`. The
expected privacy URL is
`https://barbarioli1.github.io/ChopTorch/privacy.html`; it is not live until
Pages is enabled and the URL is tested after deployment.

## Contributing

Use GitHub Issues to report bugs or propose changes. Please do not attach private
motion recordings, model/example data, keystores, passwords, tokens, or other
credentials to issues or pull requests. Test gesture behavior on real hardware;
sensor delivery, flashlight hardware, and background policies vary by device.

ChopTorch is an independent project and is not affiliated with Motorola.
