# Feature: gesture detection

## User outcome

When monitoring is active, two deliberate chop-like motions within a short interval toggle the flashlight. A single chop or unrelated movement should not trigger the torch.

## Processing pipeline

1. Read accelerometer and gyroscope events.
2. Fuse orientation with `MotionProcessor` and `AngleKalman`.
3. Buffer a 1,900 ms motion window.
4. Extract 27 features with `GestureFeatures`.
5. Score the feature vector with `TinyNeuralNet`.
6. Require the double-chop shape and two consecutive positive predictions.
7. Enforce the 1,600 ms accepted-detection cooldown.
8. Return a detection to the service, which toggles the torch.

## Feature vector

The vector is versioned as feature version 1 and contains:

- Gyroscope and acceleration means and maxima per axis.
- Gyroscope and acceleration energy measures.
- Maximum gyroscope and acceleration magnitudes.
- Signed gyroscope integrals per axis.
- Unwrapped roll and pitch ranges.
- Peak count and the best peak-pair gap.
- Gyroscope energy distributed across four time quartiles.

The resulting vector must contain exactly 27 values. Model parameters and stored examples are incompatible with a different feature layout unless the feature version changes with a migration or replacement model.

## Decision gates

A model probability of at least `0.82` is necessary but insufficient. The detector also checks:

- Two energy peaks are present.
- The best peak gap is between 190 ms and 1,100 ms.
- Two consecutive scheduled evaluations are positive.
- At least 1,600 ms have elapsed since the previous accepted detection.

The detector exposes the latest score for diagnostics and returns a structured detection only when all gates pass.

## Public detector surface

`GestureDetector` provides:

- `reset()` to clear streaming state.
- `onGyroscope(x, y, z)` to enqueue the latest gyroscope sample.
- `onAccelerometer(timestamp, x, y, z, model)` to process an accelerometer event and possibly return a detection.
- `latestScore()` for the most recent model probability.

This boundary is deliberately independent of Android UI and service code, which allows synthetic motion tests to exercise the recognition behavior.

## Failure behavior

- No model: the service should continue safely without accepting detections.
- Insufficient history: no prediction is emitted until the window has enough data.
- Invalid shape: the score may be recorded for diagnostics, but no torch action occurs.
- Cooldown active: the result is suppressed without resetting the entire sensor stream.
- Torch unavailable or permission denied: the service must not treat the attempted toggle as a successful state change.

## Verification

`CoreSmokeTest` covers synthetic single- and double-chop signals, feature dimensionality, trained probabilities, stationary gravity reconstruction, peak-pair gaps below, at, and above the 190 ms and 1,100 ms limits, and sensor-fusion resets after backward timestamps and gaps above 500 ms. `StreamingDetectorTest` exercises the current raw sensor event API and verifies seeded double-chop detections, single-chop rejection, null-model and short-history rejection, deterministic reset, a non-positive score interrupting consecutive hits, and acceptance at the exact 1,600 ms cooldown boundary. Detector-history behavior across timestamp discontinuities and real-device recognition remain unverified.

## Action plan

### Status and scope

The streaming detector is implemented in pure Java. `StreamingDetectorTest.java` feeds raw events through `onGyroscope()` and `onAccelerometer()` and runs through `tests/run-core-tests.sh`. The core suite passes, including detector and monitoring-start tests. Coverage checks no-model and short-history rejection, deterministic reset, score interruption, inclusive peak-pair bounds, cooldown equality, and sensor-fusion timestamp resets. A package-private scorer seam is used only by the same-package regression test; the public constructor continues to use the trained model unchanged. Synthetic tests are a development check, not evidence of real-world recognition quality.

### P0: Make the streaming regression test executable (complete)

1. **Establish the baseline.** From the repository root, run `bash tests/run-core-tests.sh` and record the result. Inspect [GestureDetector.java](../../app/src/main/java/com/example/choptorch/GestureDetector.java), [MotionProcessor.java](../../app/src/main/java/com/example/choptorch/MotionProcessor.java), [GestureFeatures.java](../../app/src/main/java/com/example/choptorch/GestureFeatures.java), and [StreamingDetectorTest.java](../../tests/com/example/choptorch/StreamingDetectorTest.java). Do not use the broken streaming test as a passing baseline.
2. **Replace the obsolete test call.** In `StreamingDetectorTest`, synthesize raw accelerometer values in m/s² and gyroscope values in rad/s, and feed them in the same event sequence used by the service: `onGyroscope(x, y, z)`, then `onAccelerometer(timestampNs, ax, ay, az, model)`. Convert the generated millisecond timestamps to nanoseconds exactly once. Do not construct preprocessed `MotionSample` objects and pass their fused fields as raw accelerometer input.
3. **Prove the basic outcomes.** Using deterministic, seeded sensor streams and the existing synthetic model-training pattern, assert that the intended double-chop fixture produces an accepted detection and the single-chop fixture does not. Derive expected results from the current implementation; do not copy old expected timestamps or latency bounds until the corrected test actually demonstrates them.
4. **Add the test to the runner.** Only after it passes standalone, add its source to the `javac` inputs and invoke `com.example.choptorch.StreamingDetectorTest` from [run-core-tests.sh](../../tests/run-core-tests.sh). Preserve the current temporary output directory and cleanup behavior.

**Acceptance:** `bash tests/run-core-tests.sh` exits successfully and runs `CoreSmokeTest`, `SeedDedupTest`, `MonitoringStartFlowTest`, and `StreamingDetectorTest`. The production constants remain unchanged: 1,900 ms feature window, 0.82 threshold, 160 ms prediction spacing, and 1,600 ms cooldown.

### P1: Cover detector boundaries (partially complete)

5. Add deterministic cases for no model, insufficient startup history, `reset()`, repeated movement, a non-positive prediction interrupting consecutive hits, and cooldown suppression. No-model, short-history, reset, repeated movement, score interruption, and exact 1,600 ms cooldown equality are covered. Motion-fusion resets after backward timestamps and gaps above 500 ms are also checked; detector-history behavior after timestamp discontinuities remains open. Check the current predicates in `GestureDetector` before setting expected values: the retained history includes a 160 ms margin, the extracted window is at most 1,900 ms, prediction requires more than 35 samples and more than 1,550 ms of span, and cooldown suppresses only while elapsed time is less than 1,600 ms.
6. Test peak-pair boundaries in [CoreSmokeTest.java](../../tests/CoreSmokeTest.java) or the repaired streaming test by building raw signals and passing them through `GestureFeatures.extract()`. This is covered for gaps just below, at, and above both the 190 ms lower and 1,100 ms upper limits; the assertions check actual shape and reported gap.
7. If a test cannot reliably isolate a gate because scoring and shape are coupled, first improve its deterministic fixture. The consecutive-hit reset check uses a package-private scorer seam because raw peak fixtures cannot insert a reliably timed negative score between two positive prediction windows. The public constructor retains the production model-scoring path; no mutable detector internals or production decisions are exposed or changed.

**Acceptance:** Each boundary has a test that fails when the corresponding expected outcome changes. Timing expectations are based on observed event timestamps and current code, not guessed callback latency. The core runner remains deterministic across repeated executions.

### P2: Measure device behavior before tuning

8. Before changing thresholds or claiming accuracy, define supported devices and product-approved targets for intentional-gesture detection, unintended activations per monitoring hour, and detection latency. Collect representative, consented sessions with separate participants or sessions for training and held-out evaluation; include ordinary movements and report sample sizes and uncertainty.
9. Run the device and power-state matrix described in [Android and Google Play readiness](android-play-readiness.md). Record device model, Android version, monitoring state, sensor delivery conditions, missed gestures, false activations, and torch outcome. Do not log identifiable or unnecessary raw motion data.

**Release evidence:** Link the passing core-test run and the approved real-device results. Synthetic accuracy alone does not satisfy the device-validation gate.
