# Feature: model storage and shipping

## User outcome

Development users can personalize and export a model. A shipping build can carry those trained weights without exposing training controls or requiring runtime training data.

## Stored artifacts

`GestureStore` persists these artifacts in `SharedPreferences`:

| Key | Contents |
| --- | --- |
| `examples_v1` | JSON array of labeled examples; each has `positive` and a 27-value feature vector. |
| `model_v1` | Complete `TinyNeuralNet` parameters and detection configuration. |
| `last_on_detection_v2` | Triggering features, confidence, and timestamp for feedback. |
| `feedback_message_v2` | Feedback status shown by the UI. |

The serialized model includes `modelVersion`, `featureVersion`, `featureCount`, `hiddenCount`, `detectionThreshold`, `requiresDoubleChopShape`, `windowMs`, `w1`, `b1`, `w2`, `b2`, and `trainedCount`.

## Compatibility validation

On load, the runtime validates the model metadata and all parameter dimensions. A model that does not match the current feature layout or network shape is rejected. `isModelCurrent()` also prevents using a model that no longer corresponds to the stored example count.

There is no migration framework. A future feature or network layout change must either provide an explicit migration or ship a newly trained compatible model.

## Seed recovery

The development build includes 19 original examples, consisting of 7 positives and 12 negatives. On a new installation, those examples bootstrap the store when no local examples exist. `RecoveredExampleDeduper` repairs the known exact duplicate-seed state from an earlier version without deleting other recordings.

Seed restoration is intentionally one-time. Examples recorded in other application-ID variants are not imported automatically.

## Export and shipping workflow

1. Install the development build with training enabled.
2. Record and label examples or use the bundled seed data.
3. Train until the model is current and valid.
4. Export the model JSON from the development app.
5. Replace `app/src/main/assets/choptorch_model.json` with the exported model.
6. Build with `-Pshipping=true` or enable the shipping workflow option.
7. Verify that training, feedback, and export controls are disabled and the bundled model loads.

Shipping uses application ID `com.example.choptorch`; development uses `com.example.choptorch.devclassic` so development data remains isolated from older variants.

## Operational risks

- Shipping with a stale or incompatible asset should fail closed rather than silently use random/default weights.
- Replacing the shipping asset without recording its feature/model versions makes rollback and debugging difficult.
- `SharedPreferences` is convenient for this small payload but is not a robust interchange or high-volume training-data store.
- The model export path does not by itself export the labeled examples that produced the model.

## Verification

Pure-Java checks cover seed deduplication and model behavior indirectly through the smoke test. Release verification should additionally parse the asset, validate dimensions, build shipping mode, and exercise the no-training UI configuration.

## Action plan

### Status and scope

`GestureStore` validates model compatibility at runtime. In shipping mode, `app/build.gradle` also parses the bundled JSON and checks the required feature count, parameter dimensions, and finite numeric weights; optional metadata remains optional. Development mode can load persisted examples/model and falls back to the bundled model; shipping mode ignores the development example store and uses the bundled model. No model-storage Android tests currently run in the root Java test script.

### P0: Lock down the model contract and build variants

1. Re-read `GestureStore`'s `decodeModel()`, `toDoubles()`, constructor, `encodeModel()`, `saveModel()`, and `isModelCurrent()` before changing any format. Required data currently includes `featureCount`, `w1`, `b1`, `w2`, and `b2`; dimensions must match 27 inputs and 18 hidden units. Numeric arrays and `b2` are checked for finite values.
2. Treat `modelVersion`, `featureVersion`, and `hiddenCount` according to their existing `optInt(..., 1/18)` defaults; threshold, double-chop requirement, and window are checked only when present. `trainedCount` has separate load behavior for persisted development models, development bundled fallback, and shipping. Do not make optional metadata newly mandatory without an explicit compatibility decision and regenerated assets.
3. Verify the Gradle shipping guard in [app/build.gradle](../../app/build.gradle) rejects a missing asset, malformed JSON, incorrect required parameter dimensions, and non-finite required weights. Do not require optional metadata or change the runtime decoder's compatibility rules.
4. Verify that the development build includes training assets and uses its local examples/model when valid; verify that shipping (`-Pshipping=true`) excludes training assets/UI and uses only the bundled model. Keep package IDs, signing, and feature gating as distinct concerns.

**Acceptance:** A valid compatible asset loads; missing or incompatible models do not enable detection; development recordings do not leak across the distinct package IDs; existing model JSON remains readable under the current version rules.

### P1: Add model validation tests and a repeatable asset procedure

5. Add tests at an Android-aware boundary for the actual `GestureStore` decoder/loader. Do not describe them as pure Java: the store depends on Android preferences/assets and `org.json`. Use test fixtures rather than modifying the production asset.
6. Test a valid model, malformed JSON, wrong `featureCount`, wrong row/vector lengths, non-finite numeric values where the JSON parser allows representation, mismatched optional metadata when present, and missing asset. Assert a clear rejection and safe unavailable-model behavior, not an uncaught activity crash.
7. Test `saveModel()` and reload for a matching development example count, stale count rejection, export JSON parse and decode round trip, and seed duplicate repair preserving appended non-seed examples. Keep these tests separate from the root `javac` suite unless they can compile without Android dependencies.
8. For each shipping candidate, record model/feature versions, `trainedCount`, training provenance, held-out evaluation results, and a SHA-256 hash of the exact JSON asset. Run the runtime loader and representative positive/negative inference fixtures before the APK/AAB build. Define model-quality acceptance thresholds with the product owner; do not invent them in the validator.
9. Build shipping mode and inspect the resulting package/asset set. Verify no training UI/assets are present, the expected bundled model is present, and monitoring remains unavailable when the model is invalid. Preserve the previous known-good model and artifact for forward-fix comparison.

**Build caveat:** The repository workflow installs Gradle 8.13 directly. The checked-in wrapper configuration has no wrapper JAR, so use an installed compatible `gradle` command or restore a verified wrapper before using `gradlew`. `bundleRelease` also requires release signing configuration; neither the wrapper nor signing setup is currently complete.

### P2: Compatibility changes

10. Before changing feature count, feature extraction, network shape, or detector semantics, choose a migration policy: explicit migration, dual-version support, or replacing the shipped model while requiring retraining. Update format/version tests and assets together. Do not add arbitrary magnitude limits to finite weights without evaluating real exported models and recording a compatibility decision.
