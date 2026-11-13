# Opt-in Diagnostics Deployment

## Current status

The client feature is compile-time disabled unless built with `-Pshipping=true -PdiagnosticsEnabled=true`. The ordinary debug build remains diagnostics-off. The shipping app has a real, package-matched Firebase config at `app/google-services.json` (ignored by Git). The diagnostics-enabled debug APK, Android unit tests, and instrumentation-test compilation passed on November 13, 2026.

Firebase CLI is authenticated as `barbarioli1@gmail.com` for the approved project `chop-torch`. Firestore `(default)` is Standard Native in `us-central1`; Anonymous Auth is enabled and verified. The reviewed Firestore rules were emulator-tested and deployed. Billing is disabled, with no billing account linked. See [diagnostics-cloud-setup-report.md](diagnostics-cloud-setup-report.md).

## Remaining Setup and Verification

1. Configure the Play-signed release environment and build with `-Pshipping=true -PdiagnosticsEnabled=true`. The passing local check so far is a debug APK, not a Play-signed release.
2. Verify the Google Play app-signing SHA-256 and configure Play Integrity App Check. Keep enforcement off until valid Play-installed tester uploads are confirmed.
3. Update the Google Play Data Safety declaration and hosted privacy policy before distribution.

## Build and tester workflow

Default local verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Shipping build with diagnostics disabled:

```powershell
.\gradlew.bat :app:assembleDebug -Pshipping=true
```

Diagnostics-enabled build with the actual matching Firebase client configuration:

```powershell
.\gradlew.bat :app:assembleDebug -Pshipping=true -PdiagnosticsEnabled=true
```

The diagnostics-enabled debug build passed. A release build still requires upload-signing secrets configured outside the repository. The Settings control is absent when the compile-time flag is false. When present, consent begins OFF. Turning sharing off cancels scheduled uploads, disables Firestore networking, and clears the local SQLite queue. A Firestore write already accepted by Google's SDK before opt-out may still complete. Previously uploaded records require a deletion request to the privacy contact; local opt-out cannot remove server records.

## Data inspection and cleanup

Events are stored at `diagnosticEvents/{anonymousUid}/events/{eventId}`. In Firebase Console, filter by `kind`, `torchOutcome`, `manufacturer`, `deviceModel`, `screenInteractive`, and `maxScore`. Treat scores from 0.50 to below 0.82 as diagnostic candidates, not detector failures. A `confirmation_timeout` is inconclusive; `confirmed_on` confirms Android's torch-state callback, not emitted light. Never infer a missed gesture from absent events below the 0.50 logging threshold.

Use the Firebase Console to inspect current Firestore usage and manually delete old diagnostic documents when appropriate. Automatic TTL deletion is not configured. Monitor free quotas and storage; retries can consume additional writes. A deletion request can be sent from the Settings screen or to the published privacy contact. App Check registration/enforcement, Play Console Data Safety, a real upload round trip, offline/reconnect behavior, and device checks remain external verification steps.

## Limitations

The upload worker waits for completion of the Firestore SDK write task, but SDK/offline behavior and server acknowledgment have not been verified against a configured project or device. Queue records are deleted only after that task reports success. No claim is made that accepted SDK writes can be recalled after opt-out. Emulator rules tests do not establish Firebase project configuration or production security behavior.
