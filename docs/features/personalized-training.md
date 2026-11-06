# Feature: personalized training

## User outcome

In a development build, a user can teach ChopTorch what their own double-chop looks like and identify movements that should not activate the torch.

## Eligibility

Training requires at least:

- 6 positive examples labeled as two chops.
- 6 negative examples labeled as another movement.

The app records a short movement, extracts the same 27 features used at runtime, and stores the labeled vector. Training does not use a separate feature pipeline, so the trained model and live detector share the same contract.

## Training flow

1. Start a recording from the training UI.
2. Capture roughly two seconds of motion.
3. Review and label the example positive or negative.
4. Repeat until both class minimums are met.
5. Train the compact MLP on a background worker.
6. Report training accuracy and class counts.
7. Save the model and make it available to the detector.

The network has 27 input values, an 18-unit hidden layer, and one output probability. Training runs for 850 epochs with a learning rate that linearly decays from 0.035 to zero and L2 regularization of 0.0001.

## False-positive feedback

After an accepted torch-on detection, the app keeps the triggering feature vector and score for a ten-minute feedback window. If the user marks that detection as incorrect:

1. The exact triggering vector is added as a negative example.
2. The example set is persisted.
3. If both class minimums are still satisfied, the model retrains automatically.
4. The in-memory and persisted model are replaced after training completes.

Feedback is development-only and is disabled in shipping mode.

## Persistence contract

Examples are stored under `examples_v1` as JSON entries with a boolean label and a 27-value feature array. `GestureStore` owns serialization and model-currentness checks; the activity should not write these preferences directly.

The current implementation does not checkpoint an in-progress recording. A process death before labeling can lose that sample. Training data also has no first-class import/export workflow apart from the model export path.

## Safety and UX rules

- Training must never block the main UI thread.
- A model should not replace the current model until training has produced valid parameters.
- A stale or incompatible model must be rejected rather than used for inference.
- Training and feedback controls must be hidden or disabled in shipping builds.
- Erasing all data must clear examples, the stored model, detection feedback, and any in-memory current model.

## Verification

The pure-Java smoke test verifies neural-network training, held-out probabilities, and the trainer's class-count boundary: five examples in either class are rejected, while six in each class are accepted. `GestureStore` persistence and feedback tests require Android `Context`, `SharedPreferences`, and `org.json`; they are not covered by the plain-Java runner.

## Action plan

### Status and scope

Recording, labeling, training, feedback, and model export are implemented only when `BuildConfig.ENABLE_TRAINING` is true (the default development build). Shipping mode (`-Pshipping=true`) disables these controls and does not load or persist the development example set. Training runs on an executor; `GestureStore` methods are synchronized and `saveModel()` rejects a result if the example count changed during training. The app reports fit on the training examples; it does not calculate held-out accuracy in the UI.

This plan verifies the existing development workflow and adds targeted Android-aware tests. Recording recovery and training-example import/export are optional product decisions, not prerequisites.

### P0: Verify development and shipping behavior

1. Run `bash tests/run-core-tests.sh` and record that it currently covers `CoreSmokeTest` and `SeedDedupTest`, not Android UI or `GestureStore` persistence.
2. In [MainActivity.java](../../app/src/main/java/com/example/choptorch/MainActivity.java), walk through `beginRecording()`, `finishRecording()`, `confirmRecording()`, and `beginTraining()`. Confirm that a recording must span at least 1,300 ms, that labels are saved only after confirmation, and that leaving the activity cancels an unfinished recording.
3. Verify that `beginTraining()` requires six positive and six negative examples, copies the examples before submitting the training job, and posts its completion message back to the main thread. Verify `TinyNeuralNet.train()` also rejects insufficient class counts.
4. Verify the feedback path in [GestureStore.java](../../app/src/main/java/com/example/choptorch/GestureStore.java) and `GestureDetectionService.retrainAfterFeedback()`: the candidate is eligible only for ten minutes, `flagFalsePositive()` consumes it once, the exact triggering feature vector is added as a negative example, and retraining is skipped until both class minimums remain satisfied.
5. Build and manually inspect development and shipping variants using the commands available in [Android and Google Play readiness](android-play-readiness.md). Confirm training/feedback/export controls exist only in development, and do not call `-Pshipping=true` a release-signing switch.

**Acceptance:** The observed UI and messages match this document; an added example makes the dev model stale until retraining; shipping has no development controls. Record build variant, package ID, device/API level, and outcomes.

### P1: Add Android-backed persistence and workflow tests

6. The root `javac` runner covers the `TinyNeuralNet.train()` class-count boundary: five examples in either class are rejected, and six in each are accepted. Before adding Android persistence tests, choose the test boundary: `GestureStore` requires Android `Context`, `SharedPreferences`, and `org.json`, so these tests cannot be treated as plain Java. No `app/src/test` or `app/src/androidTest` source set or Android test dependency is currently configured. Use an existing Android local-test setup if one is added separately, or propose the smallest suitable Robolectric/instrumentation setup in `app/build.gradle`; do not make the root `javac` script depend on Android classes.
7. Cover a successful `saveModel()` with an unchanged dataset and a rejected save after the dataset size changes while the worker is running.
8. Cover model freshness and restart behavior: after training, the model is current; adding one example makes it stale; a fresh store instance loads persisted examples/model and returns a model only when its saved count matches.
9. Cover feedback with a controllable clock only if the existing test framework can do so without production API pollution. Verify just-inside and just-outside the ten-minute boundary, a future timestamp, one successful consume, and a repeated flag that does not append another negative example. Verify retraining behavior both above and below the 6/6 minimum.
10. Cover erase-all: examples, model-currentness, feedback candidate, and stored feedback message are cleared. Include a failed-training case and verify that a failed or stale training result does not replace a usable current model.

**Acceptance:** Tests run in the selected Android test task and CI, assert persisted state rather than private fields, and do not rely on sleeps or wall-clock race timing. `bash tests/run-core-tests.sh` remains a pure-Java check and is reported separately.

### P2: Optional product decisions

11. Decide separately whether an unfinished recording should survive process death. If approved, define which data to checkpoint, retention/cleanup, whether the user must confirm recovered data, and how discard/erase behaves before changing persistence.
12. Decide separately whether users need training-example import/export. Define schema version, validation, merge/replace semantics, privacy, and recovery before implementation. The current model export exports weights, not the examples that trained them.

Do not add telemetry or upload sensor examples as part of this plan. Any collection or sharing requires an explicit product and privacy review.
