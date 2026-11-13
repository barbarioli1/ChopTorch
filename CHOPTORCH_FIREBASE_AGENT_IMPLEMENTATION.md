# ChopTorch — STRICT VS Code Agent Implementation Specification

**Purpose:** Implement *opt-in, automatic, background-uploaded gesture/flashlight diagnostics* for the existing ChopTorch Java Android app using Firebase Authentication (anonymous) and Cloud Firestore **on Firebase Spark (no billing required)**. The development machine has authenticated `gcloud` and `gh` CLIs available; use them for read-only discovery and permitted setup, with Firebase CLI where required.

**Audience:** A coding agent that should follow precise instructions instead of redesigning the application.

**Source reviewed:** `ChopTorch (5).zip`, November 2026. **The agent must re-read the files in its own workspace and stop if their code materially differs from these descriptions.** This is an implementation instruction, not a claim that code has already been changed.

---

## 0. AGENT OPERATING CONTRACT — MANDATORY

1. Implement **only** the telemetry feature in this document. Do **not** refactor UI architecture, sensor pipeline, neural network, camera lifecycle, model weights, training, publishing workflows, or the foreground-service lifecycle.
2. **Never modify** `GestureDetector.THRESHOLD` (currently `0.82`), `GestureDetector.WINDOW_MS` (`1900` ms), the 160 ms prediction cadence, 2-hit requirement, 1600 ms activation cooldown, or model feature extraction/math. `0.50` is a **logging threshold only**; it is NOT a new activation threshold.
3. Do not upload raw accelerometer/gyroscope streams, addresses, GPS, contact information, email, hardware serials, advertising IDs, Android ID, or camera content. The default diagnostic event includes **at most one 27-element feature vector per movement attempt**, and the additional metadata specified below.
4. **Consent is mandatory.** No network sign-in, telemetry collection, or upload until the tester explicitly enables a clear "Share anonymous diagnostics" control. Default is OFF. The control must have an OFF action; turning it OFF must immediately stop event collection and cancel queued work; erase locally queued events. Explain how the tester can request deletion of previously uploaded data.
5. Diagnostics must be **compile-time disabled by default**, even for production shipping builds, and enabled only by explicit Gradle property `-PdiagnosticsEnabled=true`. Keep all existing build identifiers. Closed testers will receive a **shipping-style closed-test build** compiled with this property, still with user opt-in OFF by default.
6. Work must run off the sensor callback and main/UI thread. **Do not** do network I/O, disk I/O, JSON serialization, blocking waits, or Firebase SDK calls inside `onSensorChanged` or the torch callback. A small in-memory handoff to a bounded executor/queue is allowed.
7. Implement a **bounded, durable local queue** and constrained WorkManager uploads. Do not rely on sending every model prediction to Firestore; aggregate adjacent qualifying predictions into one attempt record. Every event must have a stable UUID so retries cannot create duplicate documents.
8. Preserve continuous monitoring and the existing screen-off behavior. Upload must not start a new foreground service, keep a wake lock, interfere with torch callbacks, or invoke `GestureDetector` from a second thread.
9. Do not introduce Firebase Analytics, Crashlytics, Cloud Functions, BigQuery, paid-tier products, a custom server, an admin credential, or an API key pasted into Java source.
10. Do not use Firestore `allow read, write: if true`, do not embed a Firebase service-account JSON/private key in the app, and never give clients admin access or cross-user reads.
11. Do not claim the feature has been tested without building and running the relevant tests. If an Android SDK, Firebase config, or real device is unavailable, report `BLOCKED` with the exact reason; do not fake a green test.
12. The deliverable is a **git diff and test report** in the VS Code workspace, not just prose. Don't change things outside the explicit permitted files unless build/runtime verification proves it essential; document every exception.
13. You have access to `gcloud` and `gh` CLIs. You MUST use these for read-only discovery, but NEVER assume `firebase` is installed or logged in. Install/login Firebase CLI only with user permission if necessary. Never silently select a cloud project from the default active account.
14. Cloud and GitHub safety: read-only operations are allowed; get user approval before **creating cloud projects/databases/apps, enabling cloud APIs, deploying or replacing rules/auth configuration, pushing commits, creating pull requests, enabling billing, or changing App Check enforcement**. No `gh repo delete`, `gcloud projects delete`, deletion of databases, force pushes, or authentication credential changes. Never enable billing. If approval is not available, finish all independent local code and provide a precise pending command checklist.
15. Never print access tokens, login secrets, service account material, signing keystore passwords, or full credential JSON to logs, commits or chat. Firebase Android `google-services.json` is client config rather than a service-account secret, but still inspect Git policy before committing. Never use `gcloud auth print-access-token` in logs.

## 0A. CLI-FIRST CLOUD & GITHUB SETUP (REPLACES MANUAL-ONLY SETUP WHERE POSSIBLE)

**Use this sequence before Phase E.** The presence of `gcloud` and `gh` credentials does **not** prove the correct Google account, project, repo, permissions, or billing tier. Commands below contain placeholders: replace only with IDs discovered from CLI output or explicitly approved by the human; do not paste placeholder strings into executable project files. A Firebase project is a Google Cloud project with Firebase added.

### A. Read-only capability and identity discovery (safe to run)

```bash
pwd
git rev-parse --show-toplevel
git status --short
git remote -v
gh auth status
gh repo view --json nameWithOwner,url,defaultBranchRef
command -v gcloud && gcloud --version
command -v firebase && firebase --version || true
command -v gh && gh --version
gcloud auth list
gcloud config list
gcloud projects list --format='table(projectId,name,lifecycleState)'
# Only if firebase CLI is installed and authenticated:
firebase login:list
firebase projects:list
```

Do not include raw tokens in the report. If a GitHub remote/repo is absent, do **not** create a GitHub repository. Read-only `gh` commands must not change the remote. Prefer using an existing Firebase project dedicated to diagnostics. Confirm the user-approved Google Cloud **project ID**, account, GitHub repo, and whether the project has any linked billing account. DO NOT use `gcloud config set project` as a substitute for project review; pass `--project="$PROJECT_ID"` explicitly to all commands that support it.

### B. Select / create the Firebase project (approval required for mutation)

1. If an appropriate Firebase project already exists, reuse it **after verifying its project ID and free-plan status**. Do not overwrite existing Firestore rules or Auth providers before comparing current configuration.
2. Otherwise propose a unique project ID (lowercase/hyphens, verify validity) and ask approval to create **one** dedicated project. The Firebase CLI supports `firebase projects:create PROJECT_ID --display-name "ChopTorch Diagnostics"`, or `firebase projects:addfirebase PROJECT_ID` for an existing *approved* GCP project. Do not run both. Firebase project creation is a real cloud mutation.
3. Ensure no billing account is linked and the Firebase project is on **Spark**; if billing is linked or its plan cannot be established, STOP cloud creation/deployment and ask the human to verify in Firebase Console. **Never** issue `gcloud billing projects link` or upgrade to Blaze, and never opt into the $300 trial.
4. Choose one **Standard edition, Firestore Native-mode `(default)` database**. Region selection affects data residency and may be irreversible; do not assume the user's location means a region is approved. Get the human's region approval before creation. CLI references:

```bash
# Read-only inspection, replacing approved PROJECT_ID:
gcloud firestore databases list --project="$PROJECT_ID"
firebase firestore:locations --project "$PROJECT_ID"
# Mutating command; only with explicit approval, only if no existing database:
gcloud firestore databases create --project="$PROJECT_ID" \
  --database='(default)' --location="$APPROVED_LOCATION" \
  --edition=standard --type=firestore-native
```

Do not create extra databases; Firestore grants free quota to only one database per project. Do not enable PITR, TTL deletes, backups, restore, or clone features.

### C. Register the *actual* Android package, retrieve configuration

The reviewed source uses production package `com.barbarioli1.choptorch`; the dev package has `.devclassic`. After project approval, inspect registration with `firebase apps:list --project "$PROJECT_ID"`. If missing, and after approval, create the Android app using the **syntax printed by the installed CLI's** `firebase apps:create --help`; e.g. `firebase apps:create ANDROID --package-name=com.barbarioli1.choptorch --display-name="ChopTorch" --project "$PROJECT_ID"`. Do not invent a Firebase App ID: copy it from the create/list response. Then use `firebase apps:sdkconfig ANDROID "$FIREBASE_APP_ID" --project "$PROJECT_ID"` with the CLI-supported output flag to save its JSON to `app/google-services.json`. Do not copy a web-app config into Android. Confirm JSON `client[].client_info.android_client_info.package_name` matches exactly. If also building `.devclassic`, register it separately, fetch config, and merge via official supported multi-client configuration or build-variant resources; do not replace prod config with dev config.

Check that `app/google-services.json` has no `private_key`, `client_email`, or `type=service_account`. Handle this file according to the repository's agreed client-config policy; it is **not** a service-account key but can identify the project. Never commit downloaded admin keys.

### D. Auth configuration through Firebase CLI (approval required to deploy)

The current official Firebase CLI supports Auth-as-code. First inspect `firebase.json` and Firebase Console state. For this **dedicated** project, the relevant fragment is:

```json
{
  "auth": {
    "providers": {
      "anonymous": true
    }
  }
}
```

Use `firebase init auth` only after understanding whether it overwrites existing `firebase.json`, and merge carefully. `firebase deploy --only auth --project "$PROJECT_ID"` changes remote Auth providers and requires approval; do not disable other existing providers accidentally. CLI feature availability depends on installed version. If unavailable, explicitly report that one console step remains; never use undocumented GCP endpoints or OAuth token hacks.

### E. Firestore rules: preserve and verify before deployment

1. If a project already has rules, fetch/inspect current rules and do not replace unrelated paths. Prefer a dedicated project.
2. Check `firebase.json` references `firestore.rules` for database `(default)`. Use the Firebase emulator/rules unit tests for ownership, create, duplicate identical retry, deny update with changed content, deny read/delete, unknown field and unrelated paths.
3. Perform a **dry review of the actual diff**. After user approval only: `firebase deploy --only firestore:rules --project "$PROJECT_ID"` (verify selector support in installed CLI). Never use public test-mode rules even temporarily.
4. Do not deploy a rules file before Firestore is provisioned and authenticated write tests are designed. Record which project ID was targeted.

### F. App Check and Google Play credentials

`gcloud` and `gh` do **not** substitute for Google Play Console permissions. To register Play Integrity App Check correctly, obtain the **Play app-signing SHA-256** from Play Console, not from a local debug key; link the same cloud project in Play Integrity API settings. The agent may register SHA fingerprints with `firebase apps:android:sha:create` **after confirming exact IDs and approval**, but App Check registration, Play Integrity project linking and enforcement may still require Console interaction. Keep enforcement OFF until measured legitimate Play-installed closed testers can upload successfully. Never assume CLI access equals Play Console access.

### G. GitHub CLI: local branch and explicit permission boundary

```bash
git status --short
git switch -c feature/opt-in-firebase-diagnostics  # only if safe and branch absent
# ... implement + test ...
git diff --check
git diff --stat
git status --short
gh repo view --json nameWithOwner,url
```

Do not stage generated admin credentials, local keystores, access tokens, or unrelated files. **Do not push** (`git push`), open a PR (`gh pr create`), edit secrets (`gh secret set`), trigger releases (`gh release create`), modify workflows, or merge without explicit permission. After permission, commit only reviewed scoped files and optionally use `gh pr create` with a full test/deployment checklist. Do not claim that a PR is merged or that a Play release is published.

### H. Cloud setup evidence and stopping behavior

Create `docs/diagnostics-cloud-setup-report.md` containing **nonsecret** command names actually run; chosen project ID, region, Firebase Android app IDs, linked database ID, Spark/billing status (verified or UNKNOWN), Auth provider status, rule deployment/version status, App Check status, and any blocked prerequisite. Separate `INSPECTED`, `CREATED`, `DEPLOYED`, `TESTED`, and `NEEDS HUMAN APPROVAL`. Never mark an item complete based only on a local file. If CLI commands differ from examples, run `--help`, check current official docs, and use the installed CLI's supported flags; do not invent flags or commands.

---

## 1. FACTS ABOUT THE EXISTING PROJECT (VERIFY THESE FIRST)

- Project Gradle root `build.gradle` uses Android Gradle Plugin `8.13.2`.
- `app/build.gradle` is **Groovy**, applies `com.android.application`, compiles Java 17, `compileSdk 36`, `minSdk 26`, `targetSdk 36`.
- `defaultConfig.applicationId = 'com.barbarioli1.choptorch'` and non-shipping builds add suffix `.devclassic`.
- `-Pshipping=true` sets `BuildConfig.ENABLE_TRAINING=false` and validates `app/src/main/assets/choptorch_model.json`. No Firebase dependencies exist in the reviewed ZIP.
- `app/src/main/AndroidManifest.xml` has CAMERA, WAKE_LOCK, foreground service permissions, but **does not have `INTERNET`**.
- `GestureDetector.java`: `onAccelerometer(...)` computes a neural-network score, tests `hasDoubleChopShape && score >= 0.82`, tracks `consecutiveHits`, and returns `Detection` only after 2 hits and cooldown; it currently exposes `latestScore()`, `lastPredictionTimeMs()` and `lastPositiveMatchTimeMs()`.
- `GestureFeatures.Result` already exposes `values` (27 doubles), `strongPeaks`, `peakGapMs`, `maxGyro`, and `hasDoubleChopShape`.
- `GestureDetectionService.java`: `onSensorChanged(...)` drives `GestureDetector`; on a match it invokes `torch.toggle()` and logs exceptions; `startRecovery(...)` resets detector history after a sensor delivery gap. It creates `TorchController` with a no-op `onTorchState` listener.
- `TorchController.java`: existing `CameraManager.TorchCallback` receives `onTorchModeChanged(id, enabled)` and `onTorchModeUnavailable(id)` on the main looper. `toggle()` returns after `setTorchMode(...)` and optimistically sets internal `on`; **`isOn()` is not proof of a confirmed hardware state**.
- `MainActivity.java`: builds Settings UI in Java in `showSettings()`, and About/Privacy in `showAbout()`. There is no XML settings screen.
- Both `app/src/main/assets/privacy.html` and `privacy-pt.html` currently say sensor information stays local and isn't transmitted. Update them (accurately) before shipping the new build.
- Existing pure-Java tests live under `tests/`; an instrumentation test dependency block already exists in `app/build.gradle`.

**Step 1 agent action:** Read all files named above; run `git status --short` and record original status. Preserve user changes. Do not overwrite secrets, model assets or signing files.

## 2. ARCHITECTURE — IMPLEMENT THIS, NOT AN ALTERNATIVE

```text
existing accelerometer stream -> GestureDetector (same thread, same decision logic)
                                          |
                          lightweight read-only prediction observer
                                          |
                              AttemptAggregator (in memory)
                                          |
                        DiagnosticsRecorder (bounded executor)
                                          |
                         SQLite diagnostics_queue (app-private)
                                          |
                   WorkManager with CONNECTED network constraint
                                          |
         Firebase anonymous authentication -> Cloud Firestore SDK
                                          |
              diagnosticEvents/{anonymousUid}/events/{eventId}
```

- The **observer** reports a completed prediction without influencing detector returns, score, state, or timing. It must be nullable and exception-safe. For each prediction provide score, cloned 27-vector, hasDoubleChopShape, strongPeaks, peakGapMs, maxGyro, `pass`, consecutive-hit count, whether trigger occurred, and monotonic prediction time. Never recalculate model output in another component.
- `AttemptAggregator` groups neighboring scores >= `0.50` into attempts; use **800 ms** of no new qualifying score as a split, and cap the length of a single attempt at **4 seconds**. Finalize on trigger or on timeout. Aggregate to exactly one event with the **highest-score snapshot** (its 27 features + shape details), `maxScore`, `predictionCount`, `start/end monotonic times`, `triggered`, and resolution. A low score ends/no-op for the qualifying candidate; do not create a record for every below-50% prediction.
- **Do not lose a final attempt simply because there are no more sensor callbacks.** Flush pending attempts when monitoring stops, recovery starts, or after a delayed timeout scheduled on the diagnostics executor. Scheduling must not block sensor delivery. Ensure thread safety.
- Allow **important events regardless of 50%**: sensor recovery/interruption, start failure, torch API exception/unavailable, user-submitted missed gesture; do not confuse these with a network score. Use event `kind` described in schema.
- Torch outcomes need **correlation to a specific request**. On gesture trigger, store an `eventId` and requested desired state (ON/OFF) before calling the existing toggle. On `CameraAccessException`/`RuntimeException`, mark `api_error`. On successful return mark `api_returned_pending` until matching camera callback. On callback matching request within **2 seconds**, `confirmed_on` or `confirmed_off`. If unavailable callback arrives, mark `unavailable`. If no matching callback after 2 seconds, mark `confirmation_timeout`, **not** confirmed. Do not equate `torch.isOn()` with confirmation. The torch listener currently reports state but not unavailability; extend **observability only** while preserving behavior, or register a separate safe observer. Do not change vibration, torch logic, or external quick-settings synchronization.
- For the issue "torch doesn't turn on," focus diagnostics on requests where `requestedOn=true`; OFF toggles can be recorded too, but label them distinctly. Do not call successful OFF an ON failure.
- Events are enqueued only if `BuildConfig.ENABLE_REMOTE_DIAGNOSTICS && consent == true`. After opt-out, callbacks already in flight MUST NOT add more events: re-check consent/feature flag on the disk-writing executor and immediately before upload. For consent races, use a synchronized generation token; invalidate it on opt-out.
- The local queue is the **source of truth** until server confirmation. Use `SQLiteOpenHelper`, not a global in-memory list. Cap at **500 events** and **2 MiB total serialized payload**; when full, drop the oldest **non-error** diagnostic first, then oldest remaining if absolutely required. Keep a counter `dropped_local_event_count`. Do not upload that counter as a personal identifier.
- Use JSON text in SQLite with stable `eventId`; no need for Room or protobuf. Each event should usually be under 4 KB. Reject non-finite numbers before serialization; omit invalid vectors rather than crashing.
- **Uploads:** one unique `OneTimeWorkRequest` named `choptorch-diagnostics-upload`, `ExistingWorkPolicy.KEEP`, network constraint `CONNECTED`, exponential backoff. Trigger after appending events (debounced; do not enqueue on each sensor sample), and when app starts or monitoring starts if opted in. Also a unique **15-minute** periodic work for eventual retries if useful and supported. The worker obtains or reuses anonymous Firebase auth, sends at most **20 events per run**, one document per attempt, then removes a local event only when its matching Firestore write task succeeds **at the server**. Use a finite wait and return `Result.retry()` on timeout; never block indefinitely. With Firestore offline persistence, beware a local-cache write completion being misread as a server acknowledgment—confirm semantics against SDK documentation; if you cannot guarantee remote acknowledgment, retain until explicit confirmed server receipt or use a documented server-acknowledgment strategy. The deterministic doc ID makes retries idempotent at the data level, though they can still cost extra writes.
- If `PERMISSION_DENIED` or another permanent configuration error occurs, keep events locally, persist a visible `lastUploadError`, and do not flood retries; use backoff. If auth isn't ready, retry. Never delete data on network failure. On opted-out status, cancel workers and clear local queue.
- Disable Firebase network use when diagnostics are off. No sign-in on app startup until the user opts in. Cancel work on opting out.

**Important implementation nuance:** Because Firestore's Android offline queue may persist writes beyond your own queue, do not launch writes while consent is revoked, call Firestore `terminate()` and `clearPersistence()` only if safe and supported (not while active), and document that uploads already accepted by the SDK prior to opt-out may not be cancellable. Design for minimal queued Firebase writes, keep your SQLite as the primary queue, and make this limitation explicit in consent wording. If this conflicts with hard opt-out expectations, change the worker transport plan and document it before coding rather than promising impossible cancellation.

## 3. FIREBASE CONSOLE SETUP — HUMAN CHECKPOINT (NO GUESSED CONFIG)

The user must perform these tasks in [Firebase Console](https://console.firebase.google.com/). An agent may provide instructions but must **never** fabricate `google-services.json`, application IDs, API keys, SHA fingerprints, cloud project IDs, or credentials.

1. Create/select a Firebase project named something like `ChopTorch Diagnostics`. Keep project on **Spark**, with **no linked billing account**.
2. Add **Android app** with package `com.barbarioli1.choptorch` (this is the shipping-style app used for closed testing). Optionally add second Android app `com.barbarioli1.choptorch.devclassic` for local/dev testing.
3. Download the actual Firebase `google-services.json` and place it at `app/google-services.json`. A single downloaded config can cover both variants **only if it actually includes matching client entries for both**; otherwise arrange variant-specific configs following official Google Services plugin rules. Never edit package names inside the JSON to make them match. Agent should verify rather than assume.
4. In **Build -> Firestore Database**, create exactly **one `(default)` database**, **Standard edition**, using an appropriate supported region. Pick locked/production mode initially; immediately deploy the restrictive rules from section 6. **Do not use test mode for rollout**.
5. In **Authentication -> Sign-in method**, enable **Anonymous**.
6. In **App Check**, register the shipping app with **Play Integrity**, using Google Play's actual app-signing SHA-256 certificate, not automatically the upload certificate. Link the proper Google Cloud project/Play Integrity API if required. For dev builds use the official debug provider and explicitly allowlist locally generated debug tokens; never ship debug App Check provider in release. **Do not enable Firestore App Check enforcement until closed-test Play installs are verified and metrics show healthy valid traffic**. Once validated, enable enforcement and verify uploads remain successful.
7. Install/configure the uploaded JSON in a local worktree. Do not publish, screenshot, or log Firebase admin credentials or debug tokens.
8. Manually record project ID, Firestore region, collection path, and whether App Check enforcement is on in a *non-secret deployment checklist*.

**Agent checkpoint:** If Firebase console steps aren't done, continue with pure-Java aggregation, local storage, UI, rules, and unit tests, but explicitly mark end-to-end upload `BLOCKED: Firebase configuration missing`. Do NOT assert a working backend.

## 4. PERMITTED SOURCE FILE CHANGES

**Existing files permitted:**

- `build.gradle`: add Google Services Gradle plugin declaration (version pinned after checking official docs; version seen in Nov 2026 Firebase setup docs: `4.5.0`), `apply false`.
- `app/build.gradle`: apply Google Services plugin; add **Firebase BoM** and `firebase-auth`, `firebase-firestore`, optional `firebase-appcheck-playintegrity` / `firebase-appcheck-debug` based on build type; add pinned `androidx.work:work-runtime` dependency (use current official stable, do not guess version); add `BuildConfig.ENABLE_REMOTE_DIAGNOSTICS` from `providers.gradleProperty('diagnosticsEnabled') ... getOrElse(false)`; keep existing shipping/training/signed-release logic unchanged.
- `app/src/main/AndroidManifest.xml`: add `android.permission.INTERNET`; optionally `ACCESS_NETWORK_STATE` if needed for WorkManager network constraint; do not alter existing FGS type, CAMERA, or receiver/service exported values.
- `app/src/main/java/com/example/choptorch/GestureDetector.java`: add read-only observation hook for **scored windows**; keep detector outputs and timing identical; zero side effects on observer failures.
- `app/src/main/java/com/example/choptorch/GestureDetectionService.java`: emit state events from existing callbacks/recovery/start/error paths; no I/O in sensor callback.
- `app/src/main/java/com/example/choptorch/TorchController.java`: add passive callback reporting of confirmed/unavailable state; do not change toggle semantics, camera selection, feedback, and UI updates.
- `app/src/main/java/com/example/choptorch/MainActivity.java`: add consent switch and human-readable explanatory text to `showSettings()` and visible diagnostics status; optional `Report missed chop` UI, which must work even when the score was below 50%. Don't require a new screen framework.
- `app/src/main/assets/privacy.html` and `privacy-pt.html`: carefully revise both languages and date, including exactly what is uploaded, storage, opt-in/out, Firebase/Google processing, approximate retention, contact/deletion request, and removal limitations; remove claims of absolutely no INTERNET/no data transmission.
- `app/src/main/res/values/strings.xml`: add strings as needed; keep English and Portuguese privacy pages aligned.
- `.gitignore`: as appropriate, ignore only genuine private/generated files; do not blanket-ignore source or tests. `google-services.json` is client configuration, not an admin private key, but choose repo handling deliberately; never commit a service-account key.

**Add new files** (names below are suggested and should be followed unless a name collision exists):

- `app/src/main/java/com/example/choptorch/diagnostics/DiagnosticEvent.java`
- `app/src/main/java/com/example/choptorch/diagnostics/AttemptAggregator.java` — pure Java, easily unit-testable.
- `app/src/main/java/com/example/choptorch/diagnostics/DiagnosticsConsent.java`
- `app/src/main/java/com/example/choptorch/diagnostics/DiagnosticsQueue.java` — SQLiteOpenHelper.
- `app/src/main/java/com/example/choptorch/diagnostics/DiagnosticsRecorder.java` — bounded single-thread executor, no sensor-thread disk I/O.
- `app/src/main/java/com/example/choptorch/diagnostics/DiagnosticsUploadWorker.java`
- `app/src/main/java/com/example/choptorch/diagnostics/DiagnosticsScheduler.java`
- `app/src/main/java/com/example/choptorch/diagnostics/FirebaseDiagnosticsClient.java` — auth/init and writes, no eager initialization when off.
- `firestore.rules` — deploy manually or with Firebase CLI; do not invent a project association.
- `docs/diagnostics-deployment.md` — actual steps taken; no sensitive values.
- `docs/diagnostics-cloud-setup-report.md` — CLI verification and remote setup status; no secrets.
- Pure-Java tests for `AttemptAggregator` (under `tests/`) and Android instrumentation tests for queue/consent, as feasible.

Do not modify model assets, custom training files, signing keys, `gradle/wrapper`, or GitHub Actions without a proven build blocker and written explanation.

## 5. EXACT DIAGNOSTIC SCHEMA (VERSION 1)

Firestore path: `diagnosticEvents/{uid}/events/{eventId}`. Firebase Auth's anonymous UID is the `{uid}` path segment. Generate `eventId` as UUID v4 **locally** when the attempt starts; never use sequential IDs or device hardware IDs.

**One complete Firestore document per attempt** (after closure/torch confirmation timeout). This eliminates a stream of in-place updates, and avoids writing 10 model predictions as 10 Firestore documents. Fields must be bounded, and no other fields should be uploaded:

```json
{
  "schemaVersion": 1,
  "eventId": "uuid-v4-string",
  "kind": "gesture_attempt",
  "createdAtEpochMs": 1794520000000,
  "appVersionName": "2.2",
  "appVersionCode": 4,
  "manufacturer": "example",
  "deviceModel": "example",
  "androidSdk": 36,
  "screenInteractive": false,
  "powerSaveMode": false,
  "maxScore": 0.914,
  "predictionCount": 5,
  "features": [0.0, 0.0],
  "strongPeaks": 2,
  "peakGapMs": 380,
  "maxGyro": 5.2,
  "hasDoubleChopShape": true,
  "passedActivationScoreAndShape": true,
  "triggered": true,
  "requestedTorchOn": true,
  "torchOutcome": "confirmed_on",
  "torchErrorClass": null,
  "torchConfirmationLatencyMs": 87,
  "recoveryEpisodeCount": 0,
  "durationMs": 760
}
```

**NOTE:** The `features` array above is deliberately shortened for readability; actual schema must have exactly **27 finite numeric values** when present, not 2. For events lacking a model prediction (e.g. recovery), `features` is absent or `null`, not an invented array.

**Allowed `kind` values:** `gesture_attempt`, `sensor_recovery`, `torch_error`, `missed_chop_report`, `monitor_start_failure`. For non-gesture events set score/feature fields null or omit them; distinguish a user-reported missed chop from automatically confirmed failure.

**Allowed `torchOutcome` values:** `not_requested`, `api_error`, `api_returned_pending`, `confirmed_on`, `confirmed_off`, `unavailable`, `confirmation_timeout`. Do **not** treat a lack of ON callback as a confirmed failure; call it unconfirmed. For a completed event, `api_returned_pending` must only occur if the app is ending/cancelled before timeout and that uncertainty is explicitly preserved.

**Timestamp semantics:** `createdAtEpochMs` is the client's wall-clock time (not trusted/server time). For duration/latency use `SystemClock.elapsedRealtimeNanos()` / sensor monotonic timestamp **consistently**; don't subtract wall-clock time from monotonic time. Optionally add a server timestamp only if it does not violate rule/schema restrictions and is written in same final write.

**Data minimization:** truncate `manufacturer` and `deviceModel` to max 64 characters each; never use exception message as uploaded text (it may contain sensitive data); upload only a safe `torchErrorClass` allowlist such as `CameraAccessException`, `SecurityException`, `IllegalStateException`, `Other`. Do not upload stack traces or debug logcat. Only model features, not raw sensor history.

**Enums and max bounds:** `0 <= maxScore <= 1`; `1 <= predictionCount <= 100`; `0 <= durationMs <= 4000` for gesture attempts; `features.length == 27` if present; restrict max document payload to **16 KiB** (way below Firestore's hard document limit). Store `eventId` and `uid` only in their prescribed locations (do not use phone ID). Validate this at source and in Firestore rules where feasible.

## 6. FIRESTORE SECURITY RULES — COPY, TEST, DEPLOY

Set up as strict as possible; event creation by the authenticated owner, no updates/deletes/reads by clients, default deny elsewhere. This ruleset intentionally keeps the schema check modest to avoid brittle invalid rules; augment it after testing with the Rules simulator/emulator. Avoid putting cloud administration into a mobile APK.

```firestore
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /diagnosticEvents/{uid}/events/{eventId} {
      allow create: if request.auth != null
        && request.auth.uid == uid
        && eventId.matches('^[0-9a-fA-F-]{36}$')
        && request.resource.data.keys().hasOnly([
          'schemaVersion', 'eventId', 'kind', 'createdAtEpochMs',
          'appVersionName', 'appVersionCode', 'manufacturer', 'deviceModel',
          'androidSdk', 'screenInteractive', 'powerSaveMode', 'maxScore',
          'predictionCount', 'features', 'strongPeaks', 'peakGapMs',
          'maxGyro', 'hasDoubleChopShape',
          'passedActivationScoreAndShape', 'triggered', 'requestedTorchOn',
          'torchOutcome', 'torchErrorClass', 'torchConfirmationLatencyMs',
          'recoveryEpisodeCount', 'durationMs'
        ])
        && request.resource.data.keys().hasAll([
          'schemaVersion', 'eventId', 'kind', 'createdAtEpochMs'
        ])
        && request.resource.data.schemaVersion == 1
        && request.resource.data.eventId == eventId
        && request.resource.data.kind in [
          'gesture_attempt', 'sensor_recovery', 'torch_error',
          'missed_chop_report', 'monitor_start_failure'
        ]
        && request.resource.data.createdAtEpochMs is int;
      // Allow identical, owner-authenticated retries, but never changed contents.
      allow update: if request.auth != null
        && request.auth.uid == uid
        && request.resource.data == resource.data;
      allow read, delete: if false;
    }
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

**IMPORTANT RULES REVIEW:** The client must enforce a 16 KiB maximum serialized payload because the sample rules do not attempt a byte-size restriction. Verify the full ruleset in the Firestore Rules simulator/emulator before deployment; never deploy an unvalidated rule. `hasOnly` and `hasAll` apply to map keys, and `eventId.matches(...)` applies to the path string. The `allow update` line is an **intentional exception** for retries of the same document with completely identical data. A changed update, cross-user update, read, or delete remains forbidden. The agent must use stable UUID document IDs, serialize exactly the same fields/values for retries, and test both unchanged and changed updates. Add stricter type/size/field checks if supported and validated; do not weaken owner checks or default-deny. Firestore client writes are **not trustworthy just because they came from the official app**.

**Agent note:** Authentication + App Check reduce abuse but do not mathematically prove a human performed a chop. Monitor quotas. Do not promise impossible abuse prevention from mobile-client rules.

## 7. IMPLEMENTATION ORDER — DO NOT SKIP OR REORDER

### Phase A — Baseline / no-regression snapshot

1. `git status --short`; list current branch, existing changes and exact build commands in README/CI.
2. Inspect all files in section 1. Write down existing threshold, shape requirement, double-hit logic, cooldown, torch behavior, SDK/build IDs.
3. Run available pure-Java tests (`tests/run-core-tests.sh` if executable; otherwise adapt invocation without changing tests). Run existing Gradle compile if local wrapper and SDK are available. Record baseline outcomes.
4. Create a dedicated branch `feature/opt-in-firebase-diagnostics` if safe; do not discard user modifications.

### Phase B — Pure Java attempt aggregation / tests

1. Implement `DiagnosticEvent` as immutable validated data; ensure cloning of feature arrays; no Android dependencies in pure Java model/aggregator.
2. Add `GestureDetector.PredictionObserver` with `onPrediction(...)` and **read-only** prediction snapshot, set via `setPredictionObserver(...)` or constructor overload. Construct snapshot immediately after score and `pass` are determined; invoke after detector state update but before return; never modify any existing decisions. If observer throws RuntimeException, suppress it safely and record only a local log warning if appropriate, not a crash.
3. Implement `AttemptAggregator` to receive snapshot data and emit finalized events through a callback; use timestamps from detector's `sample.timeMs` for grouping and monotonic service time for timeout scheduling. Tests must cover single qualifying prediction, series grouping, gap split, max duration split, triggered finalization, no qualifying predictions, finite score validation, shape-failed 90% score, and observer exception safety.
4. Explicitly assert the unchanged model result and triggered timestamps with and without observer in an existing detector test or new test.

### Phase C — Durable queue, consent and UI

1. Implement `DiagnosticsConsent` using existing app-private `SharedPreferences` with default false and build flag gate. Consent must be persisted. Opt-out must invalidate in-flight write generation, clear SQLite queue, cancel work, and prevent subsequent queue appends.
2. Implement `DiagnosticsQueue` SQLite schema with `event_id TEXT PRIMARY KEY`, `created_at_ms INTEGER NOT NULL`, `json_payload TEXT NOT NULL`, a small `kind`, and optionally `in_flight` only if required for worker collision safety; avoid storing raw sensor streams. Implement atomic enqueue/list/delete/clear/count/size/cap pruning. Database operations run exclusively on diagnostics executor or worker thread, never sensors/UI.
3. Implement `DiagnosticsRecorder` as app-scoped or service-owned bounded executor accepting tiny immutable prediction snapshots. Queue work asynchronously. Record short error/status info without raw data. Avoid memory leak: disconnect observer when monitoring stops; shutdown/reuse executor responsibly.
4. Add a Settings section `CLOSED-TEST DIAGNOSTICS` to `MainActivity.showSettings()` only when `BuildConfig.ENABLE_REMOTE_DIAGNOSTICS == true`. Explain: collection only while opted in; derived movement features, confidence, torch outcome, device model, screen/power state; Google Firebase backend; automatic background upload on connectivity; no GPS/camera images; OFF clears local pending events but previously uploaded data requires deletion request. Add a switch **default OFF**; make disabled feature absent on ordinary builds.
5. Add status text `Off`, `Enabled — waiting for network`, `Enabled — sending`, `Enabled — last upload [timestamp]`, `Upload error — [safe code]` as appropriate. Keep status non-sensitive. Add optional `Report missed chop` button only if implementable safely; its purpose is specifically to capture cases that never score above 50%. A tester click should capture already-buffered most recent candidate metadata if available, **not retroactively invent score**, and emit a `missed_chop_report` if opted in. No raw stream necessary.

### Phase D — Torch confirmation, recovery, and service hooks

1. Preserve the current `TorchController.toggle()` code and `CameraManager.TorchCallback`. Add an optional passive observer delivering `(cameraIdMatch, eventKind, enabled, monotonicTimestamp)` for changes and unavailable. Call it without throwing into the camera callback, and maintain `Listener.onTorchState` behavior untouched.
2. In `GestureDetectionService`, attach the collector/observer after consent enabled (or attach a no-op collector safely; no data captured while off), and **never** touch model activation decisions.
3. A detection event must record `requestedTorchOn` correctly, before calling `toggle()`, using the pre-toggle `torch.isOn()` state. Tie subsequent callback to the request ID; if quick-settings external torch state causes a callback without a matching request, don't claim gesture success. The camera callback can run on main looper; use safe handoff into diagnostics executor.
4. Record any `torch.toggle()` exception with the recognized error class; if callback never arrives in 2 seconds, `confirmation_timeout`. Do not claim physical light was verified; Android callback confirms camera torch mode, not measured emitted light.
5. From `startRecovery(...)` create `sensor_recovery` diagnostic with safe recovery reason enum, count, screen and power state. From failed service startup send `monitor_start_failure` only after consent; don't change stop/restart policy.
6. Keep `DIAGNOSTICS = BuildConfig.ENABLE_TRAINING` existing log gates independent of remote diagnostics; changing the BuildConfig feature flag must not accidentally enable training in shipping builds.

### Phase E — Firebase, worker and rules

1. Once Firebase CLI or console setup is complete, apply pinned Google Services plugin and Firebase BoM (current official version in reviewed docs: `35.1.0`; verify at implementation time), dependencies for Auth and Firestore, and WorkManager; use Android Java APIs. The Firebase BoM manages Firebase module versions; individual Firebase artifacts have no explicit versions.
2. Make build flag `ENABLE_REMOTE_DIAGNOSTICS` default false and `true` only with `-PdiagnosticsEnabled=true`. Add an explicit check that `shipping` is true for builds distributed to Google Play; do not change current shipping behavior.
3. Add `INTERNET` permission; ensure app still builds on minSdk 26 and target 36.
4. Implement Firebase Auth sign-in anonymously on background worker when opted in and no current user; reuse existing `currentUser`. The app itself has no sign-in screen.
5. Keep Firestore collection exactly as section 5 and deploy tested `firestore.rules` only with approval, including identical-payload retry exception. If the agent cannot verify proper server acknowledgment, do not clear local queue early.
6. Implement WorkManager one-time CONNECTED retry work, 20-event cap, exponential backoff; prevent concurrent workers and lifecycle duplication. Use eventId as doc ID; no auto-ID `add()` calls. Do not call Firestore from sensor path.
7. Set up App Check Play Integrity with valid signing cert for real Play-distributed closed test. Only enable enforcement after validating requests. Never block legitimate testers by assuming debug release fingerprints match Play app-signing fingerprints.
8. Validate actual Firebase production console round trip on a test device with consent enabled, Wi-Fi connected, then flight mode and re-connect. Report any prerequisites that cannot be verified locally.

### Phase F — Privacy and delivery

1. Update BOTH privacy HTML pages to align with reality, including opt-in transmission of derived motion features, device/OS/app data and torch results to Firebase, background upload behavior, retention, deletion request contact, and restrictions on turning off future sharing versus deleting past uploads. Keep localization accurate and describe Google's role clearly.
2. Update Google Play Data Safety declarations and privacy-policy hosting **manually in Play Console** before shipping; agent must output this as an explicit external action. Consult official Google Play policies. Note: closed testing is still a distribution of the app, not a waiver for misleading disclosures.
3. Document a simple Firebase Console workflow to inspect records, count `confirmed_on`, `api_error`, `confirmation_timeout`, `unavailable`, filter by manufacturer/model, compare screenInteractive true/false, and analyze 0.50–0.82 scores separately from >=0.82 scores. Avoid suggesting custom billing-dependent cloud SQL tools.
4. Create concise `docs/diagnostics-deployment.md`: Firebase setup done/missing; exact build command; build flag behavior; status/opt-out behavior; how to view data; how to delete old data manually; free quota monitoring.
5. Final response MUST list every changed file, tests/build command and actual result, exact Firebase manual steps still required, and known limitations. Do not claim "done" for missing Firebase config, Play data safety, or untested uploads.

## 8. TEST MATRIX — BLOCK RELEASE UNTIL SATISFIED

### Pure Java tests

- [ ] Scores `.49` and `.50`: `.49` creates no qualifying gesture attempt; `.50` qualifies for diagnostics only; activation rules remain at `.82`.
- [ ] `.90` with `hasDoubleChopShape=false` is logged and **does not trigger**.
- [ ] `.83` with shape true but only one positive prediction does not trigger.
- [ ] Two correct positives with cooldown met still trigger exactly as before.
- [ ] One movement with five predictions yields **one** attempt event (with max score and correct count).
- [ ] Separate gestures >800 ms apart yield separate events; candidate max duration 4 s.
- [ ] Detector observer throws -> detection behavior unchanged, app does not crash.
- [ ] Feature array is cloned; mutation after capture cannot mutate stored record.
- [ ] Nonfinite scores/features are dropped safely, no crash or invalid JSON.

### Android storage/consent tests

- [ ] `ENABLE_REMOTE_DIAGNOSTICS=false` does not sign in or write diagnostics even if stale consent preference is true.
- [ ] On fresh install, consent OFF, zero telemetry/sign-in/network requests.
- [ ] Enabling consent lets valid events enter SQLite off the sensor thread.
- [ ] Disabling consent clears queue, invalidates pending app uploads, cancels WorkManager, blocks new app events.
- [ ] Queue survives process kill and reboot until confirmed upload or opt-out; cap 500 and 2 MiB enforced.
- [ ] No network: events remain local; when online: they upload automatically without tester action.
- [ ] Network loss mid-send: no duplicate Firestore docs; no local event deleted before confirmed server receipt.
- [ ] Anonymous auth failure: safe retry, no app crash, flashlight still works.
- [ ] Firestore `PERMISSION_DENIED`: queue preserved, UI records error status, no upload spam.
- [ ] Events are not uploaded when consent is OFF, even after previously granted consent.

### Torch / foreground / privacy tests

- [ ] `toggle()` successful return but no callback -> `confirmation_timeout` (NOT confirmed ON).
- [ ] Matching callback ON -> `confirmed_on`; matching callback OFF -> `confirmed_off`.
- [ ] `onTorchModeUnavailable` -> `unavailable` (not generic success).
- [ ] `CameraAccessException` -> `api_error` and safe class, no raw exception text.
- [ ] Screen off/locked: gesture detection continues with unchanged performance; WorkManager may defer uploads; uploads should resume later.
- [ ] Manual flashlight switch and vibration feedback remain exactly as before.
- [ ] Existing false-positive feedback/training behavior not touched.
- [ ] No GPS/permissions creep, no raw accelerometer/gyroscope samples in storage/network.
- [ ] Both privacy policies revised and Play Data Safety update flagged.

### Firestore security tests

- [ ] Valid create at own `/diagnosticEvents/{uid}/events/{eventId}` accepted.
- [ ] Unauthenticated write rejected.
- [ ] Write to other UID rejected.
- [ ] Unknown field and invalid enum rejected.
- [ ] Read, delete, changed update rejected.
- [ ] Exact same-payload idempotent retry permitted, and no alteration is permitted.
- [ ] Any other collection path rejected.

### Builds

- [ ] Default non-shipping debug build compiles; feature OFF by default.
- [ ] `-Pshipping=true` build compiles; feature OFF by default; training disabled.
- [ ] `-Pshipping=true -PdiagnosticsEnabled=true` closed-test build compiles and runs on real device.
- [ ] Dev app `.devclassic` builds only if its Firebase config is registered and correctly matches; no package-ID fabrication.
- [ ] No existing core sensor detection test regressions.

## 9. ANALYSIS AND QUOTA GUARDRAILS

Firestore Spark **ongoing** free allowances as of November 2026: 1 GiB total storage, 20,000 document writes/day, 50,000 document reads/day, 20,000 deletes/day, 10 GiB outbound/month. The free database is limited to one per project, and managed TTL deletes are not part of free usage. One movement attempt = ideally one Firestore document write; 100 tester-days × 100 attempts = ~10,000 writes/day, but account for retries and error events. Monitor Firestore Usage and storage weekly; manually delete old events in Firebase Console or other permitted admin workflow (no paid TTL). Daily quotas don't mean unlimited storage.

**Analysis rules:**
- `maxScore` is model confidence, not a measured probability that a human actually chopped.
- `.50 <= score < .82` are **subthreshold diagnostic candidates**, not necessarily app failures.
- `score >= .82` AND bad shape isn't a trigger failure; report shape rejection separately.
- `triggered=true` and `requestedTorchOn=true` with `torchOutcome=api_error/unavailable/confirmation_timeout` indicates a torch-path issue; `confirmation_timeout` is inconclusive, not proven light-off.
- `torchOutcome=confirmed_on` only confirms Android torch-state callback, **not the photons from a hardware flashlight**.
- For gestures scoring below `.50`, only explicit user missed-chop reports or separate recovery events give evidence; automatically capturing *every* below-50% movement would require broader collection and is outside scope.

## 10. STOP CONDITIONS / NEVER DEVIATE

**STOP, document BLOCKED, and ask the human for the specific missing external input** if:
- Firebase config is missing or mismatched; do not fabricate it.
- Firestore rules can't be validated, or security scope would require public writes.
- Actual Play Integrity app-signing SHA or project linkage is unavailable for enforcement.
- Gradle version resolution/Android SDK missing; report build as unverified rather than silently upgrading whole project.
- Consent design or privacy text is omitted.
- Requested diagnostic implementation would alter torch behavior or detection math.
- Reliable prevention of sending *after opting out* is impossible with an approach chosen; explicitly report the limitation and do not conceal it.

**Successful result:** A tester explicitly opts in; ChopTorch remains identical as a flashlight app; qualifying motion attempts and relevant torch failures are persisted locally, automatically uploaded in batches when connected, securely stored under anonymous UID in Firestore, and inspectable by project owner. Without consent or with default build flag OFF, **nothing is collected or sent**.

## 11. OFFICIAL REFERENCES (RECHECK AT IMPLEMENTATION TIME)

- Firebase CLI projects, apps, sdkconfig, SHA, Firestore: https://firebase.google.com/docs/cli
- Firebase CLI Authentication provider configuration: https://firebase.google.com/docs/auth/configure-providers-cli
- Cloud Firestore CLI database management: https://firebase.google.com/docs/firestore/manage-databases

- Firebase Android setup + current SDK/Gradle versions: https://firebase.google.com/docs/android/setup
- Anonymous Authentication: https://firebase.google.com/docs/auth/android/anonymous-auth
- Firestore rules conditions: https://firebase.google.com/docs/firestore/security/rules-conditions
- Firestore free quota: https://firebase.google.com/docs/firestore/quotas
- Firebase Spark: https://firebase.google.com/docs/projects/billing/firebase-pricing-plans
- App Check on Android using Play Integrity: https://firebase.google.com/docs/app-check/android/play-integrity-provider
- Android durable background work: https://developer.android.com/develop/background-work/background-tasks/persistent

**AGENT: Work phase by phase. Run the designated tests after each phase. Preserve all unrelated behavior. In the final report explicitly distinguish implemented, verified, blocked, and human-console steps.**
