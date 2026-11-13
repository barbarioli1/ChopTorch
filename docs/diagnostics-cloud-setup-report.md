# Firebase Cloud Setup Report

Cloud setup was performed in the explicitly approved Firebase project `chop-torch` on November 13, 2026. Billing remains disabled and no billing account is linked. No GitHub resources were changed.

## Inspected

- Repository: `barbarioli1/ChopTorch`; branch: `feature/opt-in-firebase-diagnostics`.
- GitHub CLI identity: `barbarioli1` (read-only repo discovery).
- Active gcloud/Firebase account: `barbarioli1@gmail.com`.
- Approved diagnostics project: `chop-torch`; project lifecycle is ACTIVE.
- `firestore.googleapis.com` is enabled in `chop-torch` following explicit user approval.
- Billing inspection reports `billingEnabled: false` and an empty billing account name.
- Firebase CLI: version 15.36.0; authenticated as `barbarioli1@gmail.com`.
- Android package: `com.barbarioli1.choptorch`; App ID: `1:1007493916779:android:8f8a2fad7ea3843104692e`.
- The downloaded Android client config package entry matches the shipping package. It is ignored locally by Git and is not a service-account credential.

## Created

- Firestore `(default)` database created in `us-central1`, Standard edition, Native mode. Cloud response reports `freeTier: true`; PITR is disabled.
- Android app `ChopTorch` registered for package `com.barbarioli1.choptorch`.
- Real Android client config downloaded to `app/google-services.json`; `.gitignore` now excludes it.
- Anonymous Auth enabled through Firebase CLI and verified by a read-only Identity Toolkit config request. The project had no Auth config before setup.
- `firebase.json` includes Anonymous Auth and retains its existing Firestore rules path.

## Deployed

- Anonymous Auth configuration only, deployed with `firebase deploy --only auth --project chop-torch`.
- Firestore rules were deployed after emulator validation; released ruleset is `projects/chop-torch/rulesets/c05a10a0-9b86-49ce-8187-3667cb9b483a`.

## Tested

- Closed-test default-on diagnostics build passed: `./gradlew.bat :app:assembleDebug -Pshipping=true -PdiagnosticsEnabled=true -PtestPhaseDefaultsOn=true`.
- Android unit tests and instrumentation-test compilation passed: `./gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac`.
- Firestore emulator suite passed all 3 tests under Temurin JDK 21.0.13 using the non-production demo project ID.
- No Android device is attached; instrumentation and end-to-end upload tests have not run.

## Unknown / blocked

- Firebase plan name has not been independently confirmed in Firebase Console. Cloud reports Firestore free tier available; billing is disabled.
- No Firestore rule deployment issue remains; the active release is `projects/chop-torch/releases/cloud.firestore`.
- Play app-signing SHA-256, Play Integrity project linkage and App Check enforcement: UNKNOWN.
- Real-device connected/offline upload behavior: BLOCKED by missing attached device and device validation.

## Needs human approval

Obtain the actual Play app-signing SHA-256 from Play Console before App Check registration. Update Play Console Data Safety disclosures and privacy-policy hosting before distributing a diagnostics-enabled build. Complete real-device consent, upload, offline/reconnect, and opt-out checks. No billing was enabled; no push or pull request was made.
