package com.example.choptorch;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Synthetic streaming tests for latency and duplicate-trigger prevention. */
public final class StreamingDetectorTest {
    private static final long DT_MS = 20;
    private static final float GRAVITY_M_PER_S2 = 9.80665f;

    private static float[] gyroscope(long t, boolean doubleChop, int repetitions, Random random) {
        return gyroscope(t, doubleChop, repetitions, 4000L, random);
    }

    private static float[] gyroscope(long t, boolean doubleChop, int repetitions,
                                     long repetitionSpacingMs, Random random) {
        double p = 0;
        for (int i = 0; i < repetitions; i++) {
            long offset = i * repetitionSpacingMs;
            p += bump(t, 2380 + offset, 85);
            if (doubleChop) p += bump(t, 3120 + offset, 85);
        }
        float noise = (float) (random.nextGaussian() * .02);
        return new float[] { (float) (4.2 * p) + noise, (float) p, (float) (0.4 * p) };
    }

    private static double bump(double t, double center, double width) {
        return Math.exp(-Math.pow((t - center) / width, 2));
    }

    private static GestureFeatures.Result example(boolean doubleChop, int seed) {
        ArrayList<MotionSample> recording = new ArrayList<>();
        MotionProcessor motion = new MotionProcessor();
        Random random = new Random(seed);
        for (long t = 1300; t <= 3200; t += DT_MS) {
            float[] gyro = gyroscope(t, doubleChop, 1, random);
            motion.onGyroscope(gyro[0], gyro[1], gyro[2]);
            recording.add(motion.onAccelerometer(t * 1_000_000L, 0, 0,
                    GRAVITY_M_PER_S2));
        }
        return GestureFeatures.extract(recording);
    }

    private static List<Long> runStream(GestureDetector detector, TinyNeuralNet model,
                                        boolean doubleChop, int repetitions, int seed,
                                        long endMs) {
        return runStream(detector, model, doubleChop, repetitions, seed, 4000L, endMs);
    }

    private static List<Long> runStream(GestureDetector detector, TinyNeuralNet model,
                                        boolean doubleChop, int repetitions, int seed,
                                        long repetitionSpacingMs, long endMs) {
        ArrayList<Long> triggers = new ArrayList<>();
        Random random = new Random(seed);
        for (long t = 0; t <= endMs; t += DT_MS) {
            float[] gyro = gyroscope(t, doubleChop, repetitions, repetitionSpacingMs, random);
            detector.onGyroscope(gyro[0], gyro[1], gyro[2]);
            GestureDetector.Detection match = detector.onAccelerometer(
                    t * 1_000_000L, 0, 0, GRAVITY_M_PER_S2, model);
            if (match != null) triggers.add(t);
        }
        return triggers;
    }

    private static TinyNeuralNet alwaysPositiveModel() {
        return TinyNeuralNet.fromParameters(new double[TinyNeuralNet.HIDDEN][TinyNeuralNet.INPUT],
                new double[TinyNeuralNet.HIDDEN], new double[TinyNeuralNet.HIDDEN], 20);
    }

    private static void verifyConsecutiveHitReset(TinyNeuralNet model) {
        int[] shapedWindowCount = {0};
        GestureDetector detector = new GestureDetector(result -> {
            if (!result.hasDoubleChopShape) return 0.9;
            shapedWindowCount[0]++;
            return shapedWindowCount[0] == 2 ? 0.1 : 0.9;
        });
        List<Long> triggers = runStream(detector, model, true, 1, 808, 6500);
        if (triggers.size() != 1 || triggers.get(0) != 3680) {
            throw new AssertionError("Non-positive shape score did not reset consecutive hits: " + triggers);
        }
    }

    public static void main(String[] args) {
        List<TinyNeuralNet.Example> train = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            train.add(new TinyNeuralNet.Example(example(true, 100 + i).values, true));
            train.add(new TinyNeuralNet.Example(example(false, 300 + i).values, false));
        }
        TinyNeuralNet model = new TinyNeuralNet();
        model.train(train);
        GestureDetector detector = new GestureDetector();
        List<Long> triggers = runStream(detector, model, true, 2, 505, 10_500);
        if (triggers.size() != 2) {
            throw new AssertionError("Expected two accepted double-chop detections, got " + triggers);
        }
        if (triggers.get(0) < 3120 || triggers.get(1) < 7120) {
            throw new AssertionError("Detection preceded the second chop: " + triggers);
        }

        List<Long> singleChopTriggers = runStream(
                new GestureDetector(), model, false, 2, 505, 10_500);
        if (!singleChopTriggers.isEmpty()) {
            throw new AssertionError("Single chops triggered at " + singleChopTriggers);
        }

        List<Long> nullModelTriggers = runStream(
                new GestureDetector(), null, true, 2, 505, 10_500);
        if (!nullModelTriggers.isEmpty()) {
            throw new AssertionError("Null model triggered at " + nullModelTriggers);
        }

        List<Long> shortStreamTriggers = runStream(
                new GestureDetector(), model, true, 1, 606, 1_500);
        if (!shortStreamTriggers.isEmpty()) {
            throw new AssertionError("Insufficient startup history triggered at " + shortStreamTriggers);
        }

        detector.reset();
        List<Long> resetTriggers = runStream(detector, model, true, 2, 505, 10_500);
        if (!triggers.equals(resetTriggers)) {
            throw new AssertionError("Reset changed deterministic detections: " + resetTriggers);
        }
        verifyConsecutiveHitReset(model);

        List<Long> cooldownTriggers = runStream(new GestureDetector(), alwaysPositiveModel(),
            true, 2, 707, 1560, 6500);
        if (cooldownTriggers.size() != 2
            || cooldownTriggers.get(1) - cooldownTriggers.get(0) != 1600) {
            throw new AssertionError("Expected the next positive shape at the 1600 ms cooldown boundary, got "
                + cooldownTriggers);
        }

        System.out.println("PASS: streaming double-chops toggled once each at " + triggers +
                ", single-chop triggers: " + singleChopTriggers.size() +
                ", null-model triggers: " + nullModelTriggers.size() +
                ", short-stream triggers: " + shortStreamTriggers.size());
    }
}
