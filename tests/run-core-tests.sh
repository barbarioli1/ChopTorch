#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
SRC="$ROOT/app/src/main/java/com/example/choptorch"
javac -d "$OUT" "$SRC/MotionSample.java" "$SRC/AngleKalman.java" \
  "$SRC/MotionProcessor.java" "$SRC/GestureFeatures.java" "$SRC/TinyNeuralNet.java" \
  "$SRC/GestureDetector.java" \
  "$SRC/SensorDeliveryGuard.java" \
  "$SRC/SensorRecoveryCoordinator.java" \
  "$SRC/SensorTimingStats.java" \
  "$SRC/MonitoringStartFlow.java" \
  "$SRC/RecoveredExampleDeduper.java" \
  "$ROOT/tests/CoreSmokeTest.java" \
  "$ROOT/tests/com/example/choptorch/SeedDedupTest.java" \
  "$ROOT/tests/com/example/choptorch/MonitoringStartFlowTest.java" \
  "$ROOT/tests/com/example/choptorch/StreamingDetectorTest.java" \
  "$ROOT/tests/com/example/choptorch/SensorDeliveryGuardTest.java"
java -cp "$OUT" CoreSmokeTest
java -cp "$OUT" com.example.choptorch.SeedDedupTest
java -cp "$OUT" com.example.choptorch.MonitoringStartFlowTest
java -cp "$OUT" com.example.choptorch.StreamingDetectorTest
java -cp "$OUT" com.example.choptorch.SensorDeliveryGuardTest
