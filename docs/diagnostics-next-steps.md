# Step-by-Step: Enable Closed-Test Diagnostics

This guide records the approved setup for project `chop-torch` as of November 13, 2026. Firestore is in `us-central1`, Anonymous Auth is enabled, and the shipping Android app is registered. Billing remains disabled with no billing account linked. The diagnostics feature stays OFF unless both the shipping and diagnostics Gradle properties are set, and user consent is still OFF by default.

## 1. Verify the Firebase project

1. Sign in to [Firebase Console](https://console.firebase.google.com/) with the Google account that should own ChopTorch diagnostics.
2. Open the approved project `chop-torch` and confirm its project ID in Project settings. Do not create another project for this setup.
3. In Project settings/Billing, verify the project is on Spark and has no billing account linked. Stop if that cannot be confirmed. Do not start a trial or upgrade to Blaze.
4. Check whether Firestore and Authentication are already configured. For an existing project, review its current rules and providers first; do not overwrite unrelated configuration.
5. The Firestore region is already set to `us-central1`; region choice can be irreversible.

**Checkpoint:** The approved project ID is `chop-torch`, visible to `barbarioli1@gmail.com`. Firebase CLI is logged in. Billing inspection reports `billingEnabled: false` and an empty billing account name. Firestore reports `freeTier: true`; no billing account was linked.

## 2. Install JDK 21 for local Rules Emulator tests

The Android app builds with JDK 17, but Firebase CLI 15.36.0 needs JDK 21 to start the Firestore emulator. Eclipse Temurin JDK 21.0.13 is installed under the current user's local Programs directory; it was used only in the rules-test terminal. Keep Android Gradle on its supported JDK 17 unless Android Studio/Gradle is deliberately configured otherwise.

In PowerShell, check the selected runtime:

```powershell
java -version
```

Set `JAVA_HOME` and prepend its `bin` directory in the PowerShell terminal that will run Firebase tests. Use the actual JDK 21 install path on your machine:

```powershell
$env:JAVA_HOME = 'C:\Path\To\JDK-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

Confirm the result says Java 21 or later. Do not place JDK paths or credentials in the repository.

## 3. Run Firestore rules tests locally

From the repository root, install the isolated test dependencies once and run the emulator suite:

```powershell
Set-Location tests/firebase
npm install
npm test
```

The npm script returns to the repository root and runs the Firestore emulator with the non-production `demo-choptorch-diagnostics` project ID. It does not deploy to or write to a real Firebase project. The suite passed all 3 tests on November 13, 2026.

## 4. Set up Firestore and anonymous authentication

In the approved Firebase Console project:

1. The Firestore API is enabled and exactly one `(default)` database exists in `us-central1`, Standard edition, Native mode. The API reports `freeTier: true`; PITR is off. Do not enable billing, PITR, backups, or TTL.
2. Anonymous sign-in is enabled and was verified through the Identity Toolkit API. The project had no Auth config before this setup.
3. Review [firestore.rules](../firestore.rules); the local emulator suite passed all 3 tests.

The database and Anonymous Auth were created/enabled after owner approval. Firestore rules were deployed after emulator validation; released ruleset: `projects/chop-torch/rulesets/c05a10a0-9b86-49ce-8187-3667cb9b483a`.

## 5. Register the Android app and download its real config

1. The shipping Android app is registered with package `com.barbarioli1.choptorch` and Firebase App ID `1:1007493916779:android:8f8a2fad7ea3843104692e`.
2. Its actual `google-services.json` was downloaded to `app/google-services.json` and the package entry was verified. The file is locally ignored by Git. It is client configuration, not a service-account key.
3. Never put service-account credentials in the app or repository.
5. The dev package `com.barbarioli1.choptorch.devclassic` is separate and needs its own registered Android app/config if you intend to run remote diagnostics in dev builds. It is not required for the shipping closed-test build.

## 6. Deploy the reviewed Firestore rules

The reviewed rules passed the local emulator tests and were deployed to the approved project on November 13, 2026. For a future rules update, rerun the emulator tests first, then deploy only the rules:

```powershell
firebase deploy --only firestore:rules --project chop-torch
```

Firebase CLI is already authenticated. The current release is `projects/chop-torch/releases/cloud.firestore`, backed by ruleset `projects/chop-torch/rulesets/c05a10a0-9b86-49ce-8187-3667cb9b483a`.

## 7. Build and verify locally

From the repository root:

```powershell
# Ordinary debug app; remote diagnostics disabled
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# Shipping-style build; remote diagnostics still disabled
.\gradlew.bat :app:assembleDebug -Pshipping=true

# Closed-test compile/package; requires real matching google-services.json
.\gradlew.bat :app:assembleDebug -Pshipping=true -PdiagnosticsEnabled=true

# Closed-test group build; defaults ON when no saved opt-out exists
.\gradlew.bat :app:assembleDebug -Pshipping=true -PdiagnosticsEnabled=true -PtestPhaseDefaultsOn=true
```

The shipping diagnostics-enabled debug APK, Android unit tests, and instrumentation-test compilation passed on November 13, 2026. The build verifies an APK, not a Play-signed release. For a release artifact, configure the existing upload-signing environment variables (`ANDROID_UPLOAD_KEYSTORE_PATH`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_KEY_PASSWORD`) outside the repository and run:

```powershell
.\gradlew.bat :app:assembleRelease -Pshipping=true -PdiagnosticsEnabled=true
```

For a signed closed-test release that defaults monitoring and diagnostics ON when no saved choice exists, add `-PtestPhaseDefaultsOn=true`. This test-only option requires both shipping mode and remote diagnostics; a previously saved opt-out remains OFF.

Never paste signing secrets into chat, source code, shell history, or logs. Do not change the existing app ID or version code.

## 8. Configure App Check and Play disclosures

1. In Play Console, retrieve the **Play app-signing SHA-256** for the uploaded app. Do not substitute the local debug or upload-key fingerprint.
2. Register Play Integrity App Check for the approved Firebase project and link the correct Play Integrity project as required.
3. Keep App Check enforcement OFF while validating legitimate closed-test installs. Enable enforcement only after tester uploads are healthy and the owner explicitly approves the change.
4. Update the Google Play Data Safety form and the hosted English/Portuguese privacy policies before distributing the diagnostics-enabled build. Closed testing still requires accurate disclosures.

These steps require Play Console access and owner review. Do not claim App Check is active until the actual Play-installed app is verified.

## 9. Test on a real closed-test install

1. Install the uploaded, Play-distributed closed-test build on a supported device. Confirm Settings shows “Share anonymous diagnostics” and it starts OFF.
2. With consent OFF, use the app and confirm no diagnostic record is uploaded and no anonymous sign-in is initiated by diagnostics.
3. Turn sharing ON, start gesture monitoring, and exercise a small number of gestures with Wi-Fi connected. Check the UI status and Firebase Console path `diagnosticEvents/{anonymousUid}/events/{eventId}`.
4. Verify the allowed fields, one stable event ID per attempt, and outcomes such as `confirmed_on`, `api_error`, or `confirmation_timeout`. A timeout is inconclusive; a callback confirms Android camera torch state, not emitted light.
5. Repeat with airplane mode, then reconnect. Confirm queued records remain and upload later.
6. Turn sharing OFF. Verify the local queue is cleared and future events stop. A Firestore write already accepted by Google's SDK may still complete; opt-out cannot recall it.
7. Check manual flashlight control, vibration, screen-off monitoring, and battery use. Do not infer detection failure from movements below the 0.50 diagnostics logging threshold.

No Android device is currently attached to this workstation, so these checks remain to be run.

## 10. Monitor and handle deletion requests

Use Firebase Console to review Firestore usage, storage, and event counts. Filter by `kind`, `torchOutcome`, `manufacturer`, `deviceModel`, `screenInteractive`, and `maxScore`. Monitor Spark quotas regularly; retries may add writes. Automatic TTL cleanup is not configured, so the project owner must manually delete old documents when appropriate.

Turning sharing OFF clears only the local queue and stops future collection. It does not delete uploaded documents. For a deletion request, use the Settings email action or contact `barbarioli.1+github@gmail.com`; the owner must locate the anonymous records and delete them in Firebase Console. Record the request and deletion outcome according to the project's privacy process.

## Remaining Checks

- Firestore rules emulator tests passed all 3 tests under JDK 21, and the reviewed rules are deployed.
- No Android device is attached; device instrumentation and connected/offline upload tests have not run.
- Play app-signing SHA-256, App Check setup, and Play Console Data Safety updates require Play Console access and owner action.
- Verify the Firebase plan and monitor usage/quotas in Firebase Console. Billing remains disabled; do not upgrade or start a trial.
