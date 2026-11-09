package com.example.choptorch;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure-Java tests for stale-sensor rejection, recovery, cooldown integrity, and timing statistics. */
public final class SensorDeliveryGuardTest {
    private static final long DT_MS = 20;
    private static final long BASE_MS = 100_000;
    private static final float GRAVITY = 9.80665f;
    private static final long MS = 1_000_000L;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // ---- synthetic signal (same shape as StreamingDetectorTest) ----

    private static double bump(double t, double center, double width) {
        return Math.exp(-Math.pow((t - center) / width, 2));
    }

    private static float[] gyroscope(long t, boolean doubleChop, int repetitions, Random random) {
        double p = 0;
        for (int i = 0; i < repetitions; i++) {
            long offset = i * 4000L;
            p += bump(t, 2380 + offset, 85);
            if (doubleChop) p += bump(t, 3120 + offset, 85);
        }
        float noise = (float) (random.nextGaussian() * .02);
        return new float[] { (float) (4.2 * p) + noise, (float) p, (float) (0.4 * p) };
    }

    private static List<float[]> signal(boolean doubleChop, int repetitions, int seed, long endMs) {
        ArrayList<float[]> samples = new ArrayList<>();
        Random random = new Random(seed);
        for (long t = 0; t <= endMs; t += DT_MS) samples.add(gyroscope(t, doubleChop, repetitions, random));
        return samples;
    }

    private static GestureFeatures.Result example(boolean doubleChop, int seed) {
        ArrayList<MotionSample> recording = new ArrayList<>();
        MotionProcessor motion = new MotionProcessor();
        Random random = new Random(seed);
        for (long t = 1300; t <= 3200; t += DT_MS) {
            float[] gyro = gyroscope(t, doubleChop, 1, random);
            motion.onGyroscope(gyro[0], gyro[1], gyro[2]);
            recording.add(motion.onAccelerometer(t * MS, 0, 0, GRAVITY));
        }
        return GestureFeatures.extract(recording);
    }

    private static TinyNeuralNet trainedModel() {
        List<TinyNeuralNet.Example> train = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            train.add(new TinyNeuralNet.Example(example(true, 100 + i).values, true));
            train.add(new TinyNeuralNet.Example(example(false, 300 + i).values, false));
        }
        TinyNeuralNet model = new TinyNeuralNet();
        model.train(train);
        return model;
    }

    private static TinyNeuralNet alwaysPositiveModel() {
        return TinyNeuralNet.fromParameters(new double[TinyNeuralNet.HIDDEN][TinyNeuralNet.INPUT],
                new double[TinyNeuralNet.HIDDEN], new double[TinyNeuralNet.HIDDEN], 20);
    }

    // ---- harness mirroring GestureDetectionService.onSensorChanged ordering ----

    private static final class Harness {
        final SensorDeliveryGuard accelGuard;
        final SensorDeliveryGuard gyroGuard;
        final SensorRecoveryCoordinator recovery = new SensorRecoveryCoordinator();
        final GestureDetector detector = new GestureDetector();
        final TinyNeuralNet model;
        final List<Long> detectionMs = new ArrayList<>();
        final List<Long> toggleMs = new ArrayList<>();
        long lastAccelNs, lastGyroNs;
        float latestGx, latestGy, latestGz;
        int recoveryResets;

        Harness(TinyNeuralNet model, SensorDeliveryGuard accelGuard, SensorDeliveryGuard gyroGuard) {
            this.model = model;
            this.accelGuard = accelGuard;
            this.gyroGuard = gyroGuard;
        }

        Harness(TinyNeuralNet model) {
            this(model, new SensorDeliveryGuard(), new SensorDeliveryGuard());
        }

        void deliver(long eventMs, long nowMs, float[] gyro) {
            deliverSensor(true, eventMs, nowMs, gyro);
            deliverSensor(false, eventMs, nowMs, gyro);
        }

        void deliverSensor(boolean isGyro, long eventMs, long nowMs, float[] gyro) {
            long eventNs = eventMs * MS, nowNs = nowMs * MS;
            SensorDeliveryGuard guard = isGyro ? gyroGuard : accelGuard;
            SensorDeliveryGuard.Decision decision = guard.check(eventNs, nowNs);
            if (decision == SensorDeliveryGuard.Decision.DROP_STALE) {
                beginRecovery(isGyro ? "STALE_GYRO" : "STALE_ACCEL");
                return;
            }
            if (decision != SensorDeliveryGuard.Decision.ACCEPT) return;

            long gapNs = guard.consumeGapNs();
            long otherNs = isGyro ? lastAccelNs : lastGyroNs;
            long crossGapNs = otherNs == 0 ? 0 : Math.abs(eventNs - otherNs);
            String reason = gapNs > SensorDeliveryGuard.MAX_GAP_NS
                    ? (isGyro ? "GAP_GYRO" : "GAP_ACCEL")
                    : crossGapNs > SensorDeliveryGuard.MAX_GAP_NS
                            ? (isGyro ? "ACCEL_NOT_FRESH" : "GYRO_NOT_FRESH") : null;
            if (reason != null && beginRecovery(reason) && otherNs > 0
                    && crossGapNs <= SensorDeliveryGuard.MAX_GAP_NS) {
                recovery.recordFresh(isGyro ? SensorRecoveryCoordinator.Sensor.ACCELEROMETER
                        : SensorRecoveryCoordinator.Sensor.GYROSCOPE, otherNs);
            }

            if (isGyro) {
                lastGyroNs = eventNs;
                latestGx = gyro[0];
                latestGy = gyro[1];
                latestGz = gyro[2];
            } else {
                lastAccelNs = eventNs;
            }
            if (recovery.isRecovering()) {
                boolean resumed = recovery.recordFresh(isGyro
                        ? SensorRecoveryCoordinator.Sensor.GYROSCOPE
                        : SensorRecoveryCoordinator.Sensor.ACCELEROMETER, eventNs);
                if (!resumed) return;
            }
            if (isGyro) {
                detector.onGyroscope(latestGx, latestGy, latestGz);
                return;
            }
            detector.onGyroscope(latestGx, latestGy, latestGz);
            GestureDetector.Detection match = detector.onAccelerometer(eventNs, 0, 0, GRAVITY, model);
            if (match == null) return;
            detectionMs.add(eventMs - BASE_MS);
            toggleMs.add(eventMs - BASE_MS);
        }

        private boolean beginRecovery(String reason) {
            if (!recovery.begin(reason)) return false;
            detector.resetForRecovery();
            recoveryResets++;
            return true;
        }
    }

    private static void liveFeed(Harness h, List<float[]> samples, long fromMs, long toMs) {
        for (long t = fromMs; t <= toMs; t += DT_MS) {
            h.deliver(BASE_MS + t, BASE_MS + t, samples.get((int) (t / DT_MS)));
        }
    }

    private static void burstFeed(Harness h, List<float[]> samples, long fromMs, long toMs, long deliveredAtMs) {
        for (long t = fromMs; t <= toMs; t += DT_MS) {
            h.deliver(BASE_MS + t, BASE_MS + deliveredAtMs, samples.get((int) (t / DT_MS)));
        }
    }

    private static List<Long> rawTriggers(TinyNeuralNet model, List<float[]> samples, long endMs) {
        GestureDetector detector = new GestureDetector();
        ArrayList<Long> triggers = new ArrayList<>();
        for (long t = 0; t <= endMs; t += DT_MS) {
            float[] g = samples.get((int) (t / DT_MS));
            detector.onGyroscope(g[0], g[1], g[2]);
            if (detector.onAccelerometer((BASE_MS + t) * MS, 0, 0, GRAVITY, model) != null) triggers.add(t);
        }
        return triggers;
    }

    // ---- guard unit tests ----

    private static void testGuardRules() {
        SensorDeliveryGuard guard = new SensorDeliveryGuard();
        long now = 10_000 * MS;
        check(guard.check(now - 10 * MS, now) == SensorDeliveryGuard.Decision.ACCEPT, "fresh sample rejected");
        check(guard.check(now - 10 * MS, now) == SensorDeliveryGuard.Decision.DROP_OUT_OF_ORDER, "duplicate timestamp accepted");
        check(guard.check(now - 30 * MS, now) == SensorDeliveryGuard.Decision.DROP_OUT_OF_ORDER, "older timestamp accepted");

        long later = 20_000 * MS;
        for (int i = 0; i < 50; i++) {
            check(guard.check(later - 3000 * MS + i * 20 * MS, later) == SensorDeliveryGuard.Decision.DROP_STALE,
                    "stale sample accepted");
        }
        check(guard.check(later - 100 * MS, later) == SensorDeliveryGuard.Decision.ACCEPT, "first fresh sample rejected");
        check(guard.check(later - 80 * MS, later) == SensorDeliveryGuard.Decision.ACCEPT, "next fresh sample rejected");

        long afterGap = later + 620 * MS;
        check(guard.check(afterGap, afterGap + 10 * MS) == SensorDeliveryGuard.Decision.ACCEPT,
                "fresh sample after a moderate gap rejected");
        check(guard.consumeGapNs() == 700 * MS, "measurement gap was not reported separately");

        SensorDeliveryGuard future = new SensorDeliveryGuard();
        check(future.check(now + 5000 * MS, now) == SensorDeliveryGuard.Decision.DROP_FUTURE,
                "far-future timestamp accepted");
        check(future.check(0, now) == SensorDeliveryGuard.Decision.DROP_INVALID_TIMESTAMP,
            "zero timestamp accepted");
        check(future.check(-1, now) == SensorDeliveryGuard.Decision.DROP_INVALID_TIMESTAMP,
            "negative timestamp accepted");

        SensorDeliveryGuard boundaries = new SensorDeliveryGuard();
        check(boundaries.check(now - SensorDeliveryGuard.MAX_STALE_NS, now)
                == SensorDeliveryGuard.Decision.ACCEPT,
            "sample at the exact stale-age limit rejected");
        check(boundaries.check(now + 100 * MS, now) == SensorDeliveryGuard.Decision.ACCEPT,
            "sample at the exact future tolerance rejected");
        check(boundaries.check(now + 100 * MS + 1, now) == SensorDeliveryGuard.Decision.DROP_FUTURE,
            "sample beyond the future tolerance accepted");
    }

    private static void testPersistentLagFailsClosed() {
        SensorDeliveryGuard lagging = new SensorDeliveryGuard();
        long start = 100_000 * MS;
        int accepted = 0;
        int stale = 0;
        for (int i = 0; i <= 3000; i++) {
            long event = start + i * 20 * MS;
            if (lagging.check(event, event + 600 * MS) == SensorDeliveryGuard.Decision.ACCEPT) accepted++;
            else stale++;
        }
        check(accepted == 0, "600 ms lag was accepted during 60 simulated seconds: " + accepted);
        check(stale == 3001, "not all persistently delayed events were rejected: " + stale);

        List<float[]> samples = signal(true, 15, 908, 60_000);
        Harness h = new Harness(alwaysPositiveModel());
        for (long t = 0; t <= 60_000; t += DT_MS) {
            h.deliver(BASE_MS + t, BASE_MS + t + 600, samples.get((int) (t / DT_MS)));
        }
        check(h.toggleMs.isEmpty(), "continuous 600 ms buffered delivery toggled the flashlight");
        check(h.recoveryResets == 1, "persistent lag created more than one recovery episode");
        check(h.recovery.isRecovering(), "pipeline left recovery while all measurements remained stale");
    }

    private static void testTimingStats() {
        SensorTimingStats stats = new SensorTimingStats("accel", 1_000_000_000L);
        String line = null;
        for (int i = 0; i <= 50; i++) {
            long event = 1_000 * MS + i * 20 * MS;
            String result = stats.record(event, event + 10 * MS, SensorDeliveryGuard.Decision.ACCEPT);
            if (result != null) line = result;
        }
        check(line != null, "summary line not produced after the interval");
        check(line.contains("accel n=51 accepted=51 stale=0 outOfOrder=0"), "unexpected counters: " + line);
        check(line.contains("min=10.0 avg=10.0 max=10.0"), "unexpected ages: " + line);

        SensorTimingStats gap = new SensorTimingStats("gyro", 1_500_000_000L);
        gap.record(1_000 * MS, 1_005 * MS, SensorDeliveryGuard.Decision.ACCEPT);
        gap.record(1_500 * MS, 1_505 * MS, SensorDeliveryGuard.Decision.DROP_STALE);
        gap.record(2_100 * MS, 2_105 * MS, SensorDeliveryGuard.Decision.ACCEPT);
        String summary = gap.record(2_000 * MS, 2_705 * MS, SensorDeliveryGuard.Decision.DROP_OUT_OF_ORDER);
        check(summary != null && summary.contains("stale=1 outOfOrder=1"), "drop counters missing: " + summary);
        check(summary.contains("measurementGapMs[max=600.0]") && summary.contains("arrivalGapMs[max=600.0]"),
                "gap measurements missing: " + summary);
    }

    // ---- replay and recovery scenarios ----

    private static void testNormalBehaviorUnchanged(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 2, 505, 10_500);
        List<Long> expected = rawTriggers(model, samples, 10_500);
        check(expected.size() == 2, "baseline detector expected two detections, got " + expected);
        Harness h = new Harness(model);
        liveFeed(h, samples, 0, 10_500);
        check(h.toggleMs.equals(expected), "guarded fresh stream changed detections: " + h.toggleMs + " vs " + expected);
        check(h.recoveryResets == 0, "fresh stream triggered a recovery reset");
    }

    private static void testOutOfOrderDoesNotReset(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 2, 505, 6_500);
        Harness h = new Harness(model);
        liveFeed(h, samples, 0, 3_000);
        int detectionsBefore = h.detectionMs.size();
        h.deliverSensor(true, BASE_MS + 2_980, BASE_MS + 3_000,
                samples.get((int) (2_980 / DT_MS)));
        check(h.recoveryResets == 0, "out-of-order gyro sample caused a full recovery reset");
        check(h.detectionMs.size() == detectionsBefore, "out-of-order gyro entered detector processing");
        liveFeed(h, samples, 3_000 + DT_MS, 6_500);
        check(h.recoveryResets == 0, "normal delivery after out-of-order sample reset the detector");
    }

    private static void testFreshSampleAfterGap(TinyNeuralNet model) {
        Harness h = new Harness(model);
        float[] quiet = new float[] { 0, 0, 0 };
        h.deliver(BASE_MS, BASE_MS, quiet);
        h.deliver(BASE_MS + 700, BASE_MS + 710, quiet);
        check(h.recoveryResets == 1, "fresh 700 ms interruption did not trigger one fusion recovery");
        check(!h.recovery.isRecovering(), "coherent fresh streams did not leave recovery");
        h.deliver(BASE_MS + 720, BASE_MS + 730, quiet);
        check(h.recoveryResets == 1, "the same interruption caused repeated recovery resets");
        check(h.toggleMs.isEmpty(), "gap recovery produced a flashlight toggle");
    }

    private static void testStartupDoesNotLoop(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 1, 606, 1_500);
        Harness h = new Harness(model);
        liveFeed(h, samples, 0, 1_500);
        check(h.detectionMs.isEmpty(), "startup detected before minimum usable history");
        check(h.recoveryResets == 0 && !h.recovery.isRecovering(),
                "healthy startup entered a repeated recovery loop");
    }

    private static void testBufferedReplayIsDiscarded(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 2, 505, 10_500);
        check(rawTriggers(model, samples, 10_500).size() == 2, "replay test needs a stream that detects twice");

        Harness allStale = new Harness(model);
        burstFeed(allStale, samples, 0, 10_500, 10_500 + 5_000);
        check(allStale.detectionMs.isEmpty() && allStale.toggleMs.isEmpty(),
                "stale batch produced detections " + allStale.detectionMs);
        check(allStale.recoveryResets == 1, "stale replay caused repeated resets: " + allStale.recoveryResets);

        Harness freshTail = new Harness(model);
        burstFeed(freshTail, samples, 0, 10_500, 10_500 + 100);
        check(freshTail.toggleMs.isEmpty(), "batch with a fresh tail produced toggles " + freshTail.toggleMs);
    }

    private static void testFreshGestureAfterRecovery(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 5, 505, 21_000);
        Harness h = new Harness(model);
        burstFeed(h, samples, 0, 8_000, 12_000);
        check(h.toggleMs.isEmpty(), "replayed gestures toggled: " + h.toggleMs);
        liveFeed(h, samples, 12_000 + DT_MS, 21_000);
        check(h.recoveryResets == 1, "recovery reset more than once per episode: " + h.recoveryResets);
        check(h.toggleMs.size() == 2, "expected two fresh toggles, got " + h.toggleMs);
        check(h.toggleMs.get(0) > 15_120 && h.toggleMs.get(0) < 17_000, "first fresh toggle out of range: " + h.toggleMs.get(0));
        check(h.toggleMs.get(1) > 19_120, "second fresh toggle out of range: " + h.toggleMs.get(1));
    }

    private static void testCooldownIntegrity(TinyNeuralNet model) throws Exception {
        TinyNeuralNet positive = alwaysPositiveModel();
        GestureDetector detector = new GestureDetector();
        Field field = GestureDetector.class.getDeclaredField("lastTriggeredAt");
        field.setAccessible(true);
        List<float[]> samples = signal(true, 2, 707, 6_500);
        GestureDetector.Detection match = null;
        for (long t = 0; t <= 6_500 && match == null; t += DT_MS) {
            float[] g = samples.get((int) (t / DT_MS));
            detector.onGyroscope(g[0], g[1], g[2]);
            match = detector.onAccelerometer((BASE_MS + t) * MS, 0, 0, GRAVITY, positive);
        }
        check(match != null, "always-positive model never detected");
        long stamped = field.getLong(detector);
        check(stamped > 0, "detection did not record its time");
        detector.resetForRecovery();
        check(field.getLong(detector) == stamped, "resetForRecovery cleared the cooldown timestamp");
        detector.reset();
        check(field.getLong(detector) == 0, "reset() no longer clears the cooldown timestamp");

        Harness detectorOwnsCooldown = new Harness(positive);
        liveFeed(detectorOwnsCooldown, signal(true, 2, 707, 6_500), 0, 6_500);
        check(!detectorOwnsCooldown.detectionMs.isEmpty(), "detector cooldown fixture produced no detections");
        check(detectorOwnsCooldown.toggleMs.equals(detectorOwnsCooldown.detectionMs),
                "service suppressed a detector-approved detection: " + detectorOwnsCooldown.detectionMs
                        + " vs " + detectorOwnsCooldown.toggleMs);
        for (int i = 1; i < detectorOwnsCooldown.detectionMs.size(); i++) {
            check(detectorOwnsCooldown.detectionMs.get(i) - detectorOwnsCooldown.detectionMs.get(i - 1) >= 1600,
                    "detector allowed a detection inside its cooldown: " + detectorOwnsCooldown.detectionMs);
        }
    }

    private static void testIndependentSensorRecovery(TinyNeuralNet model) {
        List<float[]> samples = signal(true, 3, 808, 11_000);
        for (boolean interruptGyro : new boolean[] { true, false }) {
            Harness h = new Harness(model);
            for (long t = 0; t <= 11_000; t += DT_MS) {
                float[] gyro = samples.get((int) (t / DT_MS));
                boolean interrupted = t >= 2000 && t < 3020;
                if (!interrupted || !interruptGyro) h.deliverSensor(true, BASE_MS + t, BASE_MS + t, gyro);
                if (!interrupted || interruptGyro) h.deliverSensor(false, BASE_MS + t, BASE_MS + t, gyro);
            }
            check(h.recoveryResets == 1, "sensor interruption caused repeated resets: " + h.recoveryResets);
            check(!h.recovery.isRecovering(), "sensor recovery did not complete");
            check(!h.toggleMs.isEmpty(), "fresh gesture did not work after sensor recovery");
            check(h.toggleMs.equals(h.detectionMs), "a detector-approved toggle was lost after recovery");
        }
    }

    public static void main(String[] args) throws Exception {
        testGuardRules();
        testPersistentLagFailsClosed();
        testTimingStats();
        TinyNeuralNet model = trainedModel();
        testNormalBehaviorUnchanged(model);
        testOutOfOrderDoesNotReset(model);
        testFreshSampleAfterGap(model);
        testStartupDoesNotLoop(model);
        testBufferedReplayIsDiscarded(model);
        testFreshGestureAfterRecovery(model);
        testCooldownIntegrity(model);
        testIndependentSensorRecovery(model);
        System.out.println("PASS: stale-sensor guard, replay rejection, recovery, cooldown, and timing statistics");
    }
}
