# ChopTorch documentation

ChopTorch is an Android app that recognizes a double-chop motion from the accelerometer and gyroscope and toggles the device flashlight. The development build also supports on-device personalization.

## Documents

- [System design](design.md): architecture, data flow, lifecycle, persistence, build variants, and design decisions.
- [Gesture detection](features/gesture-detection.md): sensor processing, feature extraction, thresholds, gating, and detector behavior.
- [Personalized training](features/personalized-training.md): example collection, labeling, neural-network training, and false-positive feedback.
- [Background monitoring](features/background-monitoring.md): foreground-service lifecycle, permissions, notifications, wake locks, and torch control.
- [Model storage and shipping](features/model-storage-and-shipping.md): local data format, model validation, seed recovery, export, and shipping mode.
- [Production UI and battery settings](features/production-ui-and-battery.md): existing shipping screens and a proposed battery-optimization flow.
- [Android and Google Play readiness](features/android-play-readiness.md): architecture, testing, policy, release, and operational procedures for distribution.
- [Google Play shipping checklist](google-play-shipping-checklist.md): release-candidate checks, signing, CI publishing, Play Console setup, internal testing, and staged rollout.
- [Publish to Google Play tutorial](publish-to-google-play-tutorial.md): step-by-step walkthrough of the shipping checklist, from upload key to staged rollout.

## Current verification

Run the pure-Java checks from the repository root:

```bash
bash tests/run-core-tests.sh
```

These checks cover synthetic feature extraction, neural-network training, stationary gravity reconstruction, and seed deduplication. Android APK installation and real-device sensor behavior are not covered by the current test suite.
