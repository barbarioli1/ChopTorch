# ChopTorch: Shake Flashlight

ChopTorch is an independent Android flashlight app that toggles the rear torch
when it recognizes a double-chop motion. Motion recognition runs locally using
the phone's accelerometer, gyroscope, and a small bundled neural-network model.

The standard production build has no user-facing accounts or ads and does not
collect or transmit diagnostics. A separately enabled closed-test build can
optionally upload anonymous gesture diagnostics only after the tester turns
sharing on in Settings. See the [step-by-step diagnostics guide](docs/diagnostics-next-steps.md).
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

The app has no third-party runtime library dependencies. AndroidX test libraries
are used only by the test configuration; they are licensed under Apache 2.0.

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
Build the development variant for testing. A clean checkout includes an empty
seed JSON so no example data needs to be created or edited before building. In
the dev app, open Training, record real movements, and label them; the app
extracts and saves the feature vectors automatically. Training requires at least
6 positive and 6 negative examples. If no pretrained model is configured,
gesture monitoring becomes available after you train one.

Shipping builds require the pretrained model asset at
`app/src/main/assets/choptorch_model.json`, bundled for offline recognition.

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
